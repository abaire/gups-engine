package gups.script

import gups.core.model.Effect
import gups.core.model.TargetKind
import net.starlark.java.eval.StarlarkThread

/**
 * Wording that differs by where an effect is declared: [what] prefixes each message, [subject]
 * names the effect in the item-target message, and [appliesOnly] and [noneOnly] end the messages
 * refusing `apply_affect(applies)` and a `None` duration on an actor target.
 */
internal class EffectSite(
  val what: String,
  val subject: String,
  val appliesOnly: String,
  val noneOnly: String,
)

/** Load-time checks that an effect's primitives fit the place the effect is declared. */
internal object EffectChecks {
  /** Fails the load if [effect]'s primitives don't fit [target]. */
  fun checkTarget(effect: Effect, target: TargetKind, site: EffectSite) {
    val leaves = flattenLeaves(effect)
    when (target) {
      TargetKind.ROOM -> leaves.forEach { checkRoomLeaf(it, site.what) }
      TargetKind.ITEM -> leaves.forEach { checkItemLeaf(it, site) }
      else -> leaves.filterIsInstance<Effect.ApplyAffect>().forEach { checkActorAffect(it, site) }
    }
  }

  /** Fails the load if [effect] can't be a proc's effect, and records the labels it refers to. */
  fun checkProc(effect: Effect, what: String, ctx: LoadCtx?, thread: StarlarkThread) {
    flattenLeaves(effect).forEach { checkProcLeaf(it, what) }
    if (ctx != null) refs(effect, ctx, thread)
  }

  /** Records the labels [effect] refers to, so they are checked once the world is assembled. */
  fun refs(effect: Effect, ctx: LoadCtx, thread: StarlarkThread) {
    when (effect) {
      is Effect.Summon -> ctx.ref(effect.label, RefKind.MOB, thread)
      is Effect.Sequence -> effect.effects.forEach { refs(it, ctx, thread) }
      else -> Unit
    }
  }

  private fun checkProcLeaf(leaf: Effect, what: String) {
    if (leaf is Effect.Summon) fail("$what: summon can't be used in a proc")
    if (leaf !is Effect.ApplyAffect) return
    if (leaf.duration == null || leaf.applies != null) {
      fail("$what: apply_affect in a proc needs a duration and no applies")
    }
    checkNoRoomTags(leaf, what)
  }

  private fun checkRoomLeaf(leaf: Effect, what: String) {
    when (leaf) {
      is Effect.ApplyAffect -> checkRoomAffect(leaf, what)
      is Effect.Dispel,
      is Effect.Sequence,
      is Effect.Plugin -> Unit
      else ->
        fail(
          "$what: ${primitiveName(leaf)} acts on actors; a room skill may use apply_affect, dispel, sequence and plugin primitives"
        )
    }
  }

  private fun checkRoomAffect(leaf: Effect.ApplyAffect, what: String) {
    val refusals =
      listOf(
        leaf.statDeltas.isNotEmpty() to
          "apply_affect(stat_deltas): a room's affect can't change stats",
        leaf.senseDeltas.isNotEmpty() to
          "apply_affect(sense_deltas): a room's affect can't change senses",
        leaf.maxVitalDeltas.isNotEmpty() to
          "apply_affect(max_vital_deltas): a room's affect can't change maximum vitals",
        leaf.resistDeltas.isNotEmpty() to
          "apply_affect(resist_deltas): a room's affect can't change resists",
        leaf.procs.isNotEmpty() to "apply_affect(procs): a room's affect can't have procs",
        (leaf.applies != null) to "apply_affect(applies): only item affects take applies",
        (leaf.duration == null) to "apply_affect(duration): a room's affect needs a duration",
      )
    refusals.firstOrNull { it.first }?.let { fail("$what: ${it.second}") }
  }

  private fun checkItemLeaf(leaf: Effect, site: EffectSite) {
    when (leaf) {
      is Effect.ApplyAffect -> {
        if ("stunned" in leaf.flags) fail("${site.what}: item affects can't stun")
        checkNoRoomTags(leaf, site.what)
      }
      is Effect.Plugin -> Unit
      else ->
        fail(
          "${site.what}: ${primitiveName(leaf)} acts on actors; ${site.subject} may use apply_affect and plugin primitives"
        )
    }
  }

  private fun checkActorAffect(leaf: Effect.ApplyAffect, site: EffectSite) {
    if (leaf.applies != null)
      fail("${site.what}: apply_affect(applies) is only ${site.appliesOnly}")
    if (leaf.duration == null) {
      fail("${site.what}: apply_affect(duration) can be None only ${site.noneOnly}")
    }
    checkNoRoomTags(leaf, site.what)
  }

  private fun checkNoRoomTags(leaf: Effect.ApplyAffect, what: String) {
    if (leaf.addTags.isNotEmpty()) fail("$what: apply_affect(add_tags) is only for room skills")
    if (leaf.removeTags.isNotEmpty()) {
      fail("$what: apply_affect(remove_tags) is only for room skills")
    }
  }

  private fun primitiveName(e: Effect): String =
    when (e) {
      is Effect.Damage -> "damage"
      is Effect.Heal -> "heal"
      is Effect.ApplyAffect -> "apply_affect"
      is Effect.Dispel -> "dispel"
      is Effect.Knockback -> "knockback"
      is Effect.Pull -> "pull"
      is Effect.Stun -> "stun"
      is Effect.Summon -> "summon"
      is Effect.Sequence -> "sequence"
      is Effect.Plugin -> "${e.pluginId}.${e.name}"
    }

  private fun flattenLeaves(e: Effect): List<Effect> =
    when (e) {
      is Effect.Sequence -> e.effects.flatMap(::flattenLeaves)
      else -> listOf(e)
    }
}
