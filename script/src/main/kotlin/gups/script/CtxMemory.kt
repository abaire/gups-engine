package gups.script

import gups.core.model.Entity
import gups.core.world.HookContext
import gups.core.world.STANDING_EVENT_NAME
import gups.core.world.forget
import gups.core.world.memoriesOf
import gups.core.world.remember
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Mob combat memories. */
abstract class CtxMemory
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxTasks(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "memories",
    doc = "Queries a mob's combat memories, newest first.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob whose memories are queried."),
        Param(
          name = "subject",
          defaultValue = "None",
          named = true,
          doc = "Optional actor view filter.",
        ),
        Param(
          name = "event",
          defaultValue = "None",
          named = true,
          doc = "Optional event name filter.",
        ),
        Param(name = "since", defaultValue = "0", named = true, doc = "Minimum pulse."),
      ],
  )
  fun memories(mob: Any, subject: Any, event: Any, since: Any): Any =
    verb("memories") {
      val m = mobArg(mob, "memories(mob)")
      val sub = if (isNone(subject)) null else actorArg(subject, "memories(subject)")
      val ev = if (isNone(event)) null else str(event, "memories(event)")
      val sincePulse = int(since, "memories(since)").toLong().coerceAtLeast(0L)
      sList(world.memoriesOf(m, sub, ev, sincePulse).map { memoryValue(frame, it) })
    }

  @StarlarkMethod(
    name = "remember",
    doc = "Records or merges a combat memory on mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that remembers."),
        Param(name = "subject", named = true, doc = "Actor the memory is about."),
        Param(name = "event", named = true, doc = "Event name matching [a-z][a-z0-9_]*."),
        Param(
          name = "skill",
          defaultValue = "None",
          named = true,
          doc = "Optional skill label or view.",
        ),
        Param(
          name = "amount",
          defaultValue = "None",
          named = true,
          doc = "Optional non-negative amount to add.",
        ),
        Param(name = "target", defaultValue = "None", named = true, doc = "Optional target actor."),
      ],
    useStarlarkThread = true,
  )
  fun remember(
    mob: Any,
    subject: Any,
    event: Any,
    skill: Any,
    amount: Any,
    target: Any,
    thread: StarlarkThread,
  ): Any =
    verb("remember") {
      val m = mobArg(mob, "remember(mob)")
      val sub = actorArg(subject, "remember(subject)")
      val ev = str(event, "remember(event)")
      if (!STANDING_EVENT_NAME.matches(ev)) {
        fail("remember(event): must match [a-z][a-z0-9_]*, got '$ev'")
      }
      val sk =
        if (isNone(skill)) {
          null
        } else {
          FeatureOwnership.refused("ctx.skill", "remember(skill)", world.features)?.let { fail(it) }
          val l = skillArg(skill, "remember", thread)
          if (l !in world.protos.skills) fail("remember(skill): unknown skill '${l.v}'")
          l
        }
      val amt = if (isNone(amount)) 0 else int(amount, "remember(amount)")
      if (amt < 0) fail("remember(amount): must be non-negative, got $amt")
      val tgt = if (isNone(target)) null else actorArg(target, "remember(target)")
      world.remember(m, sub, ev, sk, amt.toLong(), tgt)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "forget",
    doc = "Removes matching combat memories from mob; returns the number removed.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that forgets."),
        Param(
          name = "subject",
          defaultValue = "None",
          named = true,
          doc = "Optional actor filter.",
        ),
        Param(
          name = "event",
          defaultValue = "None",
          named = true,
          doc = "Optional event name filter.",
        ),
      ],
  )
  fun forget(mob: Any, subject: Any, event: Any): Any =
    verb("forget") {
      val m = mobArg(mob, "forget(mob)")
      val subId = if (isNone(subject)) null else actorArg(subject, "forget(subject)").id
      val ev = if (isNone(event)) null else str(event, "forget(event)")
      sInt(world.forget(m, subId, ev))
    }
}
