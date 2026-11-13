package gups.script

import gups.core.Label
import gups.core.model.Entity
import gups.core.model.SkillProto
import gups.core.rules.RulesContext
import gups.core.world.HookContext
import gups.core.world.SkillRoll
import gups.core.world.mayLearn
import gups.core.world.removeSkill
import gups.core.world.reportSkillUse
import gups.core.world.setSkill
import gups.core.world.skillPowerChain
import gups.core.world.skillRoll
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** What a skill roll's `event` must match, for scripts and plugins alike. */
val ROLL_EVENT = Regex("[a-z][a-z0-9_]*")

/** Skill learning, forgetting, rolls and use reports. */
abstract class CtxSkills
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxCombat(frame, owner, hc, extras) {
  /** A skill argument: a skill view's label, or a string resolved against the caller's package. */
  internal fun skillArg(skill: Any?, what: String, thread: StarlarkThread): Label =
    when (skill) {
      is SkillView -> skill.skill.label
      is String -> label(skill, pkgOf(thread), "$what(skill)")
      else -> fail("$what(skill): expected a skill label or skill view, got ${typeName(skill)}")
    }

  @StarlarkMethod(
    name = "set_skill",
    doc =
      "Gives actor the skill, a label or skill view, at proficiency (0 to 100), or changes the" +
        " proficiency it has. Runs the learn restrictions and returns the refusal message, or None" +
        " once the skill is set. Needs the skills feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who learns or raises the skill."),
        Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
        Param(name = "proficiency", named = true, doc = "Proficiency, 0 to 100."),
      ],
    useStarlarkThread = true,
  )
  fun setSkill(actor: Any, skill: Any, proficiency: Any, thread: StarlarkThread): Any =
    verb("set_skill") {
      val a = actorArg(actor, "set_skill(actor)")
      val l = skillArg(skill, "set_skill", thread)
      if (l !in world.protos.skills) fail("set_skill(skill): unknown skill '${l.v}'")
      val n = int(proficiency, "set_skill(proficiency)")
      if (n !in 0..100) fail("set_skill(proficiency): must be 0 to 100, got $n")
      world.setSkill(a, l, n) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "may_learn",
    doc =
      "Asks whether the actor may have the skill, a label or skill view, at proficiency, as" +
        " set_skill would: the learn restrictions. Changes nothing. Returns the refusal message," +
        " or None. Needs the skills feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who would learn or raise the skill."),
        Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
        Param(name = "proficiency", named = true, doc = "Proficiency, 0 to 100."),
      ],
    useStarlarkThread = true,
  )
  fun mayLearn(actor: Any, skill: Any, proficiency: Any, thread: StarlarkThread): Any =
    verb("may_learn") {
      val a = actorArg(actor, "may_learn(actor)")
      val l = skillArg(skill, "may_learn", thread)
      if (l !in world.protos.skills) fail("may_learn(skill): unknown skill '${l.v}'")
      val n = int(proficiency, "may_learn(proficiency)")
      if (n !in 0..100) fail("may_learn(proficiency): must be 0 to 100, got $n")
      world.mayLearn(a, l, n) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "remove_skill",
    doc = "Forgets the skill, a label or skill view, and returns whether the actor knew it.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who forgets the skill."),
        Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
      ],
    useStarlarkThread = true,
  )
  fun removeSkill(actor: Any, skill: Any, thread: StarlarkThread): Any =
    verb("remove_skill") {
      val a = actorArg(actor, "remove_skill(actor)")
      val l = skillArg(skill, "remove_skill", thread)
      if (l !in world.protos.skills) fail("remove_skill(skill): unknown skill '${l.v}'")
      world.removeSkill(a, l)
    }

  @StarlarkMethod(
    name = "skill_info",
    doc = "The skill a label names, as ctx.skill in a cast, or None when no loaded skill has it.",
    parameters = [Param(name = "label", named = true, doc = "Skill label, or the skill's view.")],
    useStarlarkThread = true,
  )
  fun skillInfo(label: Any, thread: StarlarkThread): Any =
    verb("skill_info") {
      val l = skillArg(label, "skill_info", thread)
      world.protos.skills[l]?.let { SkillView(it, world.schema) } ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "skill_roll",
    doc =
      "Rolls actor's skill outside a cast: rules.skill_check and its chain (with ctx.event), plus" +
        " adjust, then one draw unless the chance is 100 or more; on success, the power from" +
        " rules.skill_power and its chain. Returns struct(chance, roll, success, power), roll None" +
        " when nothing was drawn and power 0 on a failure. No cost and no on_skill_use; the actor" +
        " need not know the skill. Needs the skills feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who uses the skill."),
        Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
        Param(name = "item", named = true, defaultValue = "None", doc = "Item involved, or None."),
        Param(name = "target", named = true, defaultValue = "None", doc = "Target actor, or None."),
        Param(
          name = "event",
          named = true,
          defaultValue = "'craft'",
          doc = "What the roll is for, a lowercase word: ctx.event in skill_check.",
        ),
        Param(name = "adjust", named = true, defaultValue = "0", doc = "Added to the chance."),
      ],
    useStarlarkThread = true,
  )
  @Suppress("LongParameterList")
  fun skillRoll(
    actor: Any,
    skill: Any,
    item: Any,
    target: Any,
    event: Any,
    adjust: Any,
    thread: StarlarkThread,
  ): Any =
    verb("skill_roll") {
      val c =
        RulesContext(
          world,
          actorArg(actor, "skill_roll(actor)"),
          actorOrNull(target, "skill_roll(target)"),
          skill = knownSkill(skill, "skill_roll", thread),
          item = itemOrNull(item, "skill_roll(item)"),
          event = rollEvent(event),
        )
      rollView(c, world.skillRoll(c, int(adjust, "skill_roll(adjust)")))
    }

  private fun knownSkill(skill: Any, what: String, thread: StarlarkThread): SkillProto {
    val l = skillArg(skill, what, thread)
    return world.protos.skills[l] ?: fail("$what(skill): unknown skill '${l.v}'")
  }

  /** `struct(chance, roll, success, power)`; the power chain runs only for a success. */
  private fun rollView(c: RulesContext, r: SkillRoll): Any =
    StructValue(
      linkedMapOf(
        "chance" to sInt(r.chance),
        "roll" to (r.roll?.let(::sInt) ?: Starlark.NONE),
        "success" to r.success,
        "power" to sInt(if (r.success) world.skillPowerChain(c) else 0),
      )
    )

  private fun rollEvent(event: Any): String {
    val e = str(event, "skill_roll(event)")
    if (!ROLL_EVENT.matches(e)) fail("skill_roll(event): must match [a-z][a-z0-9_]*, got '$e'")
    return e
  }

  @StarlarkMethod(
    name = "report_skill_use",
    doc =
      "Reports a use of a skill to the global on_skill_use hook, with outcome 'report'. " +
        "Needs the skills feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who used the skill."),
        Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
        Param(name = "target", named = true, defaultValue = "None", doc = "Target actor, or None."),
        Param(name = "item", named = true, defaultValue = "None", doc = "Item involved, or None."),
      ],
    useStarlarkThread = true,
  )
  fun reportSkillUse(actor: Any, skill: Any, target: Any, item: Any, thread: StarlarkThread): Any =
    verb("report_skill_use") {
      val a = actorArg(actor, "report_skill_use(actor)")
      val proto = knownSkill(skill, "report_skill_use", thread)
      val t = actorOrNull(target, "report_skill_use(target)")
      world.reportSkillUse(a, proto, t, itemOrNull(item, "report_skill_use(item)"))
      Starlark.NONE
    }
}
