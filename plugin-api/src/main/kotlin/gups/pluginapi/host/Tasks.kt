package gups.pluginapi.host

import gups.core.model.Actor
import gups.core.model.Interrupt
import gups.core.model.ItemProto
import gups.core.rules.RulesContext
import gups.core.world.Diagnostic
import gups.core.world.TaskCallbacks
import gups.core.world.TaskEnd
import gups.core.world.TaskInfo
import gups.core.world.World
import gups.core.world.skillPowerChain
import gups.core.world.skillRoll
import gups.pluginapi.PluginItemInfo
import gups.pluginapi.PluginSkillRoll
import gups.pluginapi.PluginTask
import gups.pluginapi.PluginTaskCall
import gups.pluginapi.PluginTaskEnd
import gups.pluginapi.PluginTaskInterrupt
import gups.pluginapi.PluginTaskStop

/** A task snapshot as plugins see it; [remaining] counts from the world's current pulse. */
internal class TaskFacade(private val info: TaskInfo, private val world: World) : PluginTask {
  override val name: String
    get() = info.name

  override val startedAt: Long
    get() = info.startedAt

  override val endsAt: Long
    get() = info.endsAt

  override val remaining: Long
    get() = (info.endsAt - world.pulse).coerceAtLeast(0)

  override val interrupt: PluginTaskInterrupt
    get() = PluginTaskInterrupt(info.interrupt.stun, info.interrupt.damagePct)
}

internal fun PluginTaskInterrupt.toCore(): Interrupt = Interrupt(damagePct = damagePct, stun = stun)

/**
 * Runs a plugin's task callbacks with a fresh world facade, as other host callbacks do. A throw is
 * a `plugin` diagnostic; the task has already ended either way.
 */
internal class PluginTaskCallbacks(
  private val pluginId: String?,
  private val onDone: (PluginTaskCall) -> Unit,
  private val onStop: ((PluginTaskStop) -> Unit)?,
) : TaskCallbacks {
  override fun done(world: World, actor: Actor, task: TaskInfo) =
    guarded(world, "done") {
      onDone(PluginTaskCall(facade(world), ActorFacade(actor, world), TaskFacade(task, world)))
    }

  override fun stopped(world: World, actor: Actor, task: TaskInfo, reason: TaskEnd) {
    val stop = onStop ?: return
    val end = PluginTaskEnd.valueOf(reason.name)
    guarded(world, "stop") {
      stop(PluginTaskStop(facade(world), ActorFacade(actor, world), TaskFacade(task, world), end))
    }
  }

  private fun facade(world: World) = WorldFacade(world, pluginId, Facades.logOf(world))

  private fun guarded(world: World, what: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      world.record(
        Diagnostic(world.pulse, "plugin", "plugin '$pluginId': task $what failed: $e", e)
      )
    }
  }
}

/** A skill roll for [c] with [adjust]; the power chain runs only for a success. */
internal fun pluginSkillRoll(world: World, c: RulesContext, adjust: Int): PluginSkillRoll {
  val r = world.skillRoll(c, adjust)
  val power = if (r.success) world.skillPowerChain(c) else 0
  return PluginSkillRoll(r.chance, r.roll, r.success, power)
}

internal fun itemInfoOf(p: ItemProto): PluginItemInfo =
  PluginItemInfo(
    p.label.v,
    p.name,
    p.plural,
    p.keywords,
    p.kind,
    p.tags.sorted(),
    p.value,
    p.weight,
    p.stackable,
    p.charges,
  )
