package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SemiPermanentTest {
  @Test
  fun `a SEMI_PERMANENT affect never expires and reports no expiry`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _self(ctx):
                a = ctx.apply_affect(ctx.actor, "forever", SEMI_PERMANENT)
                ctx.send(ctx.actor, str(a.expires_at) + "/" + str(a.remaining))

            def _other(ctx):
                a = ctx.apply_affect(ctx.target, "forever", SEMI_PERMANENT)
                ctx.send(ctx.actor, str(a.expires_at) + "/" + str(a.remaining))

            room("start")
            mob("rat")
            item("wand", commands = {"self": _self, "other": _other})
            skill("eternal", target = "self",
                  effect = apply_affect(name = "forever", duration = SEMI_PERMANENT))
            """,
        )
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    p.skills[Label("//zones/z:eternal")] = 100
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "self")
    f.run()
    assertContains(f.out(p), "None/None")
    assertEquals(Long.MAX_VALUE, p.affects.single { it.name == "forever" }.expiresAt)

    f.input(p, "other rat")
    f.run()
    assertContains(f.out(p), "None/None")
    assertEquals(Long.MAX_VALUE, rat.affects.single { it.name == "forever" }.expiresAt)

    f.run(1000)
    assertEquals(1, p.affects.size, "still on the actor after 1000 pulses")
    assertEquals(1, rat.affects.size, "still on the mob after 1000 pulses")
  }

  @Test
  fun `a cast at power 150 of a SEMI_PERMANENT skill still never ends`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rules("def skill_power(ctx):\n    return 150\n"),
          "zones/z/content.star" to
            """
            room("start")
            skill("eternal", target = "self",
                  effect = apply_affect(name = "forever", duration = SEMI_PERMANENT))
            """,
        )
      )
    val p = f.player()
    p.skills[Label("//zones/z:eternal")] = 100
    f.input(p, "cast eternal")
    f.run()
    assertEquals(Long.MAX_VALUE, p.affects.single { it.name == "forever" }.expiresAt)
  }

  @Test
  fun `SEMI_PERMANENT on an item skill is a normal positive duration`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            room("start")
            item("wand", use_target = "item",
                  use_effect = apply_affect(name = "forever", duration = SEMI_PERMANENT, applies = "worn"))
            item("charm")
            """,
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))
    val charm = f.w.spawnItem(Label("//zones/z:charm"), ItemLocation.Carried(p.id))
    f.input(p, "use wand on charm")
    f.run()
    assertEquals(Long.MAX_VALUE, charm.affects.single { it.name == "forever" }.expiresAt)
  }
}
