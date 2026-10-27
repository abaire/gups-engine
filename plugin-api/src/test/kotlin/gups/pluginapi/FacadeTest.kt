package gups.pluginapi

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.rules.StartingStats
import gups.core.world.World
import gups.core.world.destroy
import gups.core.world.quit
import gups.core.world.spawnItem
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.ItemFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeTest {
  private val w: World = world()
  private val f = WorldFacade(w, "weather") {}
  private val glade = f.room("//zones/z:a")!!
  private val cave = f.room("//zones/z:b")!!
  private val ann =
    ActorFacade(
      w.spawnPlayer(
        "Ann",
        Label("//zones/z:a"),
        overrides = StartingStats(max = mapOf("hp" to 20), current = mapOf("hp" to 20)),
      ),
      w,
    )
  private val wolf =
    ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)

  @Test
  fun `reads`() {
    assertEquals(listOf("//zones/z:a", "//zones/z:b"), f.rooms().map { it.label })
    assertNull(f.room("//zones/z:nope"))
    assertNull(f.room("not a label"))
    assertEquals(listOf("forest", "outdoors", "underground"), f.regions())
    assertEquals("large", glade.size)
    assertEquals(setOf("dead_end_ok"), cave.flags)
    assertEquals(mapOf("north" to "//zones/z:b"), glade.exits)
    assertEquals(listOf("forest", "outdoors"), glade.regions)
    assertEquals(setOf("Ann", "a wolf"), glade.actors.map { it.name }.toSet())
    assertEquals(7L, f.seed)
    assertEquals(w.pulse, f.pulse)
    assertTrue(f.rng.int(1, 6) in 1..6)
    f.rng.chance(50)
    assertTrue(ann.isPlayer)
    assertNull(ann.label)
    assertEquals("//zones/z:wolf", wolf.label)
    assertEquals(glade, wolf.room)
    val stick =
      ItemFacade(
        w.spawnItem(
          Label("//zones/z:stick"),
          ItemLocation.InRoom(w.room(Label("//zones/z:a")).id, 0),
        )
      )
    assertEquals(listOf("stick", "wood"), stick.keywords)
    assertEquals(listOf("a stick"), glade.items.map { it.name })
  }

  @Test
  fun `verbs`() {
    f.send(ann, "Hello.")
    f.echo(glade, "Thunder.", exclude = listOf(wolf))
    f.damage(ann, 5, "fire", "hp", source = wolf)
    assertEquals(15, ann.vital("hp"))
    f.heal(ann, 3, "hp")
    assertEquals(18, ann.vital("hp"))
    assertFailsWith<IllegalArgumentException> { f.damage(ann, -1, "fire", "hp") }
    f.applyAffect(ann, "soaked", 10, statDeltas = mapOf("str" to -1), flags = setOf("wet"))
    assertTrue(ann.hasAffect("soaked"))
    assertEquals(listOf("soaked"), ann.affects)
    assertTrue(f.removeAffect(ann, "soaked"))
    assertFalse(f.removeAffect(ann, "soaked"))
    f.teleport(ann, cave)
    assertEquals(cave, ann.room)
    assertEquals(20, ann.maxVital("hp"))
    assertEquals(1, ann.level)
  }

  @Test
  fun `verbs refuse entities that have left the world`() {
    val stick =
      ItemFacade(
        w.spawnItem(
          Label("//zones/z:stick"),
          ItemLocation.InRoom(w.room(Label("//zones/z:a")).id, 0),
        )
      )
    w.quit(ann.actor as Player)
    w.destroy(stick.item)
    assertFalse(ann.actor.inWorld)
    assertEquals(
      "teleport(actor): Ann is no longer in the world",
      assertFailsWith<IllegalArgumentException> { f.teleport(ann, cave) }.message,
    )
    assertFailsWith<IllegalArgumentException> { f.echo(glade, "Thunder.", exclude = listOf(ann)) }
    assertFailsWith<IllegalArgumentException> { f.setAttr(ann, "x", 1) }
    assertEquals(
      "get_attr(entity): ${stick.name} is no longer in the world",
      assertFailsWith<IllegalArgumentException> { f.getAttr(stick, "x") }.message,
    )
  }

  @Test
  fun `attributes live in the plugin's namespace`() {
    f.setAttr(glade, "visits", 2)
    f.setAttr(glade, "seen", listOf("Ann", true, mapOf("n" to 1L)))
    assertEquals(2L, f.getAttr(glade, "visits"))
    assertEquals(2L, f.getAttr(glade, "weather:visits"))
    assertEquals(listOf("Ann", true, mapOf("n" to 1L)), f.getAttr(glade, "seen"))
    assertEquals("dflt", f.getAttr(glade, "missing", "dflt"))
    assertTrue("weather:visits" in w.room(Label("//zones/z:a")).customAttributes)
    f.delAttr(glade, "visits")
    assertNull(f.getAttr(glade, "visits"))
    f.setAttr(glade, "seen", null)
    assertNull(f.getAttr(glade, "seen"))
    assertFailsWith<IllegalArgumentException> { f.setAttr(glade, "other:x", 1) }
    assertFailsWith<IllegalArgumentException> { f.setAttr(glade, "x", 1.5) }
    assertFailsWith<IllegalArgumentException> { f.setAttr(glade, "bad key", 1) }
    val raw = WorldFacade(w, null) {}
    assertFailsWith<IllegalArgumentException> { raw.getAttr(glade, "x") }
    f.setAttr(glade, "y", 1)
    assertEquals(1L, raw.getAttr(glade, "weather:y"))
  }

  @Test
  fun `diagnose and log`() {
    val lines = mutableListOf<String>()
    WorldFacade(w, "weather") { lines += it }.log("hi")
    f.diagnose("odd")
    assertEquals(listOf("plugin 'weather': hi"), lines)
    assertTrue(w.diagnostics.any { it.kind == "plugin" && it.message == "plugin 'weather': odd" })
  }

  @Test
  fun `actor reads go by schema name`() {
    assertEquals(10, ann.stat("dex"))
    assertEquals(10, ann.baseStat("dex"))
    f.applyAffect(ann, "quick", 10, statDeltas = mapOf("dex" to 3))
    assertEquals(13, ann.stat("dex"))
    assertEquals(10, ann.baseStat("dex"))
    assertEquals(20, ann.vital("hp"))
    assertEquals(20, ann.maxVital("mana"))
    assertEquals(0, ann.resist("fire"))
  }

  @Test
  fun `an undeclared name throws with a suggestion`() {
    assertEquals(
      "unknown stat 'dxe' (did you mean 'dex'?)",
      assertFailsWith<IllegalArgumentException> { ann.stat("dxe") }.message,
    )
    assertFailsWith<IllegalArgumentException> { ann.vital("health") }
    assertFailsWith<IllegalArgumentException> { ann.resist("acid") }
    assertEquals(
      "damage(vital): unknown vital 'hpp' (did you mean 'hp'?)",
      assertFailsWith<IllegalArgumentException> { f.damage(ann, 1, "fire", "hpp") }.message,
    )
    assertContains(
      assertFailsWith<IllegalArgumentException> { f.damage(ann, 1, "fier", "hp") }.message!!,
      "unknown damage type 'fier' (did you mean 'fire'?)",
    )
    assertFailsWith<IllegalArgumentException> { f.heal(ann, 1, "mnaa") }
    assertContains(
      assertFailsWith<IllegalArgumentException> {
          f.applyAffect(ann, "weak", 5, statDeltas = mapOf("strength" to -1))
        }
        .message!!,
      "unknown stat 'strength'",
    )
  }

  @Test
  fun `the schema lists names and labels in declaration order`() {
    val s = f.schema
    assertEquals(listOf("str", "dex", "con", "int", "wis", "armor"), s.stats.map { it.name })
    assertEquals(PluginNamed("hp", "Hit points"), s.vitals.first())
    assertEquals(listOf("hp", "mana", "move"), s.vitals.map { it.name })
    assertEquals(listOf("bludgeon", "slash", "pierce", "fire"), s.damageTypes)
  }
}
