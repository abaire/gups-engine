package gups.script

import gups.core.Label
import gups.core.model.Player
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillVerbsTest {
  private fun fixture(vararg files: Pair<String, String>): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "zones/z/skills.star" to "skill(\"zap\", kind = \"spell\")\nskill(\"mend\")",
      ) + files
    )

  @Test
  fun `set_skill sets a new skill and raises a known one`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _set(ctx):
              res = ctx.set_skill(ctx.actor, ":zap", 30)
              ctx.send(ctx.actor, "new: %s" % (res,))
              raised = ctx.set_skill(ctx.actor, ":zap", 45)
              ctx.send(ctx.actor, "raise: %s" % (raised,))

          room("start", commands = {"set": _set})
          """
      )
    val p = f.player()
    f.input(p, "set")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "new: None")
    assertContains(out, "raise: None")
    assertEquals(45, p.skills[Label("//zones/z:zap")])
  }

  @Test
  fun `a skill view is a valid skill argument`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _view(ctx):
              info = ctx.skill_info(":zap")
              ctx.send(ctx.actor, "label: %s" % (info.label,))
              res = ctx.set_skill(ctx.actor, info, 10)
              ctx.send(ctx.actor, "res: %s" % (res,))

          room("start", commands = {"view": _view})
          """
      )
    val p = f.player()
    f.input(p, "view")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "label: //zones/z:zap")
    assertContains(out, "res: None")
    assertEquals(10, p.skills[Label("//zones/z:zap")])
  }

  @Test
  fun `a learn refusal is returned and changes nothing, for a new and a known skill`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _new(ctx):
              res = ctx.set_skill(ctx.actor, ":zap", 30)
              ctx.send(ctx.actor, "new: %s" % (res,))

          def _known(ctx):
              res = ctx.set_skill(ctx.actor, ":zap", 90)
              ctx.send(ctx.actor, "raise: %s, at %d" % (res, ctx.actor.skills["//zones/z:zap"]))

          room("start", commands = {"new": _new, "known": _known})
          """
      )
    val p = f.player()

    // A fresh skill: refused, and the actor knows nothing.
    f.w.restrictions =
      mapOf(RestrictionPoint.LEARN to listOf(Restriction { _, req -> "No spells here." }))
    f.input(p, "new")
    f.run(1)
    assertContains(f.out(p), "new: No spells here.")
    assertNull(p.skills[Label("//zones/z:zap")])

    // A known skill: the refusal returns, and the proficiency is unchanged.
    f.w.restrictions = mapOf()
    f.input(p, "new")
    f.run(1)
    assertContains(f.out(p), "new: None")
    assertEquals(30, p.skills[Label("//zones/z:zap")])

    f.w.restrictions =
      mapOf(RestrictionPoint.LEARN to listOf(Restriction { _, req -> "No spells here." }))
    f.input(p, "known")
    f.run(1)
    assertContains(f.out(p), "raise: No spells here., at 30")
    assertEquals(30, p.skills[Label("//zones/z:zap")])
  }

  private fun scriptError(f: ScriptFixture, p: Player, command: String, message: String) {
    val before = f.diagnostics("script_error").count { message in it.message }
    f.input(p, command)
    f.run(1)
    assertEquals(before + 1, f.diagnostics("script_error").count { message in it.message }, command)
  }

  @Test
  fun `an unknown skill label, and a proficiency outside 0 to 100, are script errors`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _unknown(ctx):
              ctx.set_skill(ctx.actor, ":nope", 10)

          def _low(ctx):
              ctx.set_skill(ctx.actor, ":zap", -1)

          def _high(ctx):
              ctx.set_skill(ctx.actor, ":zap", 101)

          room("start", commands = {"unknown": _unknown, "low": _low, "high": _high})
          """
      )
    val p = f.player()
    scriptError(f, p, "unknown", "set_skill(skill): unknown skill '//zones/z:nope'")
    scriptError(f, p, "low", "set_skill(proficiency): must be 0 to 100, got -1")
    scriptError(f, p, "high", "set_skill(proficiency): must be 0 to 100, got 101")
    assertNull(p.skills[Label("//zones/z:zap")])
  }

  @Test
  fun `remove_skill returns True, then False`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _forget(ctx):
              ctx.set_skill(ctx.actor, ":zap", 20)
              first = ctx.remove_skill(ctx.actor, ":zap")
              ctx.send(ctx.actor, "first: %s" % (first,))
              second = ctx.remove_skill(ctx.actor, ":zap")
              ctx.send(ctx.actor, "second: %s" % (second,))

          room("start", commands = {"forget": _forget})
          """
      )
    val p = f.player()
    f.input(p, "forget")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "first: True")
    assertContains(out, "second: False")
    assertNull(p.skills[Label("//zones/z:zap")])
  }

  @Test
  fun `skill_info returns the view's name and kind, and None for an unknown label`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _info(ctx):
              zap = ctx.skill_info(":zap")
              ctx.send(ctx.actor, "%s %s" % (zap.name, zap.kind))
              mend = ctx.skill_info(":mend")
              ctx.send(ctx.actor, "%s %s" % (mend.name, mend.kind))
              none = ctx.skill_info(":nope")
              ctx.send(ctx.actor, "none: %s" % (none,))

          room("start", commands = {"info": _info})
          """
      )
    val p = f.player()
    f.input(p, "info")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "zap spell")
    assertContains(out, "mend skill")
    assertContains(out, "none: None")
  }

  @Test
  fun `without skills, each verb gives the feature error`() {
    val files =
      mapOf(
        "MODULE.gups" to TestWorlds.module("features = [\"combat\", \"track\", \"leveling\"]"),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rulesFor(listOf("combat", "track", "leveling")),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          def _set(ctx):
              ctx.set_skill(ctx.actor, ":zap", 30)

          def _forget(ctx):
              ctx.remove_skill(ctx.actor, ":zap")

          def _info(ctx):
              ctx.skill_info(":zap")

          room("start", commands = {"set": _set, "forget": _forget, "info": _info})
          """,
      )
    val f = ScriptFixture(files)
    val p = f.player()
    scriptError(f, p, "set", "ctx.set_skill() needs the skills feature")
    scriptError(f, p, "forget", "ctx.remove_skill() needs the skills feature")
    scriptError(f, p, "info", "ctx.skill_info() needs the skills feature")
  }
}
