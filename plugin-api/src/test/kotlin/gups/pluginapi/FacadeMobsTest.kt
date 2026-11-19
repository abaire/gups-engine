package gups.pluginapi

import gups.core.Label
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.flushOutput
import gups.core.world.handle
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeMobsTest {
  private val w: World = world(files = MOB_FILES)
  private val f = WorldFacade(w, "weather") {}
  private val playerEntity: Player = w.spawnPlayer("Ann", Label("//zones/z:a"))
  private val player = ActorFacade(playerEntity, w)
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
  fun `spawnMob returns plain mobs with no owner and a home room`() {
    val mobs = f.spawnMob("//zones/z:wolf", roomA, count = 3)
    assertEquals(3, mobs.size)
    for (m in mobs) {
      assertTrue(roomA.actors.any { it.id == m.id }, "spawned mob is in the room")
      assertNull(m.owner)
      assertEquals(roomA, m.homeRoom)
      assertFalse(m.leashOverridden)
    }
  }

  @Test
  fun `spawnMob runs on_spawn which sees the room`() {
    val mob = f.spawnMob("//zones/z:wolf", roomA).single()
    assertEquals("//zones/z:a", f.getAttr(mob, "z:spawn_room"))
  }

  @Test
  fun `spawnMob with an item label throws`() {
    val e = assertFailsWith<IllegalArgumentException> { f.spawnMob("//zones/z:stick", roomA) }
    assertContains(e.message!!, "is not a mob")
  }

  @Test
  fun `spawnMob with a count below 1 throws`() {
    val e =
      assertFailsWith<IllegalArgumentException> { f.spawnMob("//zones/z:wolf", roomA, count = 0) }
    assertContains(e.message!!, "at least 1")
  }

  @Test
  fun `actMove moves the mob after a pulse and a player throws`() {
    val wolf = ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
    assertTrue(f.actMove(wolf, "north"))
    w.runPulse()
    assertEquals("//zones/z:b", wolf.room.label)
    val e = assertFailsWith<IllegalArgumentException> { f.actMove(player, "north") }
    assertContains(e.message!!, "must be a mob")
  }

  @Test
  fun `act queues a command the room hears after a pulse`() {
    val out = hear(playerEntity)
    val wolf = ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
    f.act(wolf, "say hi")
    w.runPulse()
    w.flushOutput()
    assertContains(out.joinToString("\n"), "hi")
  }

  @Test
  fun `actCast with an unknown skill throws`() {
    val wolf = ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
    val e = assertFailsWith<IllegalArgumentException> { f.actCast(wolf, "//zones/z:fireball") }
    assertContains(e.message!!, "there is no skill")
  }

  @Test
  fun `actCast with the skills feature off throws IllegalStateException`() {
    val w2 = world(files = MOB_FILES, features = Features(Feature.entries.toSet() - Feature.SKILLS))
    val f2 = WorldFacade(w2, "weather") {}
    val wolf = ActorFacade(w2.spawnMob(Label("//zones/z:wolf"), w2.room(Label("//zones/z:a"))), w2)
    assertFailsWith<IllegalStateException> { f2.actCast(wolf, "//zones/z:fireball") }
  }

  @Test
  fun `summon with a player caster sets the owner and the summon fades`() {
    val mobs = f.summon(player, "//zones/z:wolf", count = 1, duration = 2)
    assertEquals(1, mobs.size)
    val summon = mobs.single()
    assertEquals(player, summon.owner)
    val core = summon as ActorFacade
    assertTrue(core.actor.inWorld)
    w.runPulses(4)
    assertFalse(core.actor.inWorld)
  }

  @Test
  fun `setOwner and clearOwner change the owner`() {
    val wolf = ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
    assertTrue(f.setOwner(wolf, player))
    assertEquals(player, wolf.owner)
    assertTrue(f.clearOwner(wolf))
    assertNull(wolf.owner)
  }

  @Test
  fun `setLeash overrides and resetLeash drops the override`() {
    val wolf = ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
    f.setLeash(wolf, 2)
    assertEquals(2, wolf.leash)
    assertTrue(wolf.leashOverridden)
    f.resetLeash(wolf)
    assertNull(wolf.leash)
    assertFalse(wolf.leashOverridden)
    val e = assertFailsWith<IllegalArgumentException> { f.setLeash(wolf, -1) }
    assertContains(e.message!!, "at least 0")
  }

  @Test
  fun `despawn removes a mob, rejects players and second calls`() {
    val wolf = ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
    assertTrue(wolf.inWorld)
    f.despawn(wolf)
    assertFalse(wolf.inWorld)
    assertFalse(roomA.actors.any { it.id == wolf.id })

    val second = assertFailsWith<IllegalArgumentException> { f.despawn(wolf) }
    assertContains(second.message!!, "no longer in the world")

    val onPlayer = assertFailsWith<IllegalArgumentException> { f.despawn(player) }
    assertEquals("despawn(mob): must be a mob", onPlayer.message)
  }

  @Test
  fun `despawn on a spawn-rule mob lets the rule respawn it after its deadline`() {
    val w2 =
      world(
        files =
          mapOf(
            "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
            "zones/z/rooms.star" to
              """
              room("a", name = "Glade", desc = "A glade.", flags = ["dead_end_ok"],
                   spawns = [spawn(":wolf", respawn = 2)])
              mob("wolf", name = "a wolf", keywords = ["wolf"])
              """,
          )
      )
    val f2 = WorldFacade(w2, "weather") {}
    w2.spawnPlayer("Ann", Label("//zones/z:a"))
    val room = f2.room("//zones/z:a")!!
    val wolf = room.actors.single { it.name == "a wolf" }
    assertTrue(wolf.inWorld)

    f2.despawn(wolf)
    assertFalse(wolf.inWorld)
    assertTrue(room.actors.none { it.name == "a wolf" })

    w2.runPulses(120)
    val wolves = room.actors.filter { it.name == "a wolf" }
    assertEquals(1, wolves.size)
    assertTrue(wolves.single().inWorld)
  }

  companion object {
    val MOB_FILES =
      mapOf(
        "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
        "zones/z/rooms.star" to
          """
          def _spawned(ctx):
              ctx.set_attr(ctx.self, "spawn_room", ctx.room.label)

          room("a", name = "Glade", desc = "A glade.", exits = [exit(DIR.north, ":b")])
          room("b", name = "Cave", desc = "A cave.", exits = [exit("south", ":a")])
          mob("wolf", name = "a wolf", keywords = ["wolf"], on_spawn = _spawned)
          item("stick", name = "a stick", keywords = ["stick"])
          """,
      )
  }
}
