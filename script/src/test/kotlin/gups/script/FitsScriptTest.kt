package gups.script

import gups.core.model.Player
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** `ctx.fits` and the `item_info` field of a carry_limit rules call. */
class FitsScriptTest {
  private val ZONE =
    """
    def _say(ctx, what):
        ctx.send(ctx.actor, "FITS:%s" % what)

    def _bag(ctx):
        return ctx.actor.inventory()[0]

    def _fits_label(ctx):
        _say(ctx, ctx.fits(":coin", ctx.actor, int(ctx.args)))

    def _fits_default(ctx):
        _say(ctx, ctx.fits(":coin", ctx.actor))

    def _fits_stack(ctx):
        ctx.grant_item(ctx.actor, ":coin", count = 30)
        for i in ctx.actor.inventory():
            if "coin" in i.keywords:
                _say(ctx, ctx.fits(i, ctx.actor))

    def _fits_bag(ctx):
        _say(ctx, ctx.fits(":coin", _bag(ctx), 10))

    def _fits_room(ctx):
        _say(ctx, ctx.fits(":coin", ctx.room, 10))

    def _fits_bad(ctx):
        _say(ctx, ctx.fits(ctx.args, ctx.actor))

    def _fits_not_container(ctx):
        ctx.grant_item(ctx.actor, ":coin")
        _say(ctx, ctx.fits(":coin", ctx.actor.inventory()[0]))

    def _fits_zero(ctx):
        _say(ctx, ctx.fits(":coin", ctx.actor, 0))

    def _gift(ctx):
        ctx.grant_item(ctx.actor, ":" + ctx.args)

    item("coin", name = "a coin", keywords = ["coin"], stackable = True, weight = 2,
         tags = ["currency"], kind = "money")
    item("bag", name = "a bag", keywords = ["bag"], container = container(capacity = 2, max_weight = 8))
    room("start", commands = {
        "flabel": _fits_label, "fdefault": _fits_default, "fstack": _fits_stack,
        "fbag": _fits_bag, "froom": _fits_room, "fbad": _fits_bad,
        "fnotbox": _fits_not_container, "fzero": _fits_zero, "gift": _gift})
    """

  private val LIMIT =
    """
    def carry_limit(ctx):
        info = ctx.item_info
        if info == None:
            return 20
        if "currency" not in info.tags or info.kind != "money":
            return 0
        return 20 if ctx.item == None else 100
    """

  private fun fixture() =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to ZONE,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(LIMIT),
      )
    )

  private fun ScriptFixture.say(p: Player, line: String): String {
    input(p, line)
    run(1)
    return out(p)
  }

  @Test
  fun `a label asked of an actor is capped by carry_limit, which reads item_info and no item`() {
    val f = fixture()
    val p = f.player()
    assertContains(f.say(p, "flabel 30"), "FITS:10")
    assertContains(f.say(p, "fdefault"), "FITS:1")
  }

  @Test
  fun `an item asked of an actor gives carry_limit both the item and its info`() {
    val f = fixture()
    val p = f.player()
    assertContains(f.say(p, "fstack"), "FITS:20")
  }

  @Test
  fun `a container's capacity and weight cap it and a room takes all`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "gift bag")
    assertContains(f.say(p, "fbag"), "FITS:4")
    assertContains(f.say(p, "froom"), "FITS:10")
  }

  @Test
  fun `bad arguments fail the verb`() {
    val f = fixture()
    val p = f.player()
    assertFalse("FITS:" in f.say(p, "fbad :nothing"))
    assertFalse("FITS:" in f.say(p, "fzero"))
    assertFalse("FITS:" in f.say(p, "fnotbox"))
  }
}
