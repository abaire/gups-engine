package gups.script

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.Player
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TrickExitRetargetScriptTest {
  private val ZONE =
    """
    def _to_cave(ctx):
        ctx.set_exit_to(ctx.room, "down", ":cave")
        ctx.send(ctx.actor, "to=%s orig=%s" % (
            ctx.room.exit("down").to, ctx.room.exit("down").original_to))

    def _to_room_view(ctx):
        cave = ctx.find(":cave") if False else None
        # Resolve cave via another exit or room view: use ctx.room after walking or pass room view.
        ctx.set_exit_to(ctx.room, "down", ctx.room)
        ctx.send(ctx.actor, "to=%s" % ctx.room.exit("down").to)

    def _reset(ctx):
        ctx.set_exit_to(ctx.room, "down", None)
        ctx.send(ctx.actor, "to=%s" % ctx.room.exit("down").to)

    def _bad_door(ctx):
        ctx.set_exit_to(ctx.room, "north", ":cave")

    def _bad_cross(ctx):
        ctx.set_exit_to(ctx.room, "west", ":cave")

    def _bad_missing(ctx):
        ctx.set_exit_to(ctx.room, "east", ":cave")

    room("start",
         exits = [
             exit("down", ":grotto", one_way = True),
             exit("north", ":vault", door = door(state = "closed"), one_way = True),
             exit("west", [":grotto", ":cave"]),
         ],
         commands = {
             "tocave": _to_cave,
             "toroom": _to_room_view,
             "reset": _reset,
             "baddoor": _bad_door,
             "badcross": _bad_cross,
             "badmissing": _bad_missing,
         })
    room("grotto")
    room("cave")
    room("vault")
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
  fun `ctx set_exit_to accepts label, room view, and None`() {
    val f = fixture()
    val p = f.player()

    assertContains(f.say(p, "tocave"), "to=//zones/z:cave orig=//zones/z:grotto")
    assertEquals(
      Label("//zones/z:cave"),
      f.w.room(Label("//zones/z:start")).exits.getValue(Direction.DOWN).to,
    )

    assertContains(f.say(p, "reset"), "to=//zones/z:grotto")
    assertEquals(
      Label("//zones/z:grotto"),
      f.w.room(Label("//zones/z:start")).exits.getValue(Direction.DOWN).to,
    )

    assertContains(f.say(p, "toroom"), "to=//zones/z:start")
    assertEquals(
      Label("//zones/z:start"),
      f.w.room(Label("//zones/z:start")).exits.getValue(Direction.DOWN).to,
    )
  }

  @Test
  fun `ctx set_exit_to reports errors on door, per-crossing, and missing exits`() {
    val f = fixture()
    val p = f.player()

    f.say(p, "baddoor")
    assertContains(
      f.w.diagnostics.last().message,
      "set_exit_to(): //zones/z:start exit north has a door and can't be retargeted",
    )

    f.say(p, "badcross")
    assertContains(
      f.w.diagnostics.last().message,
      "set_exit_to(): //zones/z:start exit west is picked per crossing and can't be retargeted",
    )

    f.say(p, "badmissing")
    assertContains(
      f.w.diagnostics.last().message,
      "set_exit_to(): //zones/z:start has no exit east",
    )
  }
}
