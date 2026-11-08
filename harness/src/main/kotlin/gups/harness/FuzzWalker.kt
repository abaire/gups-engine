package gups.harness

import gups.core.CapturingSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Condition
import gups.core.model.CreationAttempt
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
    onProgress: ((Int) -> Unit)? = null,
  ): Outcome {
    val loader = ScriptLoader(dir, ScriptLimits.HARNESS, plugins = plugins)
    val moduleProblems = moduleProblems(dir)
    if (moduleProblems.isNotEmpty()) {
      return Outcome(
        ok = false,
        pulsesRun = 0,
        bots = 0,
        zones = emptyList(),
        failureMessage = "Load failed: ${moduleProblems.joinToString("; ")}",
      )
    }
    val loadResult = loader.load(dir.packages())
    if (!loadResult.ok) {
      val errs = loadResult.errors.joinToString("; ") { it.render() }
      return Outcome(
        ok = false,
        pulsesRun = 0,
        bots = 0,
        zones = emptyList(),
        failureMessage = "Load failed: $errs",
      )
    }

    val parsedPatterns = patterns.mapNotNull { runCatching { LabelPattern(it) }.getOrNull() }
    val allPkgs = dir.packages()
    val changedZones =
      allPkgs.filter { pkg ->
        parsedPatterns.any { it.matches(pkg) } && loadResult.packages[pkg]?.kind == PackageKind.ZONE
      }

    if (changedZones.isEmpty()) {
      return Outcome(
        ok = false,
        pulsesRun = 0,
        bots = 0,
        zones = emptyList(),
        failureMessage = "No zone packages match pattern(s): ${patterns.joinToString()}",
      )
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

    val rng = Random(seed)
    val bots = mutableListOf<Player>()
    val botStartRooms = mutableMapOf<gups.core.EntityId, gups.core.Label>()
    val botAttempts = mutableMapOf<String, CreationAttempt>()
    var nextSession = 1L

    for (zone in changedZones) {
      val zoneRooms = world.rooms.filter { it.label.pkg == zone }.toList()
      if (zoneRooms.isEmpty()) continue
      val startRoom = zoneRooms[rng.nextInt(zoneRooms.size)]
      val botName = "Bot_${zone.replace('/', '_').replace('-', '_')}"
      val botChoices = drawBotChoices(world, rng)
      val bot =
        when (val spawned = spawnBot(world, botName, startRoom.label, botChoices, botAttempts)) {
          is BotSpawn.Ok -> spawned.bot
          is BotSpawn.Failed ->
            return creationFailure(world, spawned, 0, bots.size, changedZones, botStartRooms, seed)
        }
      bot.idleExempt = true

      val sessId = SessionId(nextSession++)
      world.handle(WorldControlEvent.Connect(sessId, CapturingSink()))
      world.attach(sessId, bot)
      bots += bot
      botStartRooms[bot.id] = startRoom.label
    }

    if (bots.isEmpty()) {
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
      for (i in bots.indices) {
        var bot = bots[i]
        // Survival check: bots respawn if killed/removed or heal when near death
        if (!bot.inWorld) {
          val zone = changedZones[i % changedZones.size]
          val zoneRooms = world.rooms.filter { it.label.pkg == zone }.toList()
          if (zoneRooms.isNotEmpty()) {
            val room = zoneRooms[rng.nextInt(zoneRooms.size)]
            val newBot =
              when (val spawned = spawnBot(world, bot.name, room.label, bot.choices, botAttempts)) {
                is BotSpawn.Ok -> spawned.bot
                is BotSpawn.Failed ->
                  return creationFailure(
                    world,
                    spawned,
                    pulseIndex,
                    bots.size,
                    changedZones,
                    botStartRooms,
                    seed,
                  )
              }
            newBot.idleExempt = true
            val sessId = bot.session ?: SessionId(nextSession++)
            if (world.session(sessId) == null) {
              world.handle(WorldControlEvent.Connect(sessId, CapturingSink()))
            }
            world.attach(sessId, newBot)
            bots[i] = newBot
            botStartRooms[newBot.id] = room.label
            bot = newBot
          }
        } else if (
          bot.condition != Condition.OK ||
            bot.vitals.values.any { it.current * 4 < it.max } ||
            bot.waitState > 50
        ) {
          world.restoreVitals(bot)
          // If lag/wait_state exceeds 50 pulses (5 seconds), clear it so the bot doesn't stay
          // frozen.
          bot.waitState = 0
          bot.actionQueue.clear()
        }

        // If idle, pick a random action
        if (bot.actionQueue.isEmpty() && bot.waitState == 0 && bot.casting == null) {
          pickBotAction(world, bot, rng, pulseIndex)
        }
      }

      // 2. Pulse step
      try {
        world.runPulse()
      } catch (e: InvariantViolation) {
        val d =
          Diagnostic(
            world.pulse,
            "invariant",
            e.message ?: "World invariant violation",
            cause = e,
            seed = seed,
          )
        return Outcome(
          ok = false,
          pulsesRun = pulseIndex,
          bots = bots.size,
          zones = changedZones,
          failureMessage = "Invariant violation at pulse ${world.pulse}: ${e.message}",
          failedPulse = world.pulse,
          diagnostic = d,
          world = world,
          botStartRooms = botStartRooms,
        )
      }

      // 3. Assertions
      val fatal = world.diagnostics.firstOrNull { it.kind in FORBIDDEN_KINDS }
      if (fatal != null) {
        return Outcome(
          ok = false,
          pulsesRun = pulseIndex,
          bots = bots.size,
          zones = changedZones,
          failureMessage = "[${fatal.kind}] at pulse ${fatal.pulse}: ${fatal.message}",
          failedPulse = fatal.pulse,
          diagnostic = fatal,
          world = world,
          botStartRooms = botStartRooms,
        )
      }

      if (pulseIndex % 1000 == 0) onProgress?.invoke(pulseIndex)
    }

    return Outcome(
      ok = true,
      pulsesRun = pulses,
      bots = bots.size,
      zones = changedZones,
      world = world,
      botStartRooms = botStartRooms,
    )
  }

  private fun pickBotAction(world: World, bot: Player, rng: Random, pulse: Int) {
    when (rng.nextInt(5)) {
      // Category 0: an exit
      0 -> {
        val exits = bot.room.exits.values.toList()
        if (exits.isNotEmpty()) {
          val exit = exits[rng.nextInt(exits.size)]
          world.submit(bot, exit.direction.name)
        } else {
          world.submit(bot, "${world.commands.nameOf("say")} dead end here")
        }
      }
      // Category 1: zone or item command with empty arguments
      1 -> {
        val zoneCmds = bot.room.proto.commands.keys.toList()
        val itemCmds = (bot.inventory + bot.room.items).flatMap { it.proto.commands.keys }
        val cmds = zoneCmds + itemCmds
        if (cmds.isNotEmpty()) {
          val cmd = cmds[rng.nextInt(cmds.size)]
          world.submit(bot, cmd)
        } else {
          world.submit(bot, "${world.commands.nameOf("say")} nothing to use here")
        }
      }
      // Category 2: get, drop, wear or use
      2 -> {
        when (rng.nextInt(4)) {
          0 -> { // get
            val ground = bot.room.items
            if (ground.isNotEmpty()) {
              val item = ground[rng.nextInt(ground.size)]
              val kw = item.keywords.firstOrNull() ?: item.proto.name
              world.submit(bot, "${world.commands.nameOf("get")} $kw")
            }
          }
          1 -> { // drop
            val inv = bot.inventory
            if (inv.isNotEmpty()) {
              val item = inv[rng.nextInt(inv.size)]
              val kw = item.keywords.firstOrNull() ?: item.proto.name
              world.submit(bot, "${world.commands.nameOf("drop")} $kw")
            }
          }
          2 -> { // wear
            val wearable = bot.inventory.filter { it.proto.wear != null }
            if (wearable.isNotEmpty()) {
              val item = wearable[rng.nextInt(wearable.size)]
              val kw = item.keywords.firstOrNull() ?: item.proto.name
              world.submit(bot, "${world.commands.nameOf("wear")} $kw")
            }
          }
          3 -> { // use
            val items = bot.inventory + bot.room.items
            if (items.isNotEmpty()) {
              val item = items[rng.nextInt(items.size)]
              val kw = item.keywords.firstOrNull() ?: item.proto.name
              world.submit(bot, "${world.commands.nameOf("use")} $kw")
            }
          }
        }
      }
      // Category 3: kill on a random actor
      3 -> {
        val targets = bot.room.actors.filter { it !== bot }
        if (Feature.COMBAT in world.features && targets.isNotEmpty()) {
          val target = targets[rng.nextInt(targets.size)]
          val kw = target.keywords.firstOrNull() ?: target.name
          world.submit(bot, "${world.commands.nameOf("kill")} $kw")
        } else {
          world.submit(bot, "${world.commands.nameOf("say")} looking for a fight")
        }
      }
      // Category 4: say
      4 -> {
        world.submit(bot, "${world.commands.nameOf("say")} exploring at pulse $pulse")
      }
    }
  }

  private sealed interface BotSpawn {
    data class Ok(val bot: Player) : BotSpawn

    data class Failed(val message: String, val cause: Throwable) : BotSpawn
  }

  /**
   * Spawns a bot the way a player is created: with a creation provider, through an attempt keyed by
   * the bot's name, its custom steps answered only with `done`, and `prepare`. A respawned bot
   * reuses its attempt with its current choices, so it gets the same numbers again.
   */
  private fun spawnBot(
    world: World,
    name: String,
    at: gups.core.Label,
    choices: Map<String, String>,
    attempts: MutableMap<String, CreationAttempt>,
  ): BotSpawn {
    val attempt =
      world.creation?.let {
        attempts
          .getOrPut(name) {
            CreationAttempt(name, CreationAttempt.nameKey(name), 0, world.config.seed)
          }
          .apply {
            start = at
            answers.clear()
            answers.putAll(choices)
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
    val bot = world.spawnPlayer(name, at, level = 1, choices = choices, attempt = attempt)
    attempt?.prepareError?.let {
      return BotSpawn.Failed("Creation prepare failed: ${it.message}", it)
    }
    return BotSpawn.Ok(bot)
  }

  private fun creationFailure(
    world: World,
    failed: BotSpawn.Failed,
    pulsesRun: Int,
    bots: Int,
    zones: List<String>,
    botStartRooms: Map<gups.core.EntityId, gups.core.Label>,
    seed: Long,
  ): Outcome =
    Outcome(
      ok = false,
      pulsesRun = pulsesRun,
      bots = bots,
      zones = zones,
      failureMessage = failed.message,
      failedPulse = world.pulse,
      diagnostic =
        Diagnostic(world.pulse, "plugin", failed.message, cause = failed.cause, seed = seed),
      world = world,
      botStartRooms = botStartRooms,
    )

  private fun drawBotChoices(world: World, rng: Random): Map<String, String> {
    val creation = world.creation ?: return emptyMap()
    for (attempt in 1..10) {
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
      val refusal =
        try {
          creation.validate(world, answers)
        } catch (e: Exception) {
          world.diagnose("plugin", "creation provider validate() failed: ${e.message}", e)
          "error"
        }
      if (refusal == null) return answers
    }
    return emptyMap()
  }
}

/** Why `MODULE.gups` can't be used, if it can't: it is required, and its schema must be sound. */
internal fun moduleProblems(dir: WorldDir): List<String> {
  val (module, error) = WorldModule.read(dir)
  return if (error != null) listOf(error.render()) else module.problems()
}
