package gups.script

import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.world.wearItem
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The `ctx.may_*` restriction queries: they answer like the verbs and change nothing. */
class RestrictionQueriesScriptTest {
  private val ZONE =
    """
    def _find(ctx, keyword):
        for i in ctx.actor.inventory():
            if keyword in i.keywords:
                return i
        return ctx.actor.equipped("neck")

    def _keeper(ctx):
        for a in ctx.room.actors():
            if a != ctx.actor:
                return a

    def _say(ctx, name, r):
        ctx.send(ctx.actor, "%s:%s" % (name, r))

    def _may_wear(ctx):
        _say(ctx, "WEAR", ctx.may_wear(ctx.actor, _find(ctx, ctx.args)))

    def _may_wield(ctx):
        _say(ctx, "WIELD", ctx.may_wear(ctx.actor, _find(ctx, ctx.args), wield = True))

    def _may_remove(ctx):
        _say(ctx, "REMOVE", ctx.may_remove(ctx.actor, _find(ctx, ctx.args)))

    def _may_drop(ctx):
        _say(ctx, "DROP", ctx.may_drop(ctx.actor, _find(ctx, ctx.args)))

    def _may_give(ctx):
        _say(ctx, "GIVE", ctx.may_give(ctx.actor, _find(ctx, ctx.args), _keeper(ctx)))

    def _may_give_self(ctx):
        _say(ctx, "GIVE", ctx.may_give(ctx.actor, _find(ctx, ctx.args), ctx.actor))

    def _may_put(ctx):
        _say(ctx, "PUT", ctx.may_put(ctx.actor, _find(ctx, "amulet"), _find(ctx, "bag")))

    def _may_put_pebble(ctx):
        _say(ctx, "PUT", ctx.may_put(ctx.actor, _find(ctx, "pebble"), _find(ctx, "amulet")))

    def _may_enter(ctx):
        _say(ctx, "ENTER", ctx.may_enter(ctx.actor, ctx.args))

    def _may_learn(ctx):
        _say(ctx, "LEARN", ctx.may_learn(ctx.actor, ":zap", int(ctx.args)))

    def _gift(ctx):
        ctx.grant_item(ctx.actor, ":" + ctx.args)

    def _floor(ctx):
        ctx.may_drop(ctx.actor, ctx.spawn(":amulet", ctx.room))

    item("amulet", name = "an amulet", keywords = ["amulet"], wear = "neck")
    item("pebble", name = "a pebble", keywords = ["pebble"])
    item("bag", name = "a bag", keywords = ["bag"], container = container())
    mob("keeper", name = "the keeper", vitals = vitals(hp = 10))
    room("start", spawns = [spawn(":keeper")], commands = {
        "mwear": _may_wear, "mwield": _may_wield, "mremove": _may_remove, "mdrop": _may_drop,
        "mgive": _may_give, "mgiveself": _may_give_self, "mput": _may_put,
        "mputpebble": _may_put_pebble, "menter": _may_enter, "mlearn": _may_learn,
        "gift": _gift, "floor": _floor})
    room("yard", name = "The Yard")
    skill("zap", kind = "spell")
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

  private fun ScriptFixture.refuse(point: RestrictionPoint, message: String) {
    w.restrictions = mapOf(point to listOf(Restriction { _, _ -> message }))
  }

  @Test
  fun `each query answers None when allowed and the restriction's message when refused`() {
    val f = fixture()
    val p = f.player()
    for (item in listOf("amulet", "pebble", "bag")) f.say(p, "gift $item")
    val allowed =
      mapOf(
        "mwear amulet" to "WEAR:None",
        "mdrop pebble" to "DROP:None",
        "mgive pebble" to "GIVE:None",
        "mput" to "PUT:None",
        "menter //zones/z:yard" to "ENTER:None",
        "mlearn 20" to "LEARN:None",
      )
    for ((line, expected) in allowed) assertContains(f.say(p, line), expected, message = line)

    val refused =
      mapOf(
        RestrictionPoint.WEAR to ("mwear amulet" to "WEAR:Not this."),
        RestrictionPoint.DROP to ("mdrop pebble" to "DROP:Not this."),
        RestrictionPoint.GIVE to ("mgive pebble" to "GIVE:Not this."),
        RestrictionPoint.PUT to ("mput" to "PUT:Not this."),
        RestrictionPoint.ENTER to ("menter //zones/z:yard" to "ENTER:Not this."),
        RestrictionPoint.LEARN to ("mlearn 20" to "LEARN:Not this."),
      )
    for ((point, case) in refused) {
      f.refuse(point, "Not this.")
      assertContains(f.say(p, case.first), case.second, message = case.first)
    }
  }

  @Test
  fun `may_wear and may_remove answer for wear and remove_worn and move nothing`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "gift amulet")
    val amulet = p.inventory.single()
    assertContains(f.say(p, "mwield amulet"), "WIELD:You can't wield an amulet.")
    assertEquals(ItemLocation.Carried(p.id), amulet.location)
    f.w.wearItem(p, amulet)
    f.refuse(RestrictionPoint.REMOVE, "It's stuck.")
    assertContains(f.say(p, "mremove amulet"), "REMOVE:It's stuck.")
    assertEquals(ItemLocation.Equipped(p.id, "neck"), amulet.location)
  }

  @Test
  fun `may_give refuses the giver as receiver and moves nothing`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "gift pebble")
    assertContains(f.say(p, "mgiveself pebble"), "GIVE:You already have it.")
    assertEquals(ItemLocation.Carried(p.id), p.inventory.single().location)
  }

  @Test
  fun `may_put refuses a closed container`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "gift amulet")
    f.say(p, "gift bag")
    p.inventory.first { "bag" in it.keywords }.container!!.state = gups.core.model.DoorState.CLOSED
    assertContains(f.say(p, "mput"), "PUT:A bag is closed.")
  }

  @Test
  fun `wrong arguments are script errors`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "floor")
    assertEquals(1, f.diagnostics("script_error").count { "may_drop" in it.message })
    f.say(p, "gift pebble")
    f.say(p, "gift amulet")
    f.say(p, "mputpebble")
    assertEquals(1, f.diagnostics("script_error").count { "not a container" in it.message })
    f.say(p, "mremove amulet")
    assertEquals(1, f.diagnostics("script_error").count { "may_remove" in it.message })
    f.say(p, "menter //zones/z:nowhere")
    assertEquals(1, f.diagnostics("script_error").count { "may_enter" in it.message })
    f.say(p, "mlearn 101")
    assertEquals(1, f.diagnostics("script_error").count { "may_learn" in it.message })
  }
}
