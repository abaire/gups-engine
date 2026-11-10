package gups.script

import gups.core.Label
import gups.core.model.Interrupt
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.model.WorldSchema
import gups.core.world.Feature
import gups.core.world.Features
import net.starlark.java.eval.StarlarkThread

private val TRACK_RANGES = setOf("melee", "near", "far")

/**
 * Turns the validated arguments of a `skill(...)` call into a [SkillProto], checking its effect.
 */
internal class SkillDeclParser(
  private val d: ParsedDecl,
  private val ctx: LoadCtx,
  private val thread: StarlarkThread,
  private val schema: WorldSchema,
  private val features: Features,
) {
  fun parse(): SkillProto {
    val label = Label("//${ctx.pkg.path}:${declName("skill", d, ctx)}")
    val cost = typed(d["cost"], TypedKind.VITALS, d.what("cost"))
    val interrupt = interrupt()
    checkRange()
    val proto = build(label, cost, interrupt)
    if (proto.warmup < 0 || proto.lag < 0) fail("skill(): warmup and lag can't be negative")
    checkUnrecognizedTargets(proto)
    proto.effect?.let { checkEffect(it, proto.target) }
    return proto
  }

  private fun build(label: Label, cost: Map<String, Int>, interrupt: Interrupt): SkillProto {
    val def = SkillProto(label)
    return SkillProto(
      label = label,
      kind = d.str("kind") ?: def.kind,
      cost = cost,
      warmup = d.int("warmup") ?: 0,
      lag = d.int("lag") ?: 0,
      range = d.choice("range")?.let { SkillRange.valueOf(it.uppercase()) } ?: def.range,
      target = d.choice("target")?.let { TargetKind.valueOf(it.uppercase()) } ?: def.target,
      channels = channels(d["channels"], d.what("channels"), schema),
      interrupt = interrupt,
      attackWhileCasting = d.bool("attack_while_casting") ?: false,
      effect = d["effect"]?.let { effectValue(it, d.what("effect")) },
      resolve = d["resolve"]?.let { ScriptResolve(hookRef(it, d.what("resolve")), ctx.pkg.path) },
      tags = parseTags(d),
      unrecognized = unrecognizedLines(d),
    )
  }

  private fun checkEffect(effect: gups.core.model.Effect, target: TargetKind) {
    EffectChecks.refs(effect, ctx, thread)
    EffectChecks.checkTarget(
      effect,
      target,
      EffectSite("skill(effect)", "an item skill's effect", "for item skills", "on an item skill"),
    )
  }

  private fun interrupt(): Interrupt {
    val raw = d["interrupt"] ?: return Interrupt()
    val m = dict(raw, d.what("interrupt"))
    for (k in m.keys) choice(k, d.what("interrupt"), listOf("damage_pct", "stun"))
    return Interrupt(
      damagePct = m["damage_pct"]?.let { intOrNull(it, "skill(interrupt[\"damage_pct\"])") },
      stun = m["stun"]?.let { bool(it, "skill(interrupt[\"stun\"])") } ?: true,
    )
  }

  private fun checkRange() {
    val range = d.choice("range") ?: return
    if (Feature.TRACK !in features && range in TRACK_RANGES) {
      fail(FeatureOwnership.refusal("skill(range) '$range'", Feature.TRACK))
    }
  }
}
