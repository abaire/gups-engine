package gups.harness

import gups.core.CapturingSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Condition
import gups.core.model.CreationAttempt
import gups.core.model.CreationProvider
import gups.core.model.Player
import gups.core.world.Diagnostic
import gups.core.world.Feature
import gups.core.world.HeadlessSteps
import gups.core.world.InvariantViolation
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.attach
import gups.core.world.handle
import gups.core.world.restoreVitals
import gups.core.world.runStepsHeadless
import gups.core.world.session
import gups.core.world.submit
import gups.script.PackageKind
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldMessages
import gups.script.WorldModule
import gups.script.WorldPluginWiring
import java.util.Random

/** Pulse progress callback, invoked with the pulse index every 1000 pulses. */
internal typealias ProgressCallback = (Int) -> Unit

/**
 * Automated fuzz walker bot harness for course world verification (gate 4).
 *
 * ## Execution & Simulation Model
 * - Loads the full world in a headless harness environment with reality bubble disabled (`bubble =
 *   false`), ensuring every room, mob, and item is continuously simulated regardless of player
 *   proximity.
 * - Seeds the world's random number generator with a deterministic seed for reproducible
 *   trajectories.
 * - Spawns one automated bot player per changed zone package, placed in each zone's initial entry
 *   room.
 *
 * ## Bot Behavior & Invariant Verification
 * - At each simulation pulse, each bot selects and submits random actions: directional movement,
 *   opening/closing doors, picking up/dropping/wearing/removing items, attacking visible targets,
 *   exercising skills, talking (`say`), or invoking discovered zone commands.
 * - After each pulse, verifies that engine invariants hold across all world entities and that no
 *   forbidden diagnostic events ([FORBIDDEN_KINDS]: `"script_error"`, `"step_limit"`,
 *   `"wall_clock"`, `"quarantine"`, or `"alloc_limit"`) are emitted.
 */
class FuzzWalker(
  private val dir: WorldDir,
  private val plugins: WorldPluginWiring = WorldPluginWiring.NONE,
) {

  data class Outcome(
    val ok: Boolean,
    val pulsesRun: Int,
    val bots: Int,
    val zones: List<String>,
    val failureMessage: String? = null,
    val failedPulse: Long? = null,
    val diagnostic: Diagnostic? = null,
    val world: World? = null,
    val botStartRooms: Map<gups.core.EntityId, gups.core.Label> = emptyMap(),
  )

  companion object {
    val FORBIDDEN_KINDS =
      setOf(
        "script_error",
        "step_limit",
        "wall_clock",
        "quarantine",
        "alloc_limit",
        "invariant",
        "missing",
      )
    const val DEFAULT_PULSES = 10_000
    const val DEFAULT_SEED = 42L
  }

  /** Runs the fuzz walker for [pulses] pulses. */
  fun run(
    patterns: List<String>,
    pulses: Int = DEFAULT_PULSES,
    seed: Long = DEFAULT_SEED,
    onProgress: ProgressCallback? = null,
  ): Outcome {
    when (val loaded = loadWorld(patterns, seed)) {
      is LoadedWorld.Failure ->
        return Outcome(
          ok = false,
          pulsesRun = 0,
          bots = 0,
          zones = emptyList(),
          failureMessage = loaded.message,
        )
      is LoadedWorld.Success ->
        return runWalk(
          RunState(
            loaded.world,
            loaded.changedZones,
            Random(seed),
            mutableListOf(),
            mutableMapOf(),
            mutableMapOf(),
            1L,
            seed,
          ),
          pulses,
          onProgress,
        )
    }
  }

  /**
   * Walks a loaded world for [pulses] pulses: spawns one bot per changed zone, then steps each bot
   * through every pulse, asserting invariants and forbidden diagnostics as the world runs.
   */
  private fun runWalk(state: RunState, pulses: Int, onProgress: ProgressCallback?): Outcome {
    val world = state.world
    val changedZones = state.changedZones
    val seed = state.seed

    val spawnFailure = spawnBots(state)
    if (spawnFailure != null) {
      return creationFailure(
        spawnFailure,
        CreationFailureContext(world, 0, state.bots.size, changedZones, state.botStartRooms, seed),
      )
    }

    if (state.bots.isEmpty()) {
      return Outcome(
        ok = false,
        pulsesRun = 0,
        bots = 0,
        zones = changedZones,
        failureMessage = "Changed zones have no rooms to spawn bots in",
      )
    }

    for (pulseIndex in 1..pulses) {
      // 1. Bots survival and action planning
      for (i in state.bots.indices) {
        val botFailure = stepBot(state, i, pulseIndex)
        if (botFailure != null) return botFailure
      }

      // 2. Pulse step, then assert no invariant violation or forbidden diagnostic
      val pulseFailure = runPulseAndAssert(state, pulseIndex)
      if (pulseFailure != null) return pulseFailure

      if (pulseIndex % 1000 == 0) onProgress?.invoke(pulseIndex)
    }

    return Outcome(
      ok = true,
      pulsesRun = pulses,
      bots = state.bots.size,
      zones = changedZones,
      world = world,
      botStartRooms = state.botStartRooms,
    )
  }

  /**
   * Loads the world for [patterns] seeded with [seed] and returns it with the changed zones that
   * matched, or a failure message when loading, pattern matching or zone selection fails.
   */
  private fun loadWorld(patterns: List<String>, seed: Long): LoadedWorld {
    val loader = ScriptLoader(dir, ScriptLimits.HARNESS, plugins = plugins)
    val moduleProblems = moduleProblems(dir)
    if (moduleProblems.isNotEmpty()) {
      return LoadedWorld.Failure("Load failed: ${moduleProblems.joinToString("; ")}")
    }
    val loadResult = loader.load(dir.packages())
    if (!loadResult.ok) {
      val errs = loadResult.errors.joinToString("; ") { it.render() }
      return LoadedWorld.Failure("Load failed: $errs")
    }

    val parsedPatterns = patterns.mapNotNull { runCatching { LabelPattern(it) }.getOrNull() }
    val allPkgs = dir.packages()
    val changedZones =
      allPkgs.filter { pkg ->
        parsedPatterns.any { it.matches(pkg) } && loadResult.packages[pkg]?.kind == PackageKind.ZONE
      }

    if (changedZones.isEmpty()) {
      return LoadedWorld.Failure("No zone packages match pattern(s): ${patterns.joinToString()}")
    }

    val host = StarlarkHost(loadResult, ScriptLimits.HARNESS, loader)
    val (mod) = WorldModule.read(dir)
    val world =
      World(
        loadResult.prototypes(),
        loader.schema,
        host.rules(),
        host,
        mod.config(
          WorldConfig(seed = seed, bubble = false, checkInvariants = true),
          WorldMessages.read(dir).first,
        ),
        levelMode = mod.levelMode,
        standingHistoryPulses = mod.standingHistoryPulses,
        memoryPulses = mod.memoryPulses,
      )
    plugins.install(world)
    host.install(world)
    world.start()
    plugins.started(world)
    return LoadedWorld.Success(world, changedZones)
  }

  /**
   * Spawns one bot per changed zone in [state], each wired to a fresh session, and records the
   * start room of each. Returns the creation failure if a bot cannot be created; null otherwise.
   */
  private fun spawnBots(state: RunState): BotSpawn.Failed? {
    for (zone in state.changedZones) {
      val zoneRooms = state.world.rooms.filter { it.label.pkg == zone }.toList()
      if (zoneRooms.isEmpty()) continue
      val startRoom = zoneRooms[state.rng.nextInt(zoneRooms.size)]
      val botName = "Bot_${zone.replace('/', '_').replace('-', '_')}"
      val botChoices = drawBotChoices(state.world, state.rng)
      val spawnRequest = BotSpawnRequest(botName, startRoom.label, botChoices, state.botAttempts)
      when (val spawned = spawnBot(state.world, spawnRequest)) {
        is BotSpawn.Ok -> {
          val bot = spawned.bot
          bot.idleExempt = true
          val sessId = SessionId(state.nextSession++)
          state.world.handle(WorldControlEvent.Connect(sessId, CapturingSink()))
          state.world.attach(sessId, bot)
          state.bots += bot
          state.botStartRooms[bot.id] = startRoom.label
        }
        is BotSpawn.Failed -> return spawned
      }
    }
    return null
  }

  /**
   * Advances the bot at [index] through the per-pulse survival logic: respawns a dead bot into its
   * zone (reusing its session when alive), restores a bot that is injured or lagging, and submits a
   * random action for an idle bot. Returns the creation failure if a respawn fails; null otherwise.
   */
  private fun stepBot(state: RunState, index: Int, pulseIndex: Int): Outcome? {
    var bot = state.bots[index]
    // Survival check: bots respawn if killed/removed or heal when near death
    if (!bot.inWorld) {
      val zone = state.changedZones[index % state.changedZones.size]
      val zoneRooms = state.world.rooms.filter { it.label.pkg == zone }.toList()
      if (zoneRooms.isNotEmpty()) {
        val room = zoneRooms[state.rng.nextInt(zoneRooms.size)]
        val spawnRequest = BotSpawnRequest(bot.name, room.label, bot.choices, state.botAttempts)
        val newBot =
          when (val spawned = spawnBot(state.world, spawnRequest)) {
            is BotSpawn.Ok -> spawned.bot
            is BotSpawn.Failed ->
              return creationFailure(
                spawned,
                CreationFailureContext(
                  state.world,
                  pulseIndex,
                  state.bots.size,
                  state.changedZones,
                  state.botStartRooms,
                  state.seed,
                ),
              )
          }
        newBot.idleExempt = true
        val sessId = bot.session ?: SessionId(state.nextSession++)
        if (state.world.session(sessId) == null) {
          state.world.handle(WorldControlEvent.Connect(sessId, CapturingSink()))
        }
        state.world.attach(sessId, newBot)
        state.bots[index] = newBot
        state.botStartRooms[newBot.id] = room.label
        bot = newBot
      }
    } else if (needsRecovery(bot)) {
      state.world.restoreVitals(bot)
      // If lag/wait_state exceeds 50 pulses (5 seconds), clear it so the bot doesn't stay
      // frozen.
      bot.waitState = 0
      bot.actionQueue.clear()
    }

    // If idle, pick a random action
    if (isIdle(bot)) {
      pickBotAction(state.world, bot, state.rng, pulseIndex)
    }
    return null
  }

  /** True when a bot must be restored: injured, a vital near empty, or lagging past 50 pulses. */
  private fun needsRecovery(bot: Player): Boolean =
    bot.condition != Condition.OK ||
      bot.vitals.values.any { it.current * 4 < it.max } ||
      bot.waitState > 50

  /** True when a bot is idle: no queued action, no wait state and not casting. */
  private fun isIdle(bot: Player): Boolean =
    bot.actionQueue.isEmpty() && bot.waitState == 0 && bot.casting == null

  /**
   * Runs one pulse and asserts that it neither violated an invariant nor emitted a forbidden
   * diagnostic. Returns the failure Outcome if either fires; null otherwise.
   */
  private fun runPulseAndAssert(state: RunState, pulseIndex: Int): Outcome? {
    try {
      state.world.runPulse()
    } catch (e: InvariantViolation) {
      val d =
        Diagnostic(
          state.world.pulse,
          "invariant",
          e.message ?: "World invariant violation",
          cause = e,
          seed = state.seed,
        )
      return Outcome(
        ok = false,
        pulsesRun = pulseIndex,
        bots = state.bots.size,
        zones = state.changedZones,
        failureMessage = "Invariant violation at pulse ${state.world.pulse}: ${e.message}",
        failedPulse = state.world.pulse,
        diagnostic = d,
        world = state.world,
        botStartRooms = state.botStartRooms,
      )
    }

    val fatal = state.world.diagnostics.firstOrNull { it.kind in FORBIDDEN_KINDS }
    if (fatal != null) {
      return Outcome(
        ok = false,
        pulsesRun = pulseIndex,
        bots = state.bots.size,
        zones = state.changedZones,
        failureMessage = "[${fatal.kind}] at pulse ${fatal.pulse}: ${fatal.message}",
        failedPulse = fatal.pulse,
        diagnostic = fatal,
        world = state.world,
        botStartRooms = state.botStartRooms,
      )
    }
    return null
  }

  /** Picks one of the five bot action categories from [rng] and submits it. */
  private fun pickBotAction(world: World, bot: Player, rng: Random, pulse: Int) {
    when (rng.nextInt(5)) {
      // Category 0: an exit
      0 -> submitExit(world, bot, rng)
      // Category 1: zone or item command with empty arguments
      1 -> submitCommand(world, bot, rng)
      // Category 2: get, drop, wear or use
      2 -> submitInventoryAction(world, bot, rng)
      // Category 3: kill on a random actor
      3 -> submitAttack(world, bot, rng)
      // Category 4: say
      4 -> submitSay(world, bot, pulse)
    }
  }

  /** Walks a random exit, or announces a dead end when the room has none. */
  private fun submitExit(world: World, bot: Player, rng: Random) {
    val exits = bot.room.exits.values.toList()
    if (exits.isEmpty()) {
      world.submit(bot, "${world.commands.nameOf("say")} dead end here")
      return
    }
    val exit = exits[rng.nextInt(exits.size)]
    world.submit(bot, exit.direction.name)
  }

  /** Invokes a random zone or item command with empty arguments. */
  private fun submitCommand(world: World, bot: Player, rng: Random) {
    val zoneCmds = bot.room.proto.commands.keys.toList()
    val itemCmds = (bot.inventory + bot.room.items).flatMap { it.proto.commands.keys }
    val cmds = zoneCmds + itemCmds
    if (cmds.isEmpty()) {
      world.submit(bot, "${world.commands.nameOf("say")} nothing to use here")
      return
    }
    val cmd = cmds[rng.nextInt(cmds.size)]
    world.submit(bot, cmd)
  }

  /** Picks get, drop, wear or use, then picks a random item from the matching pool. */
  private fun submitInventoryAction(world: World, bot: Player, rng: Random) {
    val actions =
      listOf(
        ItemAction("get", bot.room.items),
        ItemAction("drop", bot.inventory),
        ItemAction("wear", bot.inventory.filter { it.proto.wear != null }),
        ItemAction("use", bot.inventory + bot.room.items),
      )
    submitItemAction(world, bot, rng, actions[rng.nextInt(actions.size)])
  }

  /** Submits [action]'s verb on a random item from its pool, or submits nothing on an empty one. */
  private fun submitItemAction(world: World, bot: Player, rng: Random, action: ItemAction) {
    if (action.pool.isEmpty()) return
    val item = action.pool[rng.nextInt(action.pool.size)]
    val kw = item.keywords.firstOrNull() ?: item.proto.name
    world.submit(bot, "${world.commands.nameOf(action.verb)} $kw")
  }

  /** Kills a random actor when combat is enabled, or looks for a fight. */
  private fun submitAttack(world: World, bot: Player, rng: Random) {
    val targets = bot.room.actors.filter { it !== bot }
    if (Feature.COMBAT in world.features && targets.isNotEmpty()) {
      val target = targets[rng.nextInt(targets.size)]
      val kw = target.keywords.firstOrNull() ?: target.name
      world.submit(bot, "${world.commands.nameOf("kill")} $kw")
    } else {
      world.submit(bot, "${world.commands.nameOf("say")} looking for a fight")
    }
  }

  private fun submitSay(world: World, bot: Player, pulse: Int) {
    world.submit(bot, "${world.commands.nameOf("say")} exploring at pulse $pulse")
  }

  /** The world and its changed zones after a successful load, or the reason loading failed. */
  private sealed interface LoadedWorld {
    data class Failure(val message: String) : LoadedWorld

    data class Success(val world: World, val changedZones: List<String>) : LoadedWorld
  }

  /** Mutable state shared by the spawn, bot-step and pulse-step functions of a run. */
  private class RunState(
    val world: World,
    val changedZones: List<String>,
    val rng: Random,
    val bots: MutableList<Player>,
    val botStartRooms: MutableMap<gups.core.EntityId, gups.core.Label>,
    val botAttempts: MutableMap<String, CreationAttempt>,
    var nextSession: Long,
    val seed: Long,
  )

  private sealed interface BotSpawn {
    data class Ok(val bot: Player) : BotSpawn

    data class Failed(val message: String, val cause: Throwable) : BotSpawn
  }

  /** An inventory item action: the command verb to submit and the pool to pick the item from. */
  private data class ItemAction(val verb: String, val pool: List<gups.core.model.Item>)

  /** Everything a bot spawn needs: its name, starting room, creation choices and creation state. */
  private data class BotSpawnRequest(
    val name: String,
    val at: gups.core.Label,
    val choices: Map<String, String>,
    val attempts: MutableMap<String, CreationAttempt>,
  )

  /** The world state at the moment a bot's creation failed, for the failure [Outcome]. */
  private data class CreationFailureContext(
    val world: World,
    val pulsesRun: Int,
    val bots: Int,
    val zones: List<String>,
    val botStartRooms: Map<gups.core.EntityId, gups.core.Label>,
    val seed: Long,
  )

  /**
   * Spawns a bot the way a player is created: with a creation provider, through an attempt keyed by
   * the bot's name, its custom steps answered only with `done`, and `prepare`. A respawned bot
   * reuses its attempt with its current choices, so it gets the same numbers again.
   */
  private fun spawnBot(world: World, request: BotSpawnRequest): BotSpawn {
    val attempt =
      world.creation?.let {
        request.attempts
          .getOrPut(request.name) {
            CreationAttempt(
              request.name,
              CreationAttempt.nameKey(request.name),
              0,
              world.config.seed,
            )
          }
          .apply {
            start = request.at
            answers.clear()
            answers.putAll(request.choices)
            answersChanged()
          }
      }
    if (attempt != null) {
      val steps = world.runStepsHeadless(attempt) { listOf("done") }
      if (steps is HeadlessSteps.Failed) {
        return BotSpawn.Failed(
          "Creation step '${steps.stepId}' failed: ${steps.error.message}",
          steps.error,
        )
      }
    }
    val bot =
      world.spawnPlayer(
        request.name,
        request.at,
        level = 1,
        choices = request.choices,
        attempt = attempt,
      )
    attempt?.prepareError?.let {
      return BotSpawn.Failed("Creation prepare failed: ${it.message}", it)
    }
    return BotSpawn.Ok(bot)
  }

  private fun creationFailure(failed: BotSpawn.Failed, context: CreationFailureContext): Outcome =
    Outcome(
      ok = false,
      pulsesRun = context.pulsesRun,
      bots = context.bots,
      zones = context.zones,
      failureMessage = failed.message,
      failedPulse = context.world.pulse,
      diagnostic =
        Diagnostic(
          context.world.pulse,
          "plugin",
          failed.message,
          cause = failed.cause,
          seed = context.seed,
        ),
      world = context.world,
      botStartRooms = context.botStartRooms,
    )

  private fun drawBotChoices(world: World, rng: Random): Map<String, String> {
    val creation = world.creation ?: return emptyMap()
    for (attempt in 1..10) {
      val answers = drawAnswers(world, creation, rng)
      if (validateAnswers(world, creation, answers) == null) return answers
    }
    return emptyMap()
  }

  /**
   * Answers every creation choice once, picking a random available option, and skips a choice when
   * none are available.
   */
  private fun drawAnswers(
    world: World,
    creation: CreationProvider,
    rng: Random,
  ): Map<String, String> {
    val answers = LinkedHashMap<String, String>()
    for (choice in creation.choices) {
      val avail =
        try {
          creation.available(world, choice.id, answers)
        } catch (e: Exception) {
          world.diagnose("plugin", "creation provider available() failed: ${e.message}", e)
          emptyList()
        }
      val options = choice.options.filter { it.id in avail }
      if (options.isEmpty()) continue
      val picked = options[rng.nextInt(options.size)]
      answers[choice.id] = picked.id
    }
    return answers
  }

  /** Asks the creation provider to validate [answers]; null means accepted. */
  private fun validateAnswers(
    world: World,
    creation: CreationProvider,
    answers: Map<String, String>,
  ): String? {
    return try {
      creation.validate(world, answers)
    } catch (e: Exception) {
      world.diagnose("plugin", "creation provider validate() failed: ${e.message}", e)
      "error"
    }
  }
}

/** Why `MODULE.gups` can't be used, if it can't: it is required, and its schema must be sound. */
internal fun moduleProblems(dir: WorldDir): List<String> {
  val (module, error) = WorldModule.read(dir)
  return if (error != null) listOf(error.render()) else module.problems()
}
