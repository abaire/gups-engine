package gups.script

import gups.core.model.Entity
import gups.core.world.BUILTIN_STANDING_EVENTS
import gups.core.world.HookContext
import gups.core.world.STANDING_EVENT_NAME
import gups.core.world.StandingEvent
import gups.core.world.act
import gups.core.world.applyStanding
import gups.core.world.clearStanding
import gups.core.world.disposition
import gups.core.world.factionsOf
import gups.core.world.raiseStandingEvent
import gups.core.world.standing
import gups.core.world.standingHistory
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Dispositions, standing records and standing events. */
abstract class CtxStanding
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxMemory(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "disposition",
    doc =
      "What holder thinks of subject, -100 to 100: the default sum of its parts or the world's disposition export, then plugins.",
    parameters =
      [
        Param(name = "holder", named = true, doc = "The actor holding the opinion."),
        Param(name = "subject", named = true, doc = "The actor the opinion is about."),
      ],
  )
  fun disposition(holder: Any, subject: Any): Any =
    verb("disposition") {
      sInt(
        world.disposition(
          actorArg(holder, "disposition(holder)"),
          actorArg(subject, "disposition(subject)"),
        )
      )
    }

  @StarlarkMethod(
    name = "standing",
    doc =
      "The amount of holder's standing record about subject, 0 when there is none; declared faction opinions are not records.",
    parameters =
      [
        Param(name = "holder", named = true, doc = "Mob view or faction label holding the record."),
        Param(
          name = "subject",
          named = true,
          doc = "Actor view or faction label the record is about.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun standing(holder: Any, subject: Any, thread: StarlarkThread): Any =
    verb("standing") {
      val pkg = pkgOf(thread)
      val h =
        toStandingHolder(holder, pkg, "standing", world.protos) { actorArg(it, "standing(holder)") }
      val sub =
        toStandingSubject(subject, pkg, "standing", world.protos, h) {
          actorArg(it, "standing(subject)")
        }
      sInt(world.standing(h, sub))
    }

  @StarlarkMethod(
    name = "factions_of",
    doc = "The faction labels actor belongs to, as a list of strings.",
    parameters = [Param(name = "actor", named = true, doc = "The actor.")],
  )
  fun factionsOf(actor: Any): Any =
    verb("factions_of") {
      sList(world.factionsOf(actorArg(actor, "factions_of(actor)")).map { it.v })
    }

  @StarlarkMethod(
    name = "standing_history",
    doc = "Queries standing history for holder and subject since a pulse.",
    parameters =
      [
        Param(
          name = "holder",
          named = true,
          doc = "Mob view or faction label holding the opinion.",
        ),
        Param(
          name = "subject",
          named = true,
          doc = "Actor view or faction label the opinion is about.",
        ),
        Param(name = "since", defaultValue = "0", named = true, doc = "Start pulse for the query."),
        Param(
          name = "via",
          defaultValue = "None",
          named = true,
          doc = "Optional mob view filtering the event source.",
        ),
        Param(
          name = "event",
          defaultValue = "None",
          named = true,
          doc = "Optional event name filter.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun standingHistory(
    holder: Any,
    subject: Any,
    since: Any,
    via: Any,
    event: Any,
    thread: StarlarkThread,
  ): Any =
    verb("standing_history") {
      val pkg = pkgOf(thread)
      val holderObj =
        toStandingHolder(holder, pkg, "standing_history", world.protos) {
          actorArg(it, "standing_history(holder)")
        }
      val subjectObj =
        toStandingSubject(subject, pkg, "standing_history", world.protos, holderObj) {
          actorArg(it, "standing_history(subject)")
        }
      val sincePulse = int(since, "standing_history(since)").toLong().coerceAtLeast(0L)
      val viaId =
        if (isNone(via)) {
          null
        } else {
          mobArg(via, "standing_history(via)").id
        }
      val eventName = if (isNone(event)) null else str(event, "standing_history(event)")

      val totals = world.standingHistory(holderObj, subjectObj, sincePulse, viaId, eventName)
      StructValue(linkedMapOf("gained" to sInt(totals.gained), "lost" to sInt(totals.lost)))
    }

  @StarlarkMethod(
    name = "standing_event",
    doc = "Raises a custom standing event on affected by actor.",
    parameters =
      [
        Param(name = "affected", named = true, doc = "The actor affected."),
        Param(name = "actor", named = true, doc = "The actor who acted."),
        Param(name = "event", named = true, doc = "The event name."),
        Param(name = "amount", defaultValue = "0", named = true, doc = "Optional positive amount."),
        Param(name = "item", defaultValue = "None", named = true, doc = "Optional item view."),
      ],
  )
  fun standingEvent(affected: Any, actor: Any, event: Any, amount: Any, item: Any): Any =
    verb("standing_event") {
      val aff = actorArg(affected, "standing_event(affected)")
      val act = actorArg(actor, "standing_event(actor)")
      val eventName = str(event, "standing_event(event)")
      if (eventName in BUILTIN_STANDING_EVENTS) {
        fail("standing_event(): '$eventName' is a built-in event")
      }
      if (!STANDING_EVENT_NAME.matches(eventName)) {
        fail("standing_event(): event name must match [a-z][a-z0-9_]*, got '$eventName'")
      }
      val amt = int(amount, "standing_event(amount)")
      if (amt < 0) {
        fail("standing_event(amount): must be non-negative, got $amt")
      }
      val itm = if (isNone(item)) null else itemArg(item, "standing_event(item)")

      world.raiseStandingEvent(
        StandingEvent(
          event = eventName,
          affected = aff,
          actor = act,
          amount = amt.toLong(),
          item = itm,
        )
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "adjust_standing",
    doc = "Adjusts standing directly for holder and subject by amount.",
    parameters =
      [
        Param(
          name = "holder",
          named = true,
          doc = "Mob view or faction label holding the opinion.",
        ),
        Param(
          name = "subject",
          named = true,
          doc = "Actor view or faction label the opinion is about.",
        ),
        Param(name = "amount", named = true, doc = "Integer amount to adjust."),
      ],
    useStarlarkThread = true,
  )
  fun adjustStanding(holder: Any, subject: Any, amount: Any, thread: StarlarkThread): Any =
    verb("adjust_standing") {
      val pkg = pkgOf(thread)
      val holderObj =
        toStandingHolder(holder, pkg, "adjust_standing", world.protos) {
          actorArg(it, "adjust_standing(holder)")
        }
      val subjectObj =
        toStandingSubject(subject, pkg, "adjust_standing", world.protos, holderObj) {
          actorArg(it, "adjust_standing(subject)")
        }
      val amt = int(amount, "adjust_standing(amount)")
      world.applyStanding(holderObj, subjectObj, amt, event = "adjust", via = null)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "clear_standing",
    doc = "Clears standing records for holder, optionally restricted to subject.",
    parameters =
      [
        Param(
          name = "holder",
          named = true,
          doc = "Mob view or faction label holding the opinion.",
        ),
        Param(
          name = "subject",
          defaultValue = "None",
          named = true,
          doc = "Actor view or faction label, or None to clear all records for holder.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun clearStanding(holder: Any, subject: Any, thread: StarlarkThread): Any =
    verb("clear_standing") {
      val pkg = pkgOf(thread)
      val holderObj =
        toStandingHolder(holder, pkg, "clear_standing", world.protos) {
          actorArg(it, "clear_standing(holder)")
        }
      val subjectObj =
        if (isNone(subject)) null
        else
          toStandingSubject(subject, pkg, "clear_standing", world.protos, holderObj) {
            actorArg(it, "clear_standing(subject)")
          }
      world.clearStanding(holderObj, subjectObj)
      Starlark.NONE
    }
}
