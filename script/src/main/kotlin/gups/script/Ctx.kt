package gups.script

import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Memory
import gups.core.world.HookContext
import gups.core.world.Proposal
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkValue

/** What `ctx.deny(msg)` returns; a `before_*` hook returns it to veto. */
@StarlarkBuiltin(name = "denial", doc = "A veto, from ctx.deny(msg).")
class Denial internal constructor(val message: String) : StarlarkValue {
  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<deny ").repr(message).append(">")
  }
}

/** Per-invocation extras that are not in [HookContext]. */
internal class CtxExtras(
  val proposals: List<Proposal>? = null,
  /** A zone or global command's argument text. */
  val commandArgs: String? = null,
  val commandTarget: Actor? = null,
  val targets: List<Actor>? = null,
  val power: Int? = null,
)

/**
 * The `ctx` handed to one hook invocation. Created fresh per call and invalid once the hook
 * returns. Fields are read-only views; every mutation is a verb the engine validates and applies at
 * once, on the world thread.
 */
@StarlarkBuiltin(name = "ctx", doc = "The context of one hook invocation.")
class Ctx internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxQueries(frame, owner, hc, extras) {
  override fun repr(printer: Printer) {
    printer.append("<ctx ${frame.site.hook} of ${owner.name}>")
  }

  /** One line for diagnostics. */
  internal fun summary(): String = describeCtx(owner, hc)
}

internal fun memoryValue(frame: Frame, m: Memory): StructValue =
  StructValue(
    linkedMapOf(
      "subject" to
        (frame.world.actor(m.key.subject)?.takeIf { a -> a.inWorld }?.let { a -> view(frame, a) }
          ?: Starlark.NONE),
      "event" to m.key.event,
      "skill" to (m.key.skill?.v ?: Starlark.NONE),
      "target" to
        (m.key.target
          ?.let { id -> frame.world.actor(id)?.takeIf { a -> a.inWorld } }
          ?.let { a -> view(frame, a) } ?: Starlark.NONE),
      "first_pulse" to sInt(m.firstPulse),
      "pulse" to sInt(m.pulse),
      "count" to sInt(m.count),
      "amount" to sInt(m.amount),
      "channel" to (m.channel?.name ?: Starlark.NONE),
    )
  )

internal fun describeCtx(owner: Entity, hc: HookContext): String =
  buildList {
      add("self=$owner")
      hc.actor?.let { add("actor=$it") }
      hc.target?.let { add("target=$it") }
      hc.item?.let { add("item=$it") }
      hc.room?.let { add("room=${it.label}") }
      hc.direction?.let { add("direction=$it") }
      hc.text?.let { add("text=${it.take(80)}") }
      hc.skill?.let { add("skill=${it.label}") }
      hc.amount?.let { add("amount=$it") }
    }
    .joinToString(" ")
