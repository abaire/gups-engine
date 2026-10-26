package gups.core.world

import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GmcpTest {
  /** A client that supports the modules in [supports] and records what it gets. */
  private class GmcpSink(
    private val supports: Set<String> = setOf("Char", "Room", "Comm.Channel")
  ) : OutputSink {
    val messages = mutableListOf<Pair<String, Any?>>()
    val order = mutableListOf<String>()

    override fun send(text: String) {
      order += "text"
    }

    override fun gmcp(pkg: String, data: Any?) {
      messages += pkg to data
      order += pkg
    }

    override fun wantsGmcp(pkg: String) = supports.any { pkg == it || pkg.startsWith("$it.") }

    override fun close(reason: String) = Unit

    fun take(): List<Pair<String, Any?>> = messages.toList().also { messages.clear() }

    fun packages() = take().map { it.first }
  }

  private var nextSession = 100L

  private fun Fixture.gmcpPlayer(
    name: String,
    index: Int = 0,
    sink: GmcpSink = GmcpSink(),
  ): Pair<Player, GmcpSink> {
    val p = w.spawnPlayer(name, T.HALL, hp = 50, index = index)
    val id = SessionId(nextSession++)
    w.handle(WorldControlEvent.Connect(id, sink))
    w.attach(id, p)
    return p to sink
  }

  @Test
  fun `state packages are sent once, then only when they change`() {
    val f = Fixture()
    val (p, sink) = f.gmcpPlayer("Ann")
    f.run()
    assertEquals(
      listOf("Char.Vitals", "Char.Status", "Char.Affects", "Room.Info", "Room.Bands"),
      sink.packages(),
    )
    f.run(3)
    assertEquals(emptyList(), sink.packages(), "unchanged packages send nothing")
    p.hp -= 5
    f.run()
    val vitals = sink.take().single()
    assertEquals(
      "Char.Vitals" to
        mapOf(
          "hp" to 45,
          "maxhp" to 50,
          "mana" to 20,
          "maxmana" to 20,
          "move" to 100,
          "maxmove" to 100,
        ),
      vitals,
    )
    f.mob(index = 1)
    f.run()
    assertEquals(listOf("Room.Bands"), sink.packages())
    f.w.teleport(p, f.w.room(T.YARD))
    f.run()
    assertEquals(listOf("Room.Info", "Room.Bands"), sink.packages())
    assertEquals(
      mapOf(
        "label" to "//t:yard",
        "name" to "The Yard",
        "exits" to mapOf("south" to null),
        "regions" to emptyList<String>(),
      ),
      f.w.gmcpPayload(p, "Room.Info"),
    )
    f.w.teleport(p, f.w.room(T.HALL))
    f.run()
    sink.take()
    f.w.room(T.HALL).exits.getValue(gups.core.model.Direction.SOUTH).door!!.state =
      gups.core.model.DoorState.OPEN
    f.run()
    assertEquals(listOf("Room.Info"), sink.packages(), "a door here changed")
  }

  @Test
  fun `plugin producers send deltas, and a failing one is recorded and skipped`() {
    val f = Fixture()
    var temp = 10
    f.w.pluginGmcp["Gups.Weather"] = { mapOf("temp" to temp) }
    f.w.pluginGmcp["Gups.Broken"] = { error("boom") }
    val (_, sink) = f.gmcpPlayer("Ann", sink = GmcpSink(setOf("Gups.Weather", "Gups.Broken")))
    f.run()
    assertEquals(listOf("Gups.Weather" to mapOf("temp" to 10)), sink.take())
    f.run()
    assertEquals(emptyList(), sink.packages())
    temp = 12
    f.run()
    assertEquals(listOf("Gups.Weather" to mapOf("temp" to 12)), sink.take())
    assertTrue(
      f.w.diagnostics.any {
        it.kind == "plugin" && "GMCP producer Gups.Broken failed" in it.message
      }
    )
  }

  @Test
  fun `room bands come from each viewer's own index`() {
    val f = Fixture()
    val (ann, _) = f.gmcpPlayer("Ann", index = 0)
    val (bob, _) = f.gmcpPlayer("Bob", index = 5)
    val goblin = f.mob(index = 0)
    fun bands(p: Player) = f.w.gmcpPayload(p, "Room.Bands") as Map<*, *>
    val g = mapOf("id" to goblin.id.v, "name" to "a goblin", "kind" to "actor")
    assertEquals(
      mapOf(
        "melee" to listOf(g),
        "near" to emptyList<Any>(),
        "far" to listOf(mapOf("id" to bob.id.v, "name" to "Bob", "kind" to "actor")),
      ),
      bands(ann),
    )
    assertEquals(
      listOf(g, mapOf("id" to ann.id.v, "name" to "Ann", "kind" to "actor")).sortedBy {
        it["id"] as Long
      },
      bands(bob)["far"],
    )
    assertTrue(
      (bands(ann).values.flatMap { it as List<*> }).none { (it as Map<*, *>)["id"] == ann.id.v },
      "the viewer isn't listed",
    )
  }

  @Test
  fun `channel text follows the text it goes with, and only for supported clients`() {
    val f = Fixture()
    val (ann, sink) = f.gmcpPlayer("Ann")
    val (_, plain) = f.gmcpPlayer("Bob", sink = GmcpSink(emptySet()))
    f.run()
    sink.take()
    sink.order.clear()
    f.input(ann, "say hello there")
    assertEquals(listOf("text", "Comm.Channel.Text"), sink.order)
    assertEquals(
      listOf(
        "Comm.Channel.Text" to mapOf("channel" to "say", "talker" to "Ann", "text" to "hello there")
      ),
      sink.take(),
    )
    f.input(ann, "gossip hi all")
    assertEquals(
      mapOf("channel" to "gossip", "talker" to "Ann", "text" to "hi all"),
      sink.take().single().second,
    )
    assertEquals(emptyList(), plain.take(), "a client without Core.Supports gets no GMCP")
  }

  @Test
  fun `status shows casting progress, combat and the next level`() {
    val f = Fixture()
    val (p, _) = f.gmcpPlayer("Ann")
    f.mob()
    f.input(p, "kill goblin")
    f.run(9)
    @Suppress("UNCHECKED_CAST") val status = f.w.gmcpPayload(p, "Char.Status") as Map<String, Any?>
    assertEquals(listOf("a goblin"), status["fighting"])
    assertEquals(1000L, status["xp_next"])
    assertEquals(null, status["casting"])
  }
}
