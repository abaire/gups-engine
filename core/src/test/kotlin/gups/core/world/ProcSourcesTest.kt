package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.ItemAffect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProcSourcesTest {
  private val fs = FixtureSchemas.FANTASY
  private val testSword = Label("//t:test_sword")
  private val charm = Label("//t:charm")
  private val potion = Label("//t:potion")

  private fun protos(extra: List<ItemProto> = emptyList()) =
    Prototypes(T.rooms, T.mobs(fs), T.items + extra, T.skills(fs))

  private fun fixture(extra: List<ItemProto> = emptyList()) =
    Fixture(fs = fs, protos = protos(extra))

  @Test
  fun `proc validation rejects invalid arguments`() {
    val outcome = ProcOutcome(weight = 0.5)

    assertFailsWith<IllegalArgumentException> { Proc(ProcTrigger.HIT, -0.1, listOf(outcome)) }
    assertFailsWith<IllegalArgumentException> { Proc(ProcTrigger.HIT, 1.1, listOf(outcome)) }
    assertFailsWith<IllegalArgumentException> { Proc(ProcTrigger.HIT, 0.5, emptyList()) }
    assertFailsWith<IllegalArgumentException> {
      Proc(ProcTrigger.HIT, 0.5, listOf(ProcOutcome(weight = 0.6), ProcOutcome(weight = 0.5)))
    }

    assertFailsWith<IllegalArgumentException> { ProcOutcome(weight = -0.1) }
    assertFailsWith<IllegalArgumentException> { ProcOutcome(weight = 1.1) }
    assertFailsWith<IllegalArgumentException> {
      ProcOutcome(
        weight = 0.5,
        skill = Label("//s:magic"),
        effect = Effect.Damage(Dice(1, 6), DamageType("slash")),
      )
    }
  }

  @Test
  fun `worn item procs, prototype item affects, and carried affects appear in procSteps`() {
    val pWorn =
      Proc(ProcTrigger.HIT, 0.5, listOf(ProcOutcome(weight = 1.0, target = ProcTarget.ENEMY)))
    val pItemAff =
      Proc(ProcTrigger.STRUCK, 0.3, listOf(ProcOutcome(weight = 1.0, target = ProcTarget.SELF)))
    val pCarried =
      Proc(ProcTrigger.AVOID, 0.2, listOf(ProcOutcome(weight = 1.0, target = ProcTarget.ENEMY)))

    val swordProto =
      ItemProto(
        testSword,
        "a sword",
        listOf("sword"),
        wear = "wield",
        procs = listOf(pWorn),
        affects = listOf(ItemAffect("glow", applies = AffectScope.WORN, procs = listOf(pItemAff))),
      )
    val charmProto =
      ItemProto(
        charm,
        "a charm",
        listOf("charm"),
        affects =
          listOf(ItemAffect("ward", applies = AffectScope.CARRIED, procs = listOf(pCarried))),
      )

    val f = fixture(extra = listOf(swordProto, charmProto))
    val p = f.player("Ann")

    f.w.spawnItem(testSword, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(charm, ItemLocation.Carried(p.id))

    val steps = p.heldEffects.procSteps
    assertEquals(3, steps.size)

    assertEquals("item:test_sword", steps[0].source)
    assertEquals(listOf(pWorn), steps[0].procs)

    assertEquals("item:test_sword:glow", steps[1].source)
    assertEquals(listOf(pItemAff), steps[1].procs)

    assertEquals("item:charm:ward", steps[2].source)
    assertEquals(listOf(pCarried), steps[2].procs)
  }

  @Test
  fun `an item in an inactive slot contributes no worn procs`() {
    val pWorn = Proc(ProcTrigger.HIT, 0.5, listOf(ProcOutcome(weight = 1.0)))
    val swordProto =
      ItemProto(testSword, "a sword", listOf("sword"), wear = "wield", procs = listOf(pWorn))
    val f = fixture(extra = listOf(swordProto))
    val p = f.player("Ann")
    f.w.spawnItem(testSword, ItemLocation.Equipped(p.id, "wield"))

    assertEquals(1, p.heldEffects.procSteps.size)

    p.inactiveSlots = setOf("wield")
    f.w.refreshHeld(p)

    assertEquals(0, p.heldEffects.procSteps.size)
  }

  @Test
  fun `applyAffect and applyEffect pass procs through to Affect`() {
    val f = fixture()
    val p = f.player("Ann")
    val proc1 = Proc(ProcTrigger.HIT, 0.4, listOf(ProcOutcome(weight = 1.0)))

    val aff1 = f.w.applyAffect(p, "buff", duration = 10, procs = listOf(proc1))
    assertEquals(listOf(proc1), aff1?.procs)
    assertEquals(listOf(proc1), p.affects.first { it.name == "buff" }.procs)

    val proc2 = Proc(ProcTrigger.STRUCK, 0.8, listOf(ProcOutcome(weight = 1.0)))
    val applyEffect = Effect.ApplyAffect(name = "shield", duration = 5, procs = listOf(proc2))
    f.w.applyEffect(applyEffect, p, p)
    assertEquals(listOf(proc2), p.affects.first { it.name == "shield" }.procs)
  }

  @Test
  fun `splitting a stack keeps an instance affect's procs`() {
    val stackProto = ItemProto(potion, "a potion", listOf("potion"), stackable = true)
    val f = fixture(extra = listOf(stackProto))
    val p = f.player("Ann")
    val stack = f.w.spawnItem(potion, ItemLocation.Carried(p.id), count = 5)

    val proc = Proc(ProcTrigger.AVOID, 0.5, listOf(ProcOutcome(weight = 1.0)))
    f.w.applyItemAffect(stack, "enchanted", duration = null, procs = listOf(proc))

    val part = f.w.moveItem(stack, ItemLocation.Carried(p.id), count = 1)
    val partAffect = part.affects.firstOrNull { it.name == "enchanted" }
    assertEquals(listOf(proc), partAffect?.procs)
  }
}
