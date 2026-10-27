package gups.harness

import gups.core.CapturingSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Condition
import gups.core.model.Player
import gups.core.world.Diagnostic
import gups.core.world.InvariantViolation
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.attach
import gups.core.world.handle
import gups.core.world.restoreVitals
import gups.core.world.session
import gups.core.world.submit
import gups.script.PackageKind
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldModule
import gups.script.WorldPluginWiring
import java.util.Random

/**
 * Fuzz walker for the course world (gate 4).
 *
 * Loads the world in the harness with bubble off and a fixed seed. Spawns one bot player per
 * changed zone, repeatedly picking random actions (movement, zone/item commands, get/drop/wear/use,
 * attack, say), and asserts no script errors, step limits, wall-clock hits, quarantines or
 * invariant violations.
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
    val loader = ScriptLoader(dir, ScriptLimits.HARNESS, pluginGlobals = plugins.globals)
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
    val world =
      World(
        loadResult.prototypes(),
        loader.schema,
        host.rules(),
        host,
        WorldConfig(
          seed = seed,
          bubble = false,
          checkInvariants = true,
          aliases = WorldModule.read(dir).first.aliases,
        ),
      )
    plugins.install(world)
    host.install(world)
    plugins.started(world)

    val rng = Random(seed)
    val bots = mutableListOf<Player>()
    val botStartRooms = mutableMapOf<gups.core.EntityId, gups.core.Label>()
    var nextSession = 1L

    for (zone in changedZones) {
      val zoneRooms = world.rooms.filter { it.label.pkg == zone }.toList()
      if (zoneRooms.isEmpty()) continue
      val startRoom = zoneRooms[rng.nextInt(zoneRooms.size)]
      val botName = "Bot_${zone.replace('/', '_').replace('-', '_')}"
      val bot = world.spawnPlayer(botName, startRoom.label, level = 1)
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
            val newBot = world.spawnPlayer(bot.name, room.label, level = 1)
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
          world.submit(bot, "say dead end here")
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
          world.submit(bot, "say nothing to use here")
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
              world.submit(bot, "get $kw")
            }
          }
          1 -> { // drop
            val inv = bot.inventory
            if (inv.isNotEmpty()) {
              val item = inv[rng.nextInt(inv.size)]
              val kw = item.keywords.firstOrNull() ?: item.proto.name
              world.submit(bot, "drop $kw")
            }
          }
          2 -> { // wear
            val wearable = bot.inventory.filter { it.proto.wear != null }
            if (wearable.isNotEmpty()) {
              val item = wearable[rng.nextInt(wearable.size)]
              val kw = item.keywords.firstOrNull() ?: item.proto.name
              world.submit(bot, "wear $kw")
            }
          }
          3 -> { // use
            val items = bot.inventory + bot.room.items
            if (items.isNotEmpty()) {
              val item = items[rng.nextInt(items.size)]
              val kw = item.keywords.firstOrNull() ?: item.proto.name
              world.submit(bot, "use $kw")
            }
          }
        }
      }
      // Category 3: kill on a random actor
      3 -> {
        val targets = bot.room.actors.filter { it !== bot }
        if (targets.isNotEmpty()) {
          val target = targets[rng.nextInt(targets.size)]
          val kw = target.keywords.firstOrNull() ?: target.name
          world.submit(bot, "kill $kw")
        } else {
          world.submit(bot, "say looking for a fight")
        }
      }
      // Category 4: say
      4 -> {
        world.submit(bot, "say exploring at pulse $pulse")
      }
    }
  }
}

/** Why `MODULE.gups` can't be used, if it can't: it is required, and its schema must be sound. */
internal fun moduleProblems(dir: WorldDir): List<String> {
  val (module, error) = WorldModule.read(dir)
  return if (error != null) listOf(error.render()) else module.problems()
}
