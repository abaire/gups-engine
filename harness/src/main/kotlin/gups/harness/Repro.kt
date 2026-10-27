package gups.harness

import gups.core.world.Boot
import gups.core.world.Diagnostic
import gups.core.world.InputRecord
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.submit
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldModule
import gups.script.WorldPluginWiring

/** Pulses a replay keeps running after the failure's pulse, to give it time to happen. */
const val REPRO_SLACK_PULSES = 16

/**
 * Replays a diagnostic's repro bundle in a headless world (`gups repro`): today's packages, the
 * bundle's seed, its room as of the last checkpoint, then the recorded inputs at their pulses.
 * Exact in the harness, best effort from production.
 */
class ReproRunner(
  private val dir: WorldDir,
  private val plugins: WorldPluginWiring = WorldPluginWiring.NONE,
) {
  data class Outcome(
    val reproduced: Boolean,
    val matching: List<Diagnostic>,
    val diagnostics: List<Diagnostic>,
    val notes: List<String>,
  )

  fun run(
    seed: Long,
    startPulse: Long,
    firstFreeId: Long,
    setup: (World) -> List<String>,
    inputs: List<InputRecord>,
    untilPulse: Long,
    matches: (Diagnostic) -> Boolean,
  ): Outcome {
    val notes = mutableListOf<String>()
    val loader = ScriptLoader(dir, ScriptLimits.HARNESS, pluginGlobals = plugins.globals)
    val result = loader.load(dir.packages())
    result.errors.forEach { notes += "load error: ${it.render()}" }
    val host = StarlarkHost(result, ScriptLimits.HARNESS, loader)
    val world =
      World(
        result.prototypes(),
        loader.schema,
        host.rules(),
        host,
        WorldConfig(
          seed = seed,
          checkInvariants = true,
          aliases = WorldModule.read(dir).first.aliases,
        ),
        Boot(pulse = startPulse, nextEntityId = firstFreeId, fillSpawns = false),
      )
    plugins.install(world)
    host.install(world)
    plugins.started(world)
    notes += setup(world)
    for (input in inputs.sortedBy { it.pulse }) {
      while (world.pulse < input.pulse) world.runPulse()
      val actor = world.actor(input.actor)
      if (actor == null)
        notes +=
          "pulse ${input.pulse}: ${input.actorName} isn't in the replay; skipped '${input.line}'"
      else world.submit(actor, input.line)
    }
    while (world.pulse < untilPulse + REPRO_SLACK_PULSES) world.runPulse()
    val matching = world.diagnostics.filter(matches)
    return Outcome(matching.isNotEmpty(), matching, world.diagnostics.toList(), notes)
  }
}
