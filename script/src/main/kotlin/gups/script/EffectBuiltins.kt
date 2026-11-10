package gups.script

import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.Effect
import gups.core.model.WorldSchema
import gups.core.world.Features
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.Tuple

/** The effect primitives: `damage(...)`, `apply_affect(...)` and so on. Work anywhere. */
internal class EffectBuiltins(
  private val schema: WorldSchema = WorldSchema.EMPTY,
  private val features: Features = Features.ALL,
) {
  // ---- Effect primitives ------------------------------------------------------------------------

  @StarlarkMethod(
    name = "damage",
    doc = "Deal damage.",
    parameters =
      [
        Param(name = "dice", named = true, doc = "Damage dice such as '2d6+1'."),
        Param(
          name = "type",
          defaultValue = "None",
          named = true,
          doc = "A declared damage type, such as DAMAGE.fire; the unarmed type if omitted.",
        ),
      ],
  )
  fun damage(dice: Any, type: Any): Any =
    effect(
      "damage",
      Effect.Damage(
        dice(dice, "damage(dice)"),
        DamageType(
          if (isNone(type)) schema.unarmedDamageType
          else
            checkName(
              str(type, "damage(type)").lowercase(),
              schema.damageTypes,
              "damage type",
              "damage(type)",
            )
        ),
      ),
    )

  @StarlarkMethod(
    name = "heal",
    doc = "Restore a vital, up to its maximum.",
    parameters =
      [
        Param(name = "dice", named = true, doc = "Amount restored, as dice such as '2d4'."),
        Param(name = "vital", named = true, doc = "The declared vital to restore."),
      ],
  )
  fun heal(dice: Any, vital: Any): Any =
    effect(
      "heal",
      Effect.Heal(
        dice(dice, "heal(dice)"),
        checkName(str(vital, "heal(vital)"), schema.vitalNames, "vital", "heal(vital)"),
      ),
    )

  @StarlarkMethod(
    name = "apply_affect",
    doc = "Attach a timed buff or debuff.",
    parameters =
      [
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
  ): Any {
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
        AffectSite("apply_affect", schema, features),
      )
    val scope =
      if (isNone(applies)) null
      else {
        val s =
          choice(
            str(applies, "apply_affect(applies)"),
            "apply_affect(applies)",
            listOf("worn", "carried"),
          )
        AffectScope.valueOf(s.uppercase())
      }
    return effect("apply_affect", e.copy(applies = scope))
  }

  @StarlarkMethod(
    name = "dispel",
    doc =
      "Remove the target's affects that match every given criterion. Affects flagged " +
        "'intrinsic' are never removed.",
    parameters =
      [
        Param(
          name = "name",
          named = true,
          defaultValue = "None",
          doc = "Affect name to remove; a trailing * matches every name with that prefix.",
        ),
        Param(
          name = "flag",
          named = true,
          defaultValue = "None",
          doc = "A flag, or a list of flags; matches affects carrying any of them.",
        ),
        Param(
          name = "all",
          named = true,
          defaultValue = "False",
          doc = "True to match every affect.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc = "Most affects to remove, oldest first, or None to remove every match.",
        ),
      ],
  )
  fun dispel(name: Any, flag: Any, all: Any, count: Any): Any {
    val n = strOrNull(name, "dispel(name)")
    val flags = if (flag is String) setOf(flag) else strList(flag, "dispel(flag)").toSet()
    val everything = bool(all, "dispel(all)")
    val narrowed = n != null || flags.isNotEmpty()
    if (!narrowed && !everything) fail("dispel: give name, flag or all = True")
    val most = if (isNone(count)) null else positive(count, "dispel(count)")
    return effect("dispel", Effect.Dispel(n, flags, everything, most))
  }

  @StarlarkMethod(
    name = "knockback",
    doc = "Push the target along the track.",
    parameters =
      [Param(name = "steps", named = true, doc = "Track positions to push the target, at least 1.")],
  )
  fun knockback(steps: Any): Any =
    effect("knockback", Effect.Knockback(positive(steps, "knockback(steps)")))

  @StarlarkMethod(
    name = "pull",
    doc = "Draw the target along the track.",
    parameters =
      [Param(name = "steps", named = true, doc = "Track positions to draw the target, at least 1.")],
  )
  fun pull(steps: Any): Any = effect("pull", Effect.Pull(positive(steps, "pull(steps)")))

  @StarlarkMethod(
    name = "stun",
    doc = "Stun the target.",
    parameters =
      [Param(name = "pulses", named = true, doc = "Pulses the target is stunned, at least 1.")],
  )
  fun stun(pulses: Any): Any = effect("stun", Effect.Stun(positive(pulses, "stun(pulses)")))

  @StarlarkMethod(
    name = "summon",
    doc = "Spawn mobs beside the caster.",
    parameters =
      [
        Param(name = "label", named = true, doc = "Mob prototype label to spawn."),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number of mobs to spawn, at least 1.",
        ),
        Param(
          name = "duration",
          named = true,
          defaultValue = "None",
          doc = "Pulses before the summoned mobs vanish, or None to keep them.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun summon(label: Any, count: Any, duration: Any, thread: StarlarkThread): Any {
    val pkg = thread.getThreadLocal(LoadCtx::class.java)?.pkg?.path ?: callerFile(thread)?.pkg
    return effect(
      "summon",
      Effect.Summon(
        label(str(label, "summon(label)"), pkg, "summon(label)"),
        positive(count, "summon(count)"),
        intOrNull(duration, "summon(duration)")?.also {
          if (it < 1) fail("summon(duration): must be at least 1")
        },
      ),
    )
  }

  @StarlarkMethod(
    name = "sequence",
    doc = "Combine several effects.",
    extraPositionals = Param(name = "effects", doc = "Effects applied in order."),
  )
  fun sequence(effects: Tuple): Any =
    effect(
      "sequence",
      Effect.Sequence(effects.mapIndexed { i, e -> effectValue(e, "sequence() argument ${i + 1}") }),
    )

  private fun effect(name: String, effect: Effect): EffectDecl {
    requireFeature(features, name, "$name()")
    return EffectDecl(effect)
  }
}
