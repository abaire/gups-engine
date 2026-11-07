package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.model.Trait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaseValuesTest {
  private val fs = FixtureSchemas.FANTASY
  private val heavyAnvil =
    ItemProto(Label("//t:heavy_anvil"), "an anvil", listOf("anvil"), weight = 150)

  private fun fixture(extraItems: List<ItemProto> = emptyList()) =
    Fixture(fs = fs, protos = Prototypes(T.rooms, T.mobs(fs), T.items + extraItems, T.skills(fs)))

  @Test
  fun `setting a stat changes baseStat and effective stat with trait delta`() {
    val f = fixture()
    f.w.traits = mapOf("strong" to Trait("strong", statDeltas = mapOf(fs.strength to 3)))
    val p = f.player("Ann")
    f.w.grantTrait(p, "strong")
    val initialBase = p.baseStat(fs.strength)
    assertEquals(initialBase + 3, p.stat(fs.strength))

    p.dirty = false
    f.w.setBaseStat(p, fs.strength, 20)
    assertEquals(20, p.baseStat(fs.strength))
    assertEquals(23, p.stat(fs.strength))
    assertTrue(p.dirty)
  }

  @Test
  fun `lowering max vital below current caps current and refreshes condition`() {
    val f = fixture()
    val p = f.player("Ann", hp = 50)
    assertEquals(50, p.current(fs.health))
    assertEquals(50, p.max(fs.health))
    assertEquals(Condition.OK, p.condition)

    p.dirty = false
    f.w.setBaseMaxVital(p, fs.health, 0)
    assertEquals(0, p.baseMax(fs.health))
    assertEquals(0, p.max(fs.health))
    assertEquals(0, p.current(fs.health))
    assertEquals(Condition.DEAD, p.condition)
    assertTrue(p.dirty)
  }

  @Test
  fun `raising max vital leaves current alone`() {
    val f = fixture()
    val p = f.player("Ann", hp = 30)
    assertEquals(30, p.current(fs.health))
    assertEquals(30, p.max(fs.health))

    f.w.setBaseMaxVital(p, fs.health, 60)
    assertEquals(60, p.baseMax(fs.health))
    assertEquals(60, p.max(fs.health))
    assertEquals(30, p.current(fs.health))
  }

  @Test
  fun `setting str changes encumbrance level of heavily loaded actor`() {
    val f = fixture(extraItems = listOf(heavyAnvil))
    val p = f.player("Ann")
    f.w.setBaseStat(p, fs.strength, 10)
    f.w.spawnItem(heavyAnvil.label, ItemLocation.Carried(p.id))
    // carryLimit = 10 * 10 = 100, carried = 150 -> encumbranceLevel = 1
    assertEquals(1, p.encumbranceLevel)

    f.w.setBaseStat(p, fs.strength, 20)
    // carryLimit = 20 * 10 = 200, carried = 150 -> encumbranceLevel = 0
    assertEquals(0, p.encumbranceLevel)
  }

  @Test
  fun `setting base resist updates base and effective resist and marks actor dirty`() {
    val f = fixture()
    val p = f.player("Ann")
    p.dirty = false
    f.w.setBaseResist(p, DamageType(fs.magic), 25)
    assertEquals(25, p.baseResist(fs.magic))
    assertEquals(25, p.effectiveResist(DamageType(fs.magic)))
    assertTrue(p.dirty)
  }

  @Test
  fun `works for a mob and marks dirty`() {
    val f = fixture()
    val mob = f.mob(T.GOBLIN)
    mob.dirty = false
    f.w.setBaseStat(mob, fs.strength, 18)
    assertEquals(18, mob.baseStat(fs.strength))
    assertEquals(18, mob.stat(fs.strength))
    assertTrue(mob.dirty)

    mob.dirty = false
    f.w.setBaseMaxVital(mob, fs.health, 40)
    assertEquals(40, mob.baseMax(fs.health))
    assertTrue(mob.dirty)

    mob.dirty = false
    f.w.setBaseResist(mob, DamageType(fs.magic), 15)
    assertEquals(15, mob.baseResist(fs.magic))
    assertTrue(mob.dirty)
  }
}
