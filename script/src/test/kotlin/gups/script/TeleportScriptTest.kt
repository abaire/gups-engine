package gups.script

import gups.core.Label
import gups.core.model.Player
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `ctx.teleport` returns the refusal or veto, or None once the actor has moved. */
class TeleportScriptTest {
  private val ZONE =
    """
    def _veto(ctx):
        if ctx.direction != None or ctx.from_room == None:
            return "bad context"
        return ctx.deny("The vault is sealed.")

    def _port(ctx):
        ctx.send(ctx.actor, "TELEPORT:%s" % ctx.teleport(ctx.actor, ":" + ctx.args))

    room("start", commands = {"port": _port})
    room("vault", before_enter = _veto)
    room("yard")
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
  fun `a veto comes back as a string and moves nothing`() {
    val f = fixture()
    val p = f.player()
    assertEquals("TELEPORT:The vault is sealed.\n", f.say(p, "port vault"))
    assertEquals(Label("//zones/z:start"), p.room.label)
  }

  @Test
  fun `a teleport that moves the actor returns None`() {
    val f = fixture()
    val p = f.player()
    assertContains(f.say(p, "port yard"), "TELEPORT:None")
    assertEquals(Label("//zones/z:yard"), p.room.label)
  }
}
