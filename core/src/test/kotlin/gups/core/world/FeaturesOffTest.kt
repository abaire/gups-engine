package gups.core.world

import gups.core.Role
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeaturesOffTest {
  private fun without(vararg off: Feature): Features =
    Features(
      Feature.entries.toSet() -
        off.toSet() -
        Feature.entries.filter { f -> f.requires.any { it in off } }.toSet()
    )

  private fun Fixture.say(p: gups.core.model.Player, line: String): String {
    input(p, line)
    run(4)
    return out(p)
  }

  private val commandsOf =
    mapOf(
      Feature.COMBAT to listOf("kill", "flee", "advance", "retreat"),
      Feature.TRACK to listOf("advance", "retreat"),
      Feature.SKILLS to listOf("cast"),
    )

  @Test
  fun `the commands of a feature that is off give Huh and are missing from help`() {
    for ((feature, commands) in commandsOf) {
      val f = Fixture(features = without(feature))
      val p = f.player("Ann")
      f.mob()
      val help = f.say(p, "help")
      for (c in commands) {
        assertTrue(f.say(p, "$c goblin").contains("Huh?"), "$c without $feature")
        assertFalse(
          Regex("\\b$c\\b").containsMatchIn(help),
          "help lists $c without $feature: $help",
        )
      }
    }
  }

  @Test
  fun `the commands of a feature that is on are in help`() {
    val f = Fixture()
    val p = f.player("Ann")
    val help = f.say(p, "help")
    for (c in listOf("kill", "flee", "cast", "advance", "retreat")) assertTrue(c in help, c)
  }

  @Test
  fun `where is only a command with the track`() {
    val f = Fixture(features = without(Feature.TRACK))
    val b = f.player("Bob")
    b.role = Role.BUILDER
    assertTrue(f.say(b, "@where").contains("Huh?"))
    val g = Fixture()
    val c = g.player("Cy")
    c.role = Role.BUILDER
    assertFalse(g.say(c, "@where").contains("Huh?"))
  }

  @Test
  fun `without the track everything sits at index 0 and look shows no bands`() {
    val f = Fixture(features = without(Feature.TRACK))
    val ann = f.player("Ann", index = 2)
    val gob = f.mob(T.GOBLIN, index = 2)
    f.w.spawnItem(T.SWORD, ItemLocation.InRoom(ann.room.id, 0))
    assertEquals(0, ann.trackIndex)
    assertEquals(0, gob.trackIndex)
    assertTrue(f.w.room(T.HALL).items.all { it.groundIndex == 0 })
    val look = f.say(ann, "look")
    assertTrue("Also here: a goblin." in look, look)
    assertTrue("On the ground: a sword." in look, look)
    for (word in listOf("Beside you", "Nearby", "Far off")) assertFalse(word in look, look)
  }

  @Test
  fun `a skill with no engagement lowers vitals and credits its caster with the kill`() {
    val f = Fixture(features = without(Feature.COMBAT))
    val ann = f.player("Ann")
    ann.skills[T.ZAP] = 100
    val gob = f.mob()
    f.w.changeVital(gob, f.fs.health, -(gob.current(f.fs.health) - 3))
    f.say(ann, "cast zap goblin")
    assertFalse(gob.isEngaged)
    assertFalse(ann.isEngaged)
    f.run(2)
    assertFalse(gob.inWorld, "the zap killed the goblin")
    assertTrue(ann.xp > 0, "the last actor to damage the goblin is credited with the kill")
  }

  @Test
  fun `quit is never refused for fighting without combat`() {
    val f = Fixture(features = without(Feature.COMBAT))
    val ann = f.player("Ann")
    ann.fighting += f.mob().id
    assertTrue(ann.isEngaged)
    f.input(ann, "quit")
    assertFalse(ann.inWorld)
    val g = Fixture()
    val bea = g.player("Bea")
    g.w.startCombat(bea, g.mob())
    assertTrue(g.say(bea, "quit").contains("No way!"))
  }

  @Test
  fun `without leveling a kill earns nothing and score shows no level`() {
    val f = Fixture(features = without(Feature.LEVELING))
    val ann = f.player("Ann")
    val gob = f.mob()
    f.w.startCombat(ann, gob)
    f.run(80)
    assertFalse(gob.inWorld)
    assertEquals(0L, ann.xp)
    assertEquals(1, ann.level)
    assertFalse("Level" in f.say(ann, "score"))
    f.w.awardXp(ann, 10_000)
    assertEquals(0L, ann.xp)
    assertTrue("Level" in Fixture().let { g -> g.say(g.player("Bea"), "score") })
  }

  @Test
  fun `GMCP drops the fields and packages of a feature that is off`() {
    fun status(features: Features): Map<*, *> {
      val f = Fixture(features = features)
      return f.w.gmcpPayload(f.player("Ann"), "Char.Status") as Map<*, *>
    }
    val all = status(Features.ALL)
    assertEquals(
      setOf("name", "level", "xp", "xp_next", "wait", "casting", "initiative", "fighting"),
      all.keys,
    )
    assertEquals(
      setOf("name", "wait", "casting", "initiative", "fighting"),
      status(without(Feature.LEVELING)).keys,
    )
    assertEquals(
      setOf("name", "level", "xp", "xp_next", "wait", "casting"),
      status(without(Feature.COMBAT)).keys,
    )
    assertEquals(
      setOf("name", "level", "xp", "xp_next", "wait", "initiative", "fighting"),
      status(without(Feature.SKILLS)).keys,
    )
  }

  @Test
  fun `Char Schema lists the enabled features and Room Bands is not sent without the track`() {
    val f = Fixture(features = without(Feature.TRACK, Feature.SKILLS))
    val p = f.player("Ann")
    val schema = f.w.gmcpPayload(p, "Char.Schema") as Map<*, *>
    assertEquals(listOf("combat", "leveling"), schema["features"])
    val sink = f.sink(p)
    assertNotNull(sink)
    f.run(2)
    assertNull(f.w.sessions.values.firstOrNull()?.gmcpLast?.get("Room.Bands"))
  }

  @Test
  fun `invariants flag a cast or track index a world without the feature can't have`() {
    val f = Fixture(features = without(Feature.TRACK))
    val ann = f.player("Ann")
    assertTrue(f.w.checkInvariants().isEmpty())
    f.w.placeActor(ann, ann.room, 1)
    ann.trackIndex = 1
    assertTrue(f.w.checkInvariants().any { "without the track" in it })
  }

  @Test
  fun `a raw damage verb still kills through condition without combat`() {
    val f = Fixture(features = Features(emptySet()))
    val ann = f.player("Ann")
    f.w.damage(ann, mapOf(f.fs.health to 1000), DamageType(f.fs.unarmed))
    f.run(2)
    assertTrue(ann.inWorld)
    assertTrue(ann.current(f.fs.health) > 0)
  }
}
