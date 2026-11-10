package gups.script

import gups.core.Label
import gups.core.model.DamageType
import gups.core.model.DormantRecovery
import gups.core.model.LootRule
import gups.core.model.MobProto
import gups.core.model.WorldSchema
import gups.core.world.Features
import net.starlark.java.eval.StarlarkThread

/** Turns the validated arguments of a `mob(...)` call into a [MobProto]. */
internal class MobDeclParser(
  private val d: ParsedDecl,
  private val ctx: LoadCtx,
  private val thread: StarlarkThread,
  private val schema: WorldSchema,
  private val features: Features,
  private val knownTraits: () -> Set<String>,
) {
  private val pkg = ctx.pkg.path

  fun parse(): MobProto {
    val id = declName("mob", d, ctx)
    val label = Label("//$pkg:$id")
    val name = d.str("name") ?: displayName(id)
    val skills = skills()
    val senses = senses(d["senses"], d.what("senses"), schema, allowNegative = false)
    val memoryPulses = memoryPulses()
    val loot = loot()
    val def = MobProto(label, name, emptyList())
    val proto =
      MobProto(
        label = label,
        name = name,
        keywords = d["keywords"]?.let { d.strList("keywords") } ?: defaultKeywords(name, id),
        desc = d.str("desc") ?: "",
        level = d.int("level") ?: def.level,
        stats = typed(d["stats"], TypedKind.STATS, d.what("stats")),
        vitals = typed(d["vitals"], TypedKind.VITALS, d.what("vitals")),
        resists =
          typed(d["resists"], TypedKind.RESISTS, d.what("resists")).mapKeys { DamageType(it.key) },
        skills = skills,
        senses = senses,
        memoryPulses = memoryPulses,
        think = d["think"]?.let { ScriptThink(hookRef(it, d.what("think")), pkg) },
        thinkEvery = d.int("think_every") ?: def.thinkEvery,
        behaviors = behaviors(),
        dormantRecovery =
          d.choice("dormant_recovery")?.let { DormantRecovery.valueOf(it.uppercase()) }
            ?: def.dormantRecovery,
        leash = d.int("leash"),
        loot = loot,
        traits = parseMobTraits(d, knownTraits()),
        factions = factions(),
        version = version(d),
      )
    validate(proto)
    return proto
  }

  private fun skills(): Map<Label, Int> =
    d.dict("skills").entries.associate { (k, v) ->
      val skill = label(k, pkg, d.what("skills"))
      ctx.ref(skill, RefKind.SKILL, thread)
      val proficiency = int(v, "mob(skills[\"$k\"])")
      if (proficiency !in 0..100) fail("mob(skills[\"$k\"]): proficiency must be 0..100")
      skill to proficiency
    }

  private fun memoryPulses(): Long? =
    d.int("memory_pulses")?.let {
      if (it < 0) fail("mob(memory_pulses): must be at least 0")
      it.toLong()
    }

  private fun loot(): List<LootRule> =
    d.list("loot")
      .map { (it as? LootDecl)?.rule ?: fail("mob(loot): expected loot(...), got ${typeName(it)}") }
      .onEach { ctx.ref(it.label, RefKind.ITEM, thread) }

  private fun behaviors() =
    d.list("behaviors").map {
      (it as? BehaviorDecl)?.behavior
        ?: fail("mob(behaviors): expected a behavior such as wander(...), got ${typeName(it)}")
    }

  private fun factions(): List<Label>? {
    if (d["factions"] == null) return null
    requireFeature(features, "mob.factions", "mob(factions)")
    return d.strList("factions").map {
      label(it, pkg, d.what("factions")).also { f -> ctx.factionRef(f, thread) }
    }
  }

  private fun validate(proto: MobProto) {
    if (proto.thinkEvery < 1) fail("mob(think_every): must be at least 1")
    proto.leash?.let { if (it < 0) fail("mob(leash): must be at least 0") }
    if (proto.keywords.isEmpty()) fail("mob(keywords): a mob needs at least one keyword")
  }
}
