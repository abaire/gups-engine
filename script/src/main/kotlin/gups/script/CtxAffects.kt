package gups.script

import gups.core.model.Actor
import gups.core.model.AffectScope
import gups.core.model.Effect
import gups.core.model.Entity
import gups.core.world.HookContext
import gups.core.world.applyAffect
import gups.core.world.applyItemAffect
import gups.core.world.applyRoomAffect
import gups.core.world.removeAffect
import gups.core.world.removeItemAffect
import gups.core.world.removeRoomAffect
import gups.core.world.summon
import gups.core.world.toSpec
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Affects and summoning. */
abstract class CtxAffects
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxGroups(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "apply_affect",
    doc =
      "Attaches a buff or debuff to an actor, an item or a room; re-applying a name replaces it.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor, item or room to receive the affect."),
        Param(
          name = "name",
          named = true,
          doc = "Affect name; applying the same name again replaces the existing affect.",
        ),
        Param(
          name = "duration",
          named = true,
          doc = "Duration in pulses; None makes an item affect permanent.",
        ),
        Param(
          name = "stat_deltas",
          named = true,
          defaultValue = "None",
          doc = "Stat adjustments while the affect lasts, built with stats(...).",
        ),
        Param(
          name = "flags",
          named = true,
          defaultValue = "[]",
          doc = "Flags the affect grants, such as 'no_regen'.",
        ),
        Param(
          name = "tick_every",
          named = true,
          defaultValue = "None",
          doc = "Pulses between on_tick calls, or None for no ticking.",
        ),
        Param(
          name = "on_tick",
          named = true,
          defaultValue = "None",
          doc = "Top-level function fn(ctx) called every tick_every pulses.",
        ),
        Param(
          name = "on_expire",
          named = true,
          defaultValue = "None",
          doc = "Top-level function fn(ctx) called when the affect expires or is removed.",
        ),
        Param(
          name = "expire_msg",
          named = true,
          defaultValue = "''",
          doc = "Text sent to the target when the affect ends.",
        ),
        Param(
          name = "modifiers",
          named = true,
          defaultValue = "{}",
          doc = "Rules modifiers active while the affect lasts, by modifier point name.",
        ),
        Param(
          name = "sense_deltas",
          named = true,
          defaultValue = "{}",
          doc = "Sense score changes by channel while the affect lasts.",
        ),
        Param(
          name = "max_vital_deltas",
          named = true,
          defaultValue = "None",
          doc = "Vital maximum adjustments while the affect lasts, built with vitals(...).",
        ),
        Param(
          name = "resist_deltas",
          named = true,
          defaultValue = "None",
          doc = "Damage resistance adjustments while the affect lasts, built with resists(...).",
        ),
        Param(
          name = "applies",
          named = true,
          defaultValue = "None",
          doc = "Item affects only: 'worn' or 'carried'.",
        ),
        Param(
          name = "procs",
          named = true,
          defaultValue = "[]",
          doc = "Combat procs that fire during auto-attack swings.",
        ),
        Param(
          name = "add_tags",
          named = true,
          defaultValue = "[]",
          doc = "Tags a room gains while the affect lasts; a room affect only.",
        ),
        Param(
          name = "remove_tags",
          named = true,
          defaultValue = "[]",
          doc = "Tags a room loses while the affect lasts; a room affect only.",
        ),
      ],
  )
  fun applyAffect(
    target: Any,
    name: Any,
    duration: Any,
    statDeltas: Any,
    flags: Any,
    tickEvery: Any,
    onTick: Any,
    onExpire: Any,
    expireMsg: Any,
    modifiers: Any,
    senseDeltas: Any,
    maxVitalDeltas: Any,
    resistDeltas: Any,
    applies: Any,
    procs: Any,
    addTags: Any,
    removeTags: Any,
  ): Any =
    verb("apply_affect") {
      val e =
        affectEffect(
          AffectArgs(
            name,
            duration,
            statDeltas,
            flags,
            tickEvery,
            onTick,
            onExpire,
            expireMsg,
            modifiers,
            senseDeltas,
            maxVitalDeltas,
            resistDeltas,
            procs,
            addTags,
            removeTags,
          ),
          AffectSite("apply_affect", world.schema, world.features, "ctx.apply_affect.procs"),
        )
      if (target is RoomView) {
        val room = target.room
        val d = e.duration ?: fail("apply_affect(duration): a room's affect needs a duration")
        if (e.statDeltas.isNotEmpty())
          fail("apply_affect(stat_deltas): a room's affect can't change stats")
        if (e.senseDeltas.isNotEmpty())
          fail("apply_affect(sense_deltas): a room's affect can't change senses")
        if (e.maxVitalDeltas.isNotEmpty())
          fail("apply_affect(max_vital_deltas): a room's affect can't change maximum vitals")
        if (e.resistDeltas.isNotEmpty())
          fail("apply_affect(resist_deltas): a room's affect can't change resists")
        if (e.procs.isNotEmpty()) fail("apply_affect(procs): a room's affect can't have procs")
        if (!isNone(applies)) fail("apply_affect(applies): only item affects take applies")
        val a =
          world.applyRoomAffect(
            room,
            e.name,
            d,
            e.flags,
            e.tickEvery,
            e.onTick,
            e.onExpire,
            e.expireMessage,
            owner as? Actor,
            e.modifiers,
            e.addTags,
            e.removeTags,
          )
        a?.let { AffectView(frame, room, it) } ?: Starlark.NONE
      } else if (target is ItemView) {
        val item = itemArg(target, "apply_affect(target)")
        if (e.addTags.isNotEmpty())
          fail("apply_affect(add_tags): only a room's affect can add tags")
        if (e.removeTags.isNotEmpty())
          fail("apply_affect(remove_tags): only a room's affect can remove tags")
        val scope =
          if (isNone(applies)) AffectScope.WORN
          else {
            val s =
              choice(
                str(applies, "apply_affect(applies)"),
                "apply_affect(applies)",
                listOf("worn", "carried"),
              )
            AffectScope.valueOf(s.uppercase())
          }
        val a = world.applyItemAffect(item, e.toSpec(owner as? Actor), scope)
        a?.let { AffectView(frame, item, it) } ?: Starlark.NONE
      } else {
        val t = actorArg(target, "apply_affect(target)")
        if (e.addTags.isNotEmpty())
          fail("apply_affect(add_tags): only a room's affect can add tags")
        if (e.removeTags.isNotEmpty())
          fail("apply_affect(remove_tags): only a room's affect can remove tags")
        if (!isNone(applies)) fail("apply_affect(applies): only item affects take applies")
        if (e.duration == null) fail("apply_affect(duration): an actor's affect needs a duration")
        val a = world.applyAffect(t, e.toSpec(owner as? Actor))
        a?.let { AffectView(frame, t, it) } ?: Starlark.NONE
      }
    }

  @StarlarkMethod(
    name = "remove_affect",
    doc = "Removes an affect by name; True if there was one.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor, item or room carrying the affect."),
        Param(name = "name", named = true, doc = "Affect name."),
      ],
  )
  fun removeAffect(target: Any, name: Any): Any =
    verb("remove_affect") {
      if (target is RoomView) {
        world.removeRoomAffect(target.room, str(name, "remove_affect(name)"))
      } else if (target is ItemView) {
        world.removeItemAffect(
          itemArg(target, "remove_affect(target)"),
          str(name, "remove_affect(name)"),
        )
      } else {
        world.removeAffect(
          actorArg(target, "remove_affect(target)"),
          str(name, "remove_affect(name)"),
        )
      }
    }

  @StarlarkMethod(
    name = "summon",
    doc =
      "Spawns count mobs of prototype label beside caster as its summons. Returns the mobs as a list.",
    parameters =
      [
        Param(name = "caster", named = true, doc = "Actor the mobs are summoned beside."),
        Param(name = "label", named = true, doc = "Mob prototype label."),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number of mobs to summon; at least 1.",
        ),
        Param(
          name = "duration",
          named = true,
          defaultValue = "None",
          doc = "Pulses until the summoned affect expires and the mob fades away, or None.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun summon(caster: Any, label: Any, count: Any, duration: Any, thread: StarlarkThread): Any =
    verb("summon", "$label") {
      val c = actorArg(caster, "summon(caster)")
      val l = label(str(label, "summon(label)"), pkgOf(thread), "summon(label)")
      if (l !in world.protos.mobs) fail("summon(label): there is no mob $l")
      val n = int(count, "summon(count)")
      if (n < 1) fail("summon(count): must be at least 1")
      val d = if (duration == Starlark.NONE) null else int(duration, "summon(duration)")
      if (d != null && d < 1) fail("summon(duration): must be at least 1")
      val mobs = world.summon(c, Effect.Summon(l, n, d))
      sList(mobs.map { v(it) })
    }
}
