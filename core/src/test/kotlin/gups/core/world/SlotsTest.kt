package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
import kotlin.test.assertContains
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class SlotsTest(private val fs: FixtureSchema) {
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

  private val ringLabel = Label("//t:test_ring")
  private val specificRingLabel = Label("//t:test_ring_r")
  private val swordLabel = Label("//t:test_sword")
  private val shieldLabel = Label("//t:test_shield")
  private val greatswordLabel = Label("//t:test_greatsword")

  private val testProtos =
    listOf(
      ItemProto(ringLabel, "a ring", listOf("ring"), wear = "ring"),
      ItemProto(specificRingLabel, "a special ring", listOf("special"), wear = "finger_r"),
      ItemProto(swordLabel, "a sword", listOf("sword"), wear = "hand"),
      ItemProto(shieldLabel, "a shield", listOf("shield"), wear = "offhand"),
      ItemProto(greatswordLabel, "a greatsword", listOf("greatsword"), wear = "hand", slotCost = 2),
    )

  class TestSlotRules(
    fs: FixtureSchema = FixtureSchemas.current,
    var customSlots: List<String> = SLOTS,
    var families: Map<String, List<String>> =
      mapOf("ring" to listOf("finger_l", "finger_r"), "hand" to listOf("wield", "offhand")),
  ) : FixtureRules(fs) {
    var actorSlotsCalls = 0
    var actorSlotsOverride: ((RulesContext) -> List<String>)? = null
    var slotCostOverride: ((RulesContext) -> Int)? = null

    override fun equipmentSlots(): List<String> = customSlots

    override fun slotFamilies(): Map<String, List<String>> = families

    override fun actorSlots(c: RulesContext): List<String> {
      actorSlotsCalls++
      return actorSlotsOverride?.invoke(c) ?: equipmentSlots()
    }

    override fun slotCost(c: RulesContext): Int = slotCostOverride?.invoke(c) ?: super.slotCost(c)
  }

  private fun fixture(rules: TestSlotRules = TestSlotRules(fs)): Fixture =
    Fixture(rules = rules, protos = Prototypes(T.rooms, T.mobs, T.items + testProtos, T.skills))

  private fun Fixture.carry(p: gups.core.model.Player, label: Label) =
    w.spawnItem(label, ItemLocation.Carried(p.id))

  private fun Fixture.doLine(p: gups.core.model.Player, line: String): String {
    input(p, line)
    run(3)
    return out(p)
  }

  @Test
  fun `two rings fill finger_l then finger_r, a third is refused with WEAR_NO_ROOM, and freeing a slot allows wearing`() {
    val f = fixture()
    val p = f.player("Ann")
    val r1 = f.carry(p, ringLabel)
    val r2 = f.carry(p, ringLabel)
    val r3 = f.carry(p, ringLabel)

    assertContains(f.doLine(p, "wear ring"), "You wear a ring.")
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), r1.location)

    assertContains(f.doLine(p, "wear ring"), "You wear a ring.")
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), r2.location)

    val refused = f.doLine(p, "wear ring")
    assertContains(refused, "There's no room for a ring. Remove something first.")
    assertEquals(ItemLocation.Carried(p.id), r3.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), r1.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), r2.location)

    assertContains(f.doLine(p, "remove ring"), "You stop using a ring.")
    assertEquals(ItemLocation.Carried(p.id), r1.location)

    assertContains(f.doLine(p, "wear ring"), "You wear a ring.")
    val equipped = p.equipped("finger_l")
    assertTrue(equipped === r1 || equipped === r3)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), r2.location)
    val remainingCarried = if (equipped === r1) r3 else r1
    assertEquals(ItemLocation.Carried(p.id), remainingCarried.location)
  }

  @Test
  fun `four ring slots fill four rings and refuse a fifth`() {
    val fourSlots = FixtureRules.SLOTS + listOf("finger_3", "finger_4")
    val rules =
      TestSlotRules(
        fs,
        customSlots = fourSlots,
        families =
          mapOf(
            "ring" to listOf("finger_l", "finger_r", "finger_3", "finger_4"),
            "hand" to listOf("wield", "offhand"),
          ),
      )
    val f = fixture(rules)
    val p = f.player("Ann")
    val rings = (1..5).map { f.carry(p, ringLabel) }

    for (i in 0..3) {
      assertContains(f.doLine(p, "wear ring"), "You wear a ring.")
      assertTrue(rings[i].location is ItemLocation.Equipped)
    }

    val out = f.doLine(p, "wear ring")
    assertContains(out, "There's no room for a ring. Remove something first.")
    assertEquals(ItemLocation.Carried(p.id), rings[4].location)
  }

  @Test
  fun `a ring with wear = finger_r swaps out a worn finger_r ring`() {
    val f = fixture()
    val p = f.player("Ann")
    val r1 = f.carry(p, ringLabel)
    val r2 = f.carry(p, ringLabel)
    val rSpec = f.carry(p, specificRingLabel)

    f.doLine(p, "wear ring")
    f.doLine(p, "wear ring")
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), r1.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), r2.location)

    val out = f.doLine(p, "wear special")
    assertContains(out, "You stop using a ring.")
    assertContains(out, "You wear a special ring.")
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), r1.location)
    assertEquals(ItemLocation.Carried(p.id), r2.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), rSpec.location)
  }

  @Test
  fun `two-handed item swaps lone sword, but is refused naming both when sword and shield are worn`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword = f.carry(p, swordLabel)
    val gs = f.carry(p, greatswordLabel)

    f.doLine(p, "wear sword")
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)

    val swapOut = f.doLine(p, "wear greatsword")
    assertContains(swapOut, "You stop using a sword.")
    assertContains(swapOut, "You wield a greatsword.")
    assertEquals(ItemLocation.Carried(p.id), sword.location)
    assertEquals(ItemLocation.Equipped(p.id, "wield"), gs.location)

    // Now put sword and shield on together
    f.doLine(p, "remove greatsword")
    f.doLine(p, "wear sword")
    val shield = f.carry(p, shieldLabel)
    f.doLine(p, "wear shield")
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)
    assertEquals(ItemLocation.Equipped(p.id, "offhand"), shield.location)

    val refused = f.doLine(p, "wear greatsword")
    assertContains(refused, "You'd need to remove a sword, a shield first.")
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)
    assertEquals(ItemLocation.Equipped(p.id, "offhand"), shield.location)
    assertEquals(ItemLocation.Carried(p.id), gs.location)
  }

  @Test
  fun `wearing shield with greatsword worn swaps out the greatsword`() {
    val f = fixture()
    val p = f.player("Ann")
    val gs = f.carry(p, greatswordLabel)
    val shield = f.carry(p, shieldLabel)

    f.doLine(p, "wear greatsword")
    assertEquals(ItemLocation.Equipped(p.id, "wield"), gs.location)

    val out = f.doLine(p, "wear shield")
    assertContains(out, "You stop using a greatsword.")
    assertEquals(ItemLocation.Carried(p.id), gs.location)
    assertEquals(ItemLocation.Equipped(p.id, "offhand"), shield.location)
  }

  @Test
  fun `slotCost override lets high strength actor wear greatsword and shield together`() {
    val rules = TestSlotRules(fs)
    rules.slotCostOverride = { c ->
      if (c.item?.protoLabel == greatswordLabel && c.requireActor().stat(fs.strength) >= 18) 1
      else c.item?.proto?.slotCost ?: 1
    }
    val f = fixture(rules)
    val p = f.player("Ann")
    p.baseStats[fs.strength] = 18

    val gs = f.carry(p, greatswordLabel)
    val shield = f.carry(p, shieldLabel)

    f.doLine(p, "wear greatsword")
    val out = f.doLine(p, "wear shield")
    assertFalse(out.contains("You stop using"))
    assertEquals(ItemLocation.Equipped(p.id, "wield"), gs.location)
    assertEquals(ItemLocation.Equipped(p.id, "offhand"), shield.location)
  }

  @Test
  fun `four hand slots let two two-handed items be wielded, and stray slot in override is dropped`() {
    val fourSlots = FixtureRules.SLOTS + listOf("hand_3", "hand_4")
    val rules =
      TestSlotRules(
        fs,
        customSlots = fourSlots,
        families =
          mapOf(
            "ring" to listOf("finger_l", "finger_r"),
            "hand" to listOf("wield", "offhand", "hand_3", "hand_4"),
          ),
      )
    rules.actorSlotsOverride = { listOf("wield", "offhand", "hand_3", "hand_4", "stray_slot_xyz") }
    val f = fixture(rules)
    val p = f.player("Ann")

    val actorSlots = f.w.actorSlots(p)
    assertTrue("stray_slot_xyz" !in actorSlots)
    assertEquals(listOf("wield", "offhand", "hand_3", "hand_4"), actorSlots)

    val gs1 = f.carry(p, greatswordLabel)
    val gs2 = f.carry(p, greatswordLabel)

    f.doLine(p, "wear greatsword")
    val out = f.doLine(p, "wear greatsword")
    assertFalse(out.contains("You stop using"))
    assertEquals(ItemLocation.Equipped(p.id, "wield"), gs1.location)
    assertEquals(ItemLocation.Equipped(p.id, "offhand"), gs2.location)
  }

  @Test
  fun `an item whose cost exceeds its family capacity cannot be worn`() {
    val rules = TestSlotRules(fs)
    rules.actorSlotsOverride = { listOf("wield") } // only 1 hand slot, greatsword costs 2
    val f = fixture(rules)
    val p = f.player("Ann")
    val gs = f.carry(p, greatswordLabel)

    val out = f.doLine(p, "wear greatsword")
    assertContains(out, "You can't wear a greatsword.")
    assertEquals(ItemLocation.Carried(p.id), gs.location)
  }

  @Test
  fun `actorSlots is cached and asked again after forgetActorSlots, an affect, and reload`() {
    val rules = TestSlotRules(fs)
    var answer = listOf("head")
    rules.actorSlotsOverride = { answer }
    val f = fixture(rules)
    val p = f.player("Ann")

    // The rules' answer changes before each step; the old one is served until the step forgets it.
    // Other engine paths may ask again after the step, so the test checks answers, not call counts.
    fun refreshedBy(next: String, step: () -> Unit) {
      val before = f.w.actorSlots(p)
      answer = listOf(next)
      assertEquals(before, f.w.actorSlots(p), "cached before the step")
      step()
      assertEquals(listOf(next), f.w.actorSlots(p))
    }

    val calls = rules.actorSlotsCalls
    f.w.actorSlots(p)
    f.w.actorSlots(p)
    assertTrue(rules.actorSlotsCalls - calls <= 1)

    refreshedBy("wield") { f.w.forgetActorSlots(p) }
    refreshedBy("finger_l") { f.w.applyAffect(p, "buff", 10) }
    refreshedBy("finger_r") { f.w.removeAffect(p, "buff") }
    refreshedBy("head") { f.w.applyPrototypes(Prototypes(T.rooms, T.mobs, T.items, T.skills)) }
  }
}
