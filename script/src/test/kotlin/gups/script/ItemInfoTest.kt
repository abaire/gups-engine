package gups.script

import gups.core.model.Player
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `ctx.item_info`: an item prototype's catalog fields by label, with nothing spawned. */
class ItemInfoTest {
  private val ZONE =
    """
    def _info(ctx):
        i = ctx.item_info(ctx.args)
        if i == None:
            ctx.send(ctx.actor, "INFO:None")
            return
        ctx.send(ctx.actor, "INFO:%s|%s|%s|%s|%s|%s|%d|%d|%s|%s" % (i.label, i.name, i.plural, i.keywords,
            i.kind, i.tags, i.value, i.weight, i.stackable, i.charges))

    item("bar", name = "an iron bar", plural = "iron bars", keywords = ["bar", "iron"],
         stackable = True, tags = ["metal", "bar"], value = 4, weight = 2)
    item("wand", name = "a wand", keywords = ["wand"], charges = 3)
    room("start", commands = {"info": _info})
    """

  private fun fixture() =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to ZONE,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
      )
    )

  private fun ScriptFixture.say(p: Player, line: String): String {
    input(p, line)
    run(1)
    return out(p)
  }

  @Test
  fun `item_info reads a prototype by relative or absolute label`() {
    val f = fixture()
    val p = f.player()
    val before = f.w.actors.size + f.w.rooms.sumOf { it.items.size } + p.inventory.size
    val bar =
      "INFO://zones/z:bar|an iron bar|iron bars|[\"bar\", \"iron\"]|misc|[\"bar\", \"metal\"]|4|2|True|None"
    assertContains(f.say(p, "info :bar"), bar)
    assertContains(f.say(p, "info //zones/z:bar"), bar)
    assertContains(f.say(p, "info :wand"), "|a wand|None|[\"wand\"]|misc|[]|0|1|False|3")
    assertEquals(
      before,
      f.w.actors.size + f.w.rooms.sumOf { it.items.size } + p.inventory.size,
      "nothing was spawned",
    )
  }

  @Test
  fun `an unknown label is None and a malformed one an error`() {
    val f = fixture()
    val p = f.player()
    assertContains(f.say(p, "info :nope"), "INFO:None")
    val errors = f.diagnostics("script_error").size
    f.say(p, "info no colon")
    assertEquals(errors + 1, f.diagnostics("script_error").size)
  }
}
