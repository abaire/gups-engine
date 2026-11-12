package gups.script

import gups.core.Label
import gups.core.model.Player
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `ctx.skill_roll` outside a cast and `ctx.set_item_name`. */
class SkillRollItemNameTest {
  private val ZONE =
    """
    def _roll(ctx):
        parts = ctx.args.split(" ")
        r = ctx.skill_roll(ctx.actor, parts[0], adjust = int(parts[1]) if len(parts) > 1 else 0)
        ctx.send(ctx.actor, "ROLL:%d:%s:%s:%d" % (r.chance, r.roll, r.success, r.power))

    def _event(ctx):
        r = ctx.skill_roll(ctx.actor, ":smith", event = ctx.args)
        ctx.send(ctx.actor, "EVENT:%d" % r.chance)

    def _name(ctx):
        bar = ctx.actor.inventory()[0]
        arg = None if ctx.args == "none" else ctx.args.replace("NL", "\n")
        changed = ctx.set_item_name(bar, arg)
        ctx.send(ctx.actor, "NAME:%s:%s" % (changed, bar.name))

    def _find(ctx):
        ctx.send(ctx.actor, "FIND:%s" % ctx.actor.has_item(":bar"))

    def _give(ctx):
        ctx.merge_stack(ctx.give(ctx.actor, ":bar"))

    skill("smith", kind = "craft")
    item("bar", name = "an iron bar", keywords = ["bar", "iron"], stackable = True)
    room("start", commands = {"roll": _roll, "event": _event, "name": _name, "find": _find,
                              "fetch": _give})
    """

  /** The template world's formulas, which test worlds otherwise pin to 100. */
  private val FORMULAS =
    """
    def skill_check(ctx):
        prof = ctx.actor.skills.get(ctx.skill.label, 0)
        return max(5, min(100, prof + 20))

    def skill_power(ctx):
        prof = ctx.actor.skills.get(ctx.skill.label, 0)
        return 50 + prof // 2 + (ctx.actor.level - 1) * 2
    """

  private fun fixture(rules: String = FORMULAS, module: String? = null) =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to ZONE,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(rules),
      ) + listOfNotNull(module?.let { "MODULE.gups" to TestWorlds.module(it) })
    )

  private fun ScriptFixture.say(p: Player, line: String): String {
    input(p, line)
    run(1)
    return out(p)
  }

  private fun ScriptFixture.scriptError(p: Player, line: String, message: String) {
    val before = diagnostics("script_error").count { message in it.message }
    say(p, line)
    assertEquals(
      before + 1,
      diagnostics("script_error").count { message in it.message },
      "$line: ${diagnostics("script_error").map { it.message }}",
    )
  }

  @Test
  fun `skill_roll reports chance, roll, success and power`() {
    // prof 50 at level 1: chance 70, power 75.
    val f = fixture()
    val p = f.player()
    p.skills[Label("//zones/z:smith")] = 50
    f.w.rng.force(ints = listOf(70))
    assertContains(f.say(p, "roll :smith"), "ROLL:70:70:True:75")
    f.w.rng.force(ints = listOf(71))
    assertContains(f.say(p, "roll :smith"), "ROLL:70:71:False:0")
    assertContains(f.say(p, "roll :smith 30"), "ROLL:100:None:True:75")
  }

  @Test
  fun `the actor need not know the skill`() {
    val f = fixture()
    val p = f.player()
    f.w.rng.force(ints = listOf(20))
    // prof 0: chance 20, power 50.
    assertContains(f.say(p, "roll :smith"), "ROLL:20:20:True:50")
  }

  @Test
  fun `a rules skill_check sees ctx event`() {
    val f =
      fixture(
        """
        def skill_check(ctx):
            return 40 if ctx.event == "forge" else 10
        """
      )
    val p = f.player()
    assertContains(f.say(p, "event forge"), "EVENT:40")
    assertContains(f.say(p, "event craft"), "EVENT:10")
  }

  @Test
  fun `bad skill_roll arguments are script errors`() {
    val f = fixture()
    val p = f.player()
    f.scriptError(p, "roll :nope", "skill_roll(skill): unknown skill")
    f.scriptError(p, "event Bad", "skill_roll(event)")
  }

  @Test
  fun `skill_roll needs the skills feature`() {
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [\"combat\", \"track\", \"leveling\"]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(listOf("combat", "track", "leveling")),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def _roll(ctx):
                ctx.skill_roll(ctx.actor, ":smith")

            room("start", commands = {"roll": _roll})
            """,
        )
      )
    f.scriptError(f.player(), "roll", "ctx.skill_roll() needs the skills feature")
  }

  @Test
  fun `set_item_name sets and clears a name, and keywords still match`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "fetch")
    assertContains(f.say(p, "name a gleaming bar"), "NAME:True:a gleaming bar")
    assertContains(f.say(p, "name a gleaming bar"), "NAME:False:a gleaming bar")
    assertContains(f.say(p, "find"), "FIND:True")
    f.input(p, "drop iron")
    f.run(2)
    assertContains(f.out(p), "a gleaming bar")
    f.input(p, "get bar")
    f.run(2)
    f.out(p)
    assertContains(f.say(p, "name none"), "NAME:True:an iron bar")
  }

  @Test
  fun `set_item_name rejects a multi-line or overlong name`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "fetch")
    f.scriptError(p, "name aNLb", "set_item_name(name)")
    f.scriptError(p, "name " + "x".repeat(81), "set_item_name(name)")
  }

  @Test
  fun `a renamed item no longer stacks with plain ones`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "fetch")
    f.say(p, "fetch")
    assertEquals(
      1,
      p.inventory.count { it.protoLabel == Label("//zones/z:bar") },
      "plain bars stack",
    )
    f.say(p, "name a fine bar")
    f.say(p, "fetch")
    assertEquals(2, p.inventory.count { it.protoLabel == Label("//zones/z:bar") })
  }
}
