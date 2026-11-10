package gups.script

import gups.core.model.DamageType
import gups.core.model.Effect
import gups.core.model.WorldSchema
import gups.core.world.Features

/** The raw Starlark arguments of `apply_affect`, before validation. */
internal data class AffectArgs(
  val name: Any,
  val duration: Any,
  val statDeltas: Any,
  val flags: Any,
  val tickEvery: Any,
  val onTick: Any,
  val onExpire: Any,
  val expireMsg: Any,
  val modifiers: Any,
  val senseDeltas: Any,
  val maxVitalDeltas: Any,
  val resistDeltas: Any,
  val procs: Any,
  val addTags: Any,
  val removeTags: Any,
)

/** Where an `apply_affect` is called from: the name in its messages and what the world allows. */
internal class AffectSite(
  val fn: String,
  val schema: WorldSchema,
  val features: Features,
  val procsSymbol: String = "$fn.procs",
)

/** Builds an `apply_affect` effect; shared by the primitive and the `ctx.apply_affect` verb. */
internal fun affectEffect(a: AffectArgs, site: AffectSite): Effect.ApplyAffect {
  val fn = site.fn
  val duration = affectDuration(a.duration, fn)
  val every = intOrNull(a.tickEvery, "$fn(tick_every)")
  if (every != null && every < 1) fail("$fn(tick_every): must be at least 1")
  val procs = procList(a.procs, "$fn(procs)")
  if (procs.isNotEmpty()) requireFeature(site.features, site.procsSymbol, "$fn(procs)")
  return Effect.ApplyAffect(
    name = str(a.name, "$fn(name)"),
    duration = duration,
    statDeltas = typed(a.statDeltas, TypedKind.STATS, "$fn(stat_deltas)"),
    flags = strList(a.flags, "$fn(flags)").toSet(),
    tickEvery = every,
    onTick = hookRefOrNull(a.onTick, "$fn(on_tick)"),
    onExpire = hookRefOrNull(a.onExpire, "$fn(on_expire)"),
    expireMessage = str(a.expireMsg, "$fn(expire_msg)"),
    modifiers = modifiers(a.modifiers, "$fn(modifiers)", site.features),
    senseDeltas = senses(a.senseDeltas, "$fn(sense_deltas)", site.schema, allowNegative = true),
    maxVitalDeltas = typed(a.maxVitalDeltas, TypedKind.VITALS, "$fn(max_vital_deltas)"),
    resistDeltas =
      typed(a.resistDeltas, TypedKind.RESISTS, "$fn(resist_deltas)").mapKeys { (k, _) ->
        DamageType(k)
      },
    procs = procs,
    addTags = affectTagList(a.addTags, "$fn(add_tags)"),
    removeTags = affectTagList(a.removeTags, "$fn(remove_tags)"),
  )
}

/** A duration in pulses, or null for None: an affect that lasts until it is removed. */
private fun affectDuration(v: Any, fn: String): Long? {
  if (isNone(v)) return null
  val pulses = long(v, "$fn(duration)")
  if (pulses < 1) fail("$fn(duration): must be at least 1")
  return pulses
}
