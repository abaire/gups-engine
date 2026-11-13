package gups.script

import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The custody verbs act on the item, so they don't ask the `remove` restriction; `remove_worn`
 * does.
 */
class WornCustodyScriptTest {
  private val ZONE =
    """
    def _gift(ctx):
        ctx.grant_item(ctx.actor, ":amulet")

    def _worn(ctx):
        return ctx.actor.equipped("neck")

    def _put_on(ctx):
        ctx.wear(ctx.actor, ctx.actor.inventory()[0])

    def _unequip(ctx):
        ctx.send(ctx.actor, "REMOVE_WORN:%s" % ctx.remove_worn(ctx.actor, _worn(ctx)))

    def _hand_over(ctx):
        keeper = [a for a in ctx.room.actors() if a != ctx.actor][0]
        ctx.grant_item(keeper, _worn(ctx))

    def _drop_it(ctx):
        ctx.move_item(_worn(ctx), ctx.room)

    def _smash(ctx):
        ctx.destroy(_worn(ctx))

    item("amulet", name = "an amulet", keywords = ["amulet"], wear = "neck")
    mob("keeper", name = "the keeper", vitals = vitals(hp = 10))
    room("start", spawns = [spawn(":keeper")], commands = {
        "gift": _gift, "puton": _put_on, "unequip": _unequip, "handover": _hand_over,
        "dropit": _drop_it, "smash": _smash})
    """

  private val asked = mutableListOf<String>()

  private fun fixture() =
    ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to ZONE,
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rules(""),
        )
      )
      .also { f ->
        f.w.restrictions =
          mapOf(
            RestrictionPoint.REMOVE to
              listOf(
                Restriction { _, _ ->
                  asked += "remove"
                  "Cursed."
                }
              )
          )
      }

  private fun ScriptFixture.say(p: Player, line: String): String {
    input(p, line)
    run(1)
    return out(p)
  }

  private fun ScriptFixture.wearing(): Pair<Player, gups.core.model.Item> {
    val p = player()
    say(p, "gift")
    say(p, "puton")
    val amulet = p.equipment.values.single()
    assertEquals(ItemLocation.Equipped(p.id, "neck"), amulet.location)
    asked.clear()
    return p to amulet
  }

  @Test
  fun `remove_worn asks the remove restriction and leaves the item worn`() {
    val f = fixture()
    val (p, amulet) = f.wearing()
    assertContains(f.say(p, "unequip"), "REMOVE_WORN:Cursed.")
    assertEquals(ItemLocation.Equipped(p.id, "neck"), amulet.location)
    assertEquals(listOf("remove"), asked)
  }

  @Test
  fun `grant_item takes a worn item to another actor without asking remove`() {
    val f = fixture()
    val (p, amulet) = f.wearing()
    val keeper = f.w.actors.filterIsInstance<gups.core.model.Mob>().single()
    f.say(p, "handover")
    assertEquals(ItemLocation.Carried(keeper.id), amulet.location)
    assertEquals(emptyList(), asked)
  }

  @Test
  fun `move_item takes a worn item out of its slot without asking remove`() {
    val f = fixture()
    val (p, amulet) = f.wearing()
    f.say(p, "dropit")
    assertIs<ItemLocation.InRoom>(amulet.location)
    assertEquals(emptyList(), asked)
  }

  @Test
  fun `destroy removes a worn item without asking remove`() {
    val f = fixture()
    val (p, amulet) = f.wearing()
    f.say(p, "smash")
    assertEquals(emptyList(), p.equipment.values.toList())
    assertEquals(null, f.w.item(amulet.id))
    assertEquals(emptyList(), asked)
  }
}
