package gups.script

import gups.core.Label
import gups.core.model.Entity
import gups.core.world.HookContext
import gups.core.world.removeSkill
import gups.core.world.reportSkillUse
import gups.core.world.setSkill
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Skill learning, forgetting and use reports. */
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
      val l = skillArg(skill, "report_skill_use", thread)
      val proto = world.protos.skills[l] ?: fail("report_skill_use(skill): unknown skill '${l.v}'")
      val t = if (isNone(target)) null else actorArg(target, "report_skill_use(target)")
      val i = if (isNone(item)) null else itemArg(item, "report_skill_use(item)")
      world.reportSkillUse(a, proto, t, i)
      Starlark.NONE
    }
}
