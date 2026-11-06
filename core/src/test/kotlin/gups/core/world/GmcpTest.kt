package gups.core.world

import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Channel
import gups.core.model.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class GmcpTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @BeforeEach
  fun useSchema() {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  private var nextSession = 100L

  @Test
  fun `state packages are sent once, then only when they change`() {
    val f = Fixture()
    val (p, sink) = f.gmcpPlayer("Ann")
    f.run()
    assertEquals(
      listOf(
        "Char.Schema",
        "Char.Vitals",
        "Char.Status",
        "Char.Affects",
        "Char.Skills",
        "Char.Group",
        "Room.Info",
        "Room.Bands",
      ),
      sink.packages(),
    )
    f.run(3)
    assertEquals(emptyList(), sink.packages(), "unchanged packages send nothing")
    f.setVital(p, fs.health, 45)
    f.run()
    val vitals = sink.take().single()
    assertEquals(
      "Char.Vitals" to
        LinkedHashMap<String, Any?>().also { out ->
          for (v in fs.vitals) {
            out[v] =
              if (v == fs.health) 45 else if (v in fs.startsEmpty) 0 else fs.playerMax.getValue(v)
            out["max$v"] = if (v == fs.health) 50 else fs.playerMax.getValue(v)
          }
        },
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
  fun `a shout reaches a distant player's channel only as perceive_message lets them hear it`() {
    val f = Fixture()
    val (ann, _) = f.gmcpPlayer("Ann")
    val (_, near) = f.gmcpPlayer("Cat", index = 1)
    val (eve, mute) = f.gmcpPlayer("Eve", index = 4)
    f.w.applyAffect(eve, "deaf", 100, senseDeltas = mapOf(Channel.SOUND to -1))
    val (_, far) = f.gmcpPlayer("Bob", index = 2, at = T.YARD)
    val (dan, deaf) = f.gmcpPlayer("Dan", index = 3, at = T.YARD)
    f.w.applyAffect(dan, "deaf", 100, senseDeltas = mapOf(Channel.SOUND to -1))
    f.run()
    for (s in listOf(near, far, deaf, mute)) s.take()
    f.input(ann, "shout HELLO")
    f.run(2)
    fun shouts(s: GmcpSink) = s.take().filter { it.first == "Comm.Channel.Text" }.map { it.second }
    assertEquals(
      listOf(mapOf("channel" to "shout", "talker" to "Ann", "text" to "Ann shouts, 'HELLO'")),
      shouts(near),
    )
    assertEquals(
      listOf(
        mapOf(
          "channel" to "shout",
          "talker" to "Ann",
          "text" to "Someone shouts from the south, 'HELLO'",
        )
      ),
      shouts(far),
    )
    assertEquals(emptyList(), shouts(deaf))
    assertEquals(emptyList(), shouts(mute), "a deaf player in the speaker's room hears nothing")
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

  @Test
  fun `Char Schema is sent once per character and again when the client starts supporting it`() {
    val f = Fixture()
    val sink = GmcpSink(setOf("Room"))
    val (p, _) = f.gmcpPlayer("Ann", sink = sink)
    f.run()
    assertTrue("Char.Schema" !in sink.packages(), "a client without Char gets no schema")
    sink.supports = setOf("Room", "Char")
    f.run()
    val sent = sink.take()
    assertEquals(
      listOf(
        "Char.Schema",
        "Char.Vitals",
        "Char.Status",
        "Char.Affects",
        "Char.Skills",
        "Char.Group",
      ),
      sent.map { it.first },
    )
    assertEquals(
      mapOf(
        "stats" to fs.schema.stats.map { mapOf("name" to it.name, "label" to it.label) },
        "vitals" to fs.schema.vitals.map { mapOf("name" to it.name, "label" to it.label) },
        "damage_types" to fs.schema.damageTypes,
        "features" to listOf("combat", "track", "skills", "leveling"),
      ),
      sent.first().second,
    )
    f.run(5)
    assertEquals(emptyList(), sink.packages(), "the schema is static")
    // A reconnect takes the character again: the cache resets and the schema comes once more.
    val again = GmcpSink()
    val id = SessionId(nextSession++)
    f.w.handle(WorldControlEvent.Connect(id, again))
    f.w.attach(id, p)
    f.run()
    assertEquals(1, again.packages().count { it == "Char.Schema" })
  }

  @Test
  fun `Char Skills lists the known skills and sends a delta when they change`() {
    val f = Fixture()
    val (p, sink) = f.gmcpPlayer("Ann")
    f.run()
    val first = sink.take().first { it.first == "Char.Skills" }
    assertEquals(mapOf("skills" to emptyList<Map<String, Any?>>()), first.second)
    f.w.setSkill(p, T.ZAP, 40)
    f.w.setSkill(p, T.BOLT, 15)
    f.run()
    assertEquals(
      "Char.Skills" to
        mapOf(
          "skills" to
            listOf(
              mapOf(
                "label" to "//t:bolt",
                "name" to "bolt",
                "kind" to "spell",
                "proficiency" to 15,
              ),
              mapOf("label" to "//t:zap", "name" to "zap", "kind" to "spell", "proficiency" to 40),
            )
        ),
      sink.take().single(),
    )
    f.run(3)
    assertEquals(emptyList(), sink.packages(), "an unchanged list sends nothing")
  }

  @Test
  fun `without skills, Char Skills is not sent`() {
    val f = Fixture(features = Features(Feature.entries.toSet() - setOf(Feature.SKILLS)))
    val (_, sink) = f.gmcpPlayer("Ann")
    f.run(2)
    assertTrue("Char.Skills" !in sink.packages(), "no Char.Skills without the skills feature")
  }

  @Test
  fun `Char Vitals keys follow the schema in order`() {
    val f = Fixture()
    val (p, _) = f.gmcpPlayer("Ann")
    val keys = (f.w.gmcpPayload(p, "Char.Vitals") as Map<*, *>).keys.toList()
    assertEquals(fs.vitals.flatMap { listOf(it, "max$it") }, keys)
  }

  @Test
  fun `the prompt is generated from the schema`() {
    val f = Fixture()
    val (p, _) = f.gmcpPlayer("Ann")
    val expected =
      if (fs == FixtureSchemas.FANTASY) "<50hp 20m 100mv> " else "<0rads 50integrity 120power> "
    assertEquals(expected, f.w.promptFor(p))
  }

  @Test
  fun `a prompt template fills in current and maximum values`() {
    val schema = FixtureSchemas.FANTASY.schema.copy(prompt = "[{hp}/{max_hp} {mana}m] ")
    val w = World(T.protos(FixtureSchemas.FANTASY), schema, FixedRules(FixtureSchemas.FANTASY))
    val p = w.spawnPlayer("Ann", T.HALL)
    assertEquals("[20/20 20m] ", w.promptFor(p))
    assertEquals("<{hp}hp {mana}m {move}mv> ", FixtureSchemas.FANTASY.schema.promptTemplate)
    assertEquals(
      "<{rads}rads {integrity}integrity {power}power> ",
      FixtureSchemas.ROBOT.schema.promptTemplate,
    )
  }

  @Test
  fun `Char Group is null without a group, lists the group with one, and deltas on change`() {
    val f = Fixture()
    val (ann, sink) = f.gmcpPlayer("Ann")
    f.run()
    assertEquals(null, sink.take().first { it.first == "Char.Group" }.second, "no group: null")
    val (ben, benSink) = f.gmcpPlayer("Ben")
    f.run()
    assertEquals(null, benSink.take().first { it.first == "Char.Group" }.second)
    f.w.groupAdd(ben, ann)
    f.run()
    @Suppress("UNCHECKED_CAST")
    val m = sink.take().first { it.first == "Char.Group" }.second as Map<String, Any?>
    assertEquals(1, m["id"])
    assertEquals("Ann", m["leader"])
    @Suppress("UNCHECKED_CAST") val members = m["members"] as List<Map<String, Any?>>
    assertEquals(listOf("Ann", "Ben"), members.map { it["name"] }, "leader first")
    assertEquals(listOf(true, false), members.map { it["leader"] })
    assertTrue(members.all { it["in_room"] == true }, "both are in the same room")
    @Suppress("UNCHECKED_CAST")
    val vitalsKeys = (f.w.gmcpPayload(ann, "Char.Vitals") as Map<*, *>).keys.toList()
    assertEquals(vitalsKeys, (members.first()["vitals"] as Map<*, *>).keys.toList())
    f.run(3)
    assertEquals(emptyList(), sink.packages(), "an unchanged group sends nothing")
    f.hurt(ben, 5)
    f.run()
    assertTrue(sink.packages().contains("Char.Group"), "a member's vitals change is a delta")
    f.w.groupSetLeader(ben)
    f.run()
    @Suppress("UNCHECKED_CAST")
    val m2 = sink.take().first { it.first == "Char.Group" }.second as Map<String, Any?>
    assertEquals("Ben", m2["leader"], "a leader change is a delta")
    f.w.groupRemove(ben)
    f.run()
    assertNull(sink.take().first { it.first == "Char.Group" }.second, "the disband sends null")
    f.run(3)
    assertEquals(emptyList(), sink.packages(), "null repeated sends nothing")
  }

  @Test
  fun `Char Group marks a member in another room as not in_room`() {
    val f = Fixture()
    val (ann, sink) = f.gmcpPlayer("Ann")
    val (ben, _) = f.gmcpPlayer("Ben", at = T.YARD)
    f.w.groupAdd(ben, ann)
    f.run()
    @Suppress("UNCHECKED_CAST")
    val m = sink.take().first { it.first == "Char.Group" }.second as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val members = m["members"] as List<Map<String, Any?>>
    assertEquals(listOf("Ann", "Ben"), members.map { it["name"] })
    assertEquals(listOf(true, false), members.map { it["in_room"] })
  }

  @Test
  fun `Char Group has no level field without leveling`() {
    val f = Fixture(features = Features(Feature.entries.toSet() - setOf(Feature.LEVELING)))
    val (ann, _) = f.gmcpPlayer("Ann")
    val (ben, _) = f.gmcpPlayer("Ben")
    f.w.groupAdd(ben, ann)
    f.run()
    @Suppress("UNCHECKED_CAST") val m = f.w.gmcpPayload(ann, "Char.Group") as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val member = m["members"] as List<Map<String, Any?>>
    assertFalse("level" in member.first(), "no level without leveling")
  }

  @Test
  fun `a client that does not support Char Group gets nothing`() {
    val f = Fixture()
    val (ann, annSink) = f.gmcpPlayer("Ann", sink = GmcpSink(setOf("Room")))
    val (ben, benSink) = f.gmcpPlayer("Ben", sink = GmcpSink(setOf("Room")))
    f.w.groupAdd(ben, ann)
    f.run(2)
    assertFalse("Char.Group" in annSink.packages())
    assertFalse("Char.Group" in benSink.packages())
  }
}
