package gups.script

import gups.core.model.Condition
import gups.core.world.applyAffect
import gups.core.world.changeVital
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaTest {
  private val pkg = "zones/z/package.star" to zonePkg()

  /** Loads a zone whose rooms.star is [source] under the template schema. */
  private fun load(source: String): LoadResult {
    val root = tempWorld(mapOf(pkg, "zones/z/rooms.star" to source))
    return ScriptLoader(WorldDir(root), ScriptLimits.HARNESS).load(listOf("zones/z"))
  }

  private fun failure(source: String): String {
    val r = load(source)
    assertFalse(r.ok, "expected a load failure")
    assertTrue("zones/z" in r.failed)
    return r.errors.joinToString("\n") { it.render() }
  }

  private fun fixture(source: String) =
    ScriptFixture(mapOf(pkg, "zones/z/rooms.star" to source)).also {
      assertTrue(it.result.ok, it.result.report())
    }

  // ---- Constructors --------------------------------------------------------------------------

  @Test
  fun `a constructor refuses an unknown keyword with a suggestion`() {
    assertContains(
      failure("mob(\"m\", vitals = vitals(hpp = 5))"),
      "vitals(): unknown vital 'hpp' (did you mean 'hp'?)",
    )
    assertContains(
      failure("mob(\"m\", stats = stats(strength = 5))"),
      "stats(): unknown stat 'strength'",
    )
    assertContains(
      failure("mob(\"m\", resists = resists(fier = 5))"),
      "resists(): unknown damage type 'fier' (did you mean 'fire'?)",
    )
  }

  @Test
  fun `a constructor refuses a non-int value`() {
    assertContains(failure("mob(\"m\", vitals = vitals(hp = \"ten\"))"), "vitals(hp)")
    assertContains(failure("mob(\"m\", vitals = vitals(hp = 1.5))"), "vitals(hp)")
  }

  @Test
  fun `a constructor's value reads fields and equals an identical one`() {
    val f =
      fixture(
        """
            def _probe(ctx):
                c = vitals(hp = 3, mana = 4)
                ctx.send(ctx.actor, "%d %d %d %s %s" % (c.hp, c.mana, c.move, c == vitals(mana = 4, hp = 3), c == vitals(hp = 3)))

            room("start", commands = {"probe": _probe})
            """
      )
    val p = f.player()
    f.input(p, "probe")
    f.run()
    assertEquals("3 4 0 True False\n", f.out(p))
  }

  @Test
  fun `a plain dict is refused with the constructor's name`() {
    assertContains(
      failure("mob(\"m\", vitals = {\"hp\": 5})"),
      "mob(vitals): use vitals(...) instead of a dict",
    )
    assertContains(failure("mob(\"m\", stats = {\"str\": 5})"), "use stats(...) instead of a dict")
    assertContains(
      failure("mob(\"m\", resists = {\"fire\": 5})"),
      "use resists(...) instead of a dict",
    )
    assertContains(
      failure("item(\"i\", stat_deltas = {\"str\": 1})"),
      "use stats(...) instead of a dict",
    )
    assertContains(
      failure("skill(\"s\", cost = {\"mana\": 1})"),
      "use vitals(...) instead of a dict",
    )
    assertContains(
      failure("mob(\"m\", behaviors = [flee(below = {\"hp\": 20})])"),
      "use vitals(...) instead of a dict",
    )
    assertContains(
      failure(
        "skill(\"s\", effect = apply_affect(name = \"a\", duration = 5, stat_deltas = {\"dex\": 1}))"
      ),
      "use stats(...) instead of a dict",
    )
  }

  @Test
  fun `a constructor of the wrong kind names the one wanted`() {
    assertContains(
      failure("skill(\"s\", cost = stats(str = 1))"),
      "expected vitals(...), got stats(...)",
    )
  }

  @Test
  fun `DAMAGE holds the declared damage types and a misspelling fails at that line`() {
    assertTrue(load("item(\"i\", damage_type = DAMAGE.fire)").ok)
    val error = failure("item(\"i\", damage_type = DAMAGE.fier)")
    assertContains(error, "rooms.star:1")
    assertContains(error, "struct has no field 'fier' (did you mean 'fire'?)")
  }

  // ---- Checks at package load ----------------------------------------------------------------

  @Test
  fun `names are checked at package load, naming the declaration`() {
    assertContains(
      failure("item(\"i\", stat_deltas = stats(dxe = 1))"),
      "unknown stat 'dxe' (did you mean 'dex'?)",
    )
    assertContains(
      failure("skill(\"s\", cost = vitals(mna = 5))"),
      "unknown vital 'mna' (did you mean 'mana'?)",
    )
    assertContains(
      failure("item(\"i\", damage_type = \"acid\")"),
      "item(damage_type): unknown damage type 'acid'",
    )
    assertContains(
      failure("skill(\"s\", effect = damage(dice = \"1d4\", type = \"acid\"))"),
      "damage(type): unknown damage type 'acid'",
    )
    assertContains(
      failure("skill(\"s\", effect = heal(dice = \"1d4\", vital = \"hit\"))"),
      "heal(vital): unknown vital 'hit'",
    )
    assertContains(failure("skill(\"s\", channels = [\"smell\"])"), "skill(channels)")
    assertContains(
      failure("mob(\"m\", behaviors = [flee(below = vitals(hp = 120))])"),
      "flee(below): hp must be 0..100",
    )
  }

  @Test
  fun `declared channels are accepted and SENSE all lists the built-in and declared ones`() {
    assertTrue(
      load(
          """
          skill("s", channels = ["spell", "sound"])
          """
        )
        .ok
    )
    val f =
      fixture(
        """
            def _all(ctx):
                ctx.send(ctx.actor, ",".join(SENSE.all))

            room("start", commands = {"all": _all})
            """
      )
    val p = f.player()
    f.input(p, "all")
    f.run()
    assertEquals("sight,sound,spell\n", f.out(p))
  }

  // ---- Actor view fields ---------------------------------------------------------------------

  private val viewSource =
    """
    def _probe(ctx):
        a = ctx.actor
        ctx.send(a, "%d %d %d" % (a.hp, a.max_hp, a.dex))
        ctx.send(a, "%d %d" % (a.base.dex, a.resists.fire))
        ctx.send(a, "%s %s" % (a.condition, a.below_zero_since("hp")))
        ctx.send(a, ",".join([str(x) for x in [a.str, a.con, a.int, a.wis, a.armor, a.mana, a.max_mana, a.move, a.max_move]]))

    def _typo(ctx):
        ctx.send(ctx.actor, str(ctx.actor.hpp))

    def _typo_vital(ctx):
        ctx.send(ctx.actor, str(ctx.actor.below_zero_since("hpp")))

    room("start", commands = {"probe": _probe, "typo": _typo, "typovital": _typo_vital})
    """

  @Test
  fun `the actor view has every schema field`() {
    val f = fixture(viewSource)
    val p = f.player(hp = 30)
    f.w.applyAffect(p, "quick", 100, statDeltas = mapOf("dex" to 3))
    f.input(p, "probe")
    f.run()
    assertEquals("30 30 13\n10 0\nok None\n10,10,10,10,0,20,20,100,100\n", f.out(p))
  }

  @Test
  fun `below_zero_since and condition follow the vitals`() {
    val f = fixture(viewSource)
    val p = f.player(hp = 30)
    f.run(3)
    f.w.changeVital(p, "mana", -p.current("mana") - 1)
    assertEquals(Condition.OK, p.condition)
    f.input(p, "probe")
    f.run()
    assertContains(f.out(p), "ok None")
    assertEquals(3L, p.vitals.getValue("mana").belowZeroSince)
  }

  @Test
  fun `a misspelled field is an error that suggests the right one`() {
    val f = fixture(viewSource)
    val p = f.player()
    f.input(p, "typo")
    f.run()
    val message = f.diagnostics("script_error").single().message
    assertContains(message, "has no field or method 'hpp'")
    assertContains(message, "did you mean 'hp'?")
    f.input(p, "typovital")
    f.run()
    assertContains(
      f.diagnostics("script_error").last().message,
      "unknown vital 'hpp' (did you mean 'hp'?)",
    )
  }
}
