package gups.pluginapi

import gups.core.Label
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.flushOutput
import gups.core.world.handle
import gups.core.world.isSimulated
import gups.core.world.quit
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeMessagingTest {
  private val w: World = world(files = MESSAGING_FILES)
  private val f = WorldFacade(w, "weather") {}
  private val playerEntity: Player = w.spawnPlayer("Ann", Label("//zones/z:a"))
  private val player = ActorFacade(playerEntity, w)
  private val warden =
    ActorFacade(w.spawnMob(Label("//zones/z:warden"), w.room(Label("//zones/z:a"))), w)
  private val roomA: PluginRoom
    get() = f.room("//zones/z:a")!!

  /** Attaches a recording sink to [player] and returns its captured lines. */
  private fun hear(player: Player): List<String> {
    val lines = mutableListOf<String>()
    val sink =
      object : OutputSink {
        override fun send(text: String) {
          lines += text
        }

        override fun gmcp(pkg: String, data: Any?) {}

        override fun wantsGmcp(pkg: String) = false

        override fun close(reason: String) {}
      }
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), player)
    return lines
  }

  @Test
  fun `say speaks and fires on_say in the room`() {
    val out = hear(playerEntity)
    f.say(player, "hello")
    w.flushOutput()
    assertContains(out.joinToString("\n"), "You say, 'hello'")
    assertEquals("hello", f.getAttr(warden, "z:last_say"))
  }

  @Test
  fun `emote reaches the room`() {
    val out = hear(playerEntity)
    f.emote(player, "waves")
    w.flushOutput()
    assertContains(out.joinToString("\n"), "waves")
  }

  @Test
  fun `emit delivers a perceivable event`() {
    f.emit(roomA, "sound", "boom")
    w.runPulse()
    assertEquals("boom", f.getAttr(warden, "z:last_perceive"))
  }

  @Test
  fun `emit with intensity 0 delivers nothing`() {
    f.emit(roomA, "sound", "faint", intensity = 0)
    w.runPulse()
    assertNull(f.getAttr(warden, "z:last_perceive"))
  }

  @Test
  fun `emit with intensity 0 still rejects an actor that left the world`() {
    w.quit(playerEntity)
    val e =
      assertFailsWith<IllegalArgumentException> {
        f.emit(roomA, "sound", "faint", intensity = 0, actor = player)
      }
    assertContains(e.message!!, "emit(actor)")
  }

  @Test
  fun `emit with an unknown channel throws with a suggestion`() {
    val e = assertFailsWith<IllegalArgumentException> { f.emit(roomA, "souund", "boom") }
    assertContains(e.message!!, "sound")
  }

  /** A mob in a dormant far room is simulated only while a [reason] is held. */
  private fun assertSimulationToggle(
    aw: World,
    reason: String,
    set: (PluginEntity, String) -> Unit,
    clear: (PluginEntity, String) -> Unit,
  ) {
    val mob = aw.spawnMob(Label("//zones/z:warden"), aw.room(Label("//zones/z:e")))
    val facade = ActorFacade(mob, aw)
    assertFalse(aw.isSimulated(mob))
    set(facade, reason)
    assertTrue(aw.isSimulated(mob))
    clear(facade, reason)
    assertFalse(aw.isSimulated(mob))
  }

  @Test
  fun `setAnchor and clearAnchor toggle simulation of a mob in a far room`() {
    val aw = world(files = ANCHOR_FILES, bubble = true)
    val af = WorldFacade(aw, "weather") {}
    assertSimulationToggle(aw, "hold", af::setAnchor, af::clearAnchor)
  }

  @Test
  fun `setActive and clearActive toggle a simulation reason`() {
    val aw = world(files = ANCHOR_FILES, bubble = true)
    val af = WorldFacade(aw, "weather") {}
    assertSimulationToggle(aw, "busy", af::setActive, af::clearActive)
  }

  @Test
  fun `an empty simulation reason fails with the ctx message and a reason as ctx allows it`() {
    val aw = world(files = ANCHOR_FILES, bubble = true)
    val af = WorldFacade(aw, "weather") {}
    val mob = aw.spawnMob(Label("//zones/z:warden"), aw.room(Label("//zones/z:e")))
    val facade = ActorFacade(mob, aw)
    val e = assertFailsWith<IllegalArgumentException> { af.setActive(facade, "") }
    assertContains(e.message!!, "must not be empty")
    // `ctx.set_active` accepts any non-empty reason, so a whitespace one must not be rejected here.
    assertSimulationToggle(aw, "  ", af::setActive, af::clearActive)
  }

  @Test
  fun `clearStanding removes a record adjustStanding made`() {
    f.adjustStanding(warden, player, 20)
    assertEquals(20, f.standing(warden, player))
    f.clearStanding(warden, player)
    assertEquals(0, f.standing(warden, player))
  }

  @Test
  fun `standingHistory sums gains and losses`() {
    f.adjustStanding(warden, player, 30)
    f.adjustStanding(warden, player, -10)
    val totals = f.standingHistory(warden, player, 0)
    assertEquals(30L, totals.gained)
    assertEquals(10L, totals.lost)
  }

  @Test
  fun `roll rolls dice with the world rng`() {
    assertTrue(f.roll("2d6+1") in 3..13)
    val e = assertFailsWith<IllegalArgumentException> { f.roll("nonsense") }
    assertContains(e.message!!, "roll():")
  }

  @Test
  fun `rng choice matches a same-seeded draw`() {
    val w1 = world(seed = 99)
    val w2 = world(seed = 99)
    val f1 = WorldFacade(w1, "x") {}
    val f2 = WorldFacade(w2, "x") {}
    val items = listOf("alpha", "beta", "gamma")
    assertEquals(f1.rng.choice(items), f2.rng.choice(items))
  }

  @Test
  fun `rng choice on an empty list throws`() {
    val e = assertFailsWith<IllegalArgumentException> { f.rng.choice(emptyList()) }
    assertEquals("choice(): the list is empty", e.message)
  }

  @Test
  fun `heal with a source credits the source`() {
    val wolf = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))
    val wolfFacade = ActorFacade(wolf, w)
    assertEquals(0, f.standing(wolfFacade, player))
    f.damage(wolfFacade, 5, "fire", "hp")
    f.heal(wolfFacade, 5, "hp", source = player)
    assertEquals(5, f.standing(wolfFacade, player))
  }

  companion object {
    val MESSAGING_FILES =
      mapOf(
        "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
        "zones/z/rooms.star" to
          """
          def _heard_say(ctx):
              ctx.set_attr(ctx.self, "last_say", ctx.text)

          def _heard_perceive(ctx):
              ctx.set_attr(ctx.self, "last_perceive", ctx.event)

          room("a", name = "Glade", desc = "A glade.", exits = [exit(DIR.north, ":b")])
          room("b", name = "Cave", desc = "A cave.", flags = ["dead_end_ok"], exits = [exit("south", ":a")])
          mob("warden", name = "a warden", keywords = ["warden"], on_say = _heard_say, on_perceive = _heard_perceive)
          mob("wolf", name = "a wolf", desc = "A wolf.", keywords = ["wolf"])
          """,
      )

    val ANCHOR_FILES =
      mapOf(
        "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
        "zones/z/rooms.star" to
          """
          room("a", exits = [exit("north", ":b")])
          room("b", exits = [exit("south", ":a"), exit("north", ":c")])
          room("c", exits = [exit("south", ":b"), exit("north", ":d")])
          room("d", exits = [exit("south", ":c"), exit("north", ":e")])
          room("e", flags = ["dead_end_ok"], exits = [exit("south", ":d")])
          mob("warden", name = "a warden", keywords = ["warden"])
          """,
      )
  }
}
