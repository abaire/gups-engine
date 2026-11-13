package gups.script

import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `ctx.wear` and `ctx.remove_worn`. */
class WearVerbScriptTest {
  private val ZONE =
    """
    def _first(ctx, keyword):
        for i in ctx.actor.inventory():
            if keyword in i.keywords:
                return i
        return ctx.actor.equipped("neck")

    def _put_on(ctx):
        words = ctx.args.split(" ")
        r = ctx.wear(ctx.actor, _first(ctx, words[0]), wield = len(words) > 1)
        ctx.send(ctx.actor, "WEAR:%s" % r)

    def _take_off(ctx):
        ctx.send(ctx.actor, "REMOVE_WORN:%s" % ctx.remove_worn(ctx.actor, _first(ctx, ctx.args)))

    def _gift(ctx):
        ctx.grant_item(ctx.actor, ":" + ctx.args)

    def _floor(ctx):
        ctx.wear(ctx.actor, ctx.spawn(":amulet", ctx.room))

    item("amulet", name = "an amulet", keywords = ["amulet"], wear = "neck")
    item("pebble", name = "a pebble", keywords = ["pebble"])
    room("start", commands = {"puton": _put_on, "takeoff": _take_off, "gift": _gift,
                              "floor": _floor})
    """

  private fun fixture() =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to ZONE,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(""),
      )
    )

  private fun ScriptFixture.say(p: Player, line: String): String {
    input(p, line)
    run(1)
    return out(p)
  }

  @Test
  fun `wear and remove_worn move a carried item on and off silently`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "gift amulet")
    val amulet = p.inventory.single()
    assertEquals("WEAR:None\n", f.say(p, "puton amulet"))
    assertEquals(ItemLocation.Equipped(p.id, "neck"), amulet.location)
    assertEquals("REMOVE_WORN:None\n", f.say(p, "takeoff amulet"))
    assertEquals(ItemLocation.Carried(p.id), amulet.location)
  }

  @Test
  fun `refusals come back as strings`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "gift pebble")
    assertContains(f.say(p, "puton pebble"), "WEAR:You can't wear a pebble.")
    f.say(p, "gift amulet")
    assertContains(f.say(p, "puton amulet wield"), "WEAR:You can't wield an amulet.")
  }

  @Test
  fun `an item the actor doesn't carry or wear is a script error`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "floor")
    assertEquals(1, f.diagnostics("script_error").count { "wear(item)" in it.message })
    f.say(p, "gift amulet")
    f.say(p, "takeoff amulet")
    assertEquals(1, f.diagnostics("script_error").count { "remove_worn(item)" in it.message })
  }
}
