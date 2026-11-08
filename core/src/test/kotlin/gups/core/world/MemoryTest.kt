package gups.core.world

import gups.core.Label
import gups.core.model.Channel
import gups.core.model.Direction
import gups.core.model.ExitProto
import gups.core.model.MEMORY_MAX_ENTRIES
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryTest {
  private val start = Label("//t:start")
  private val yard = Label("//t:yard")

  private fun world(
    mobs: List<MobProto>,
    memoryPulses: Long = 100,
    features: Features = Features.ALL,
  ): World {
    val protos =
      Prototypes(
        rooms =
          listOf(
            RoomProto(start, "Start", exits = listOf(ExitProto(Direction.NORTH, yard))),
            RoomProto(yard, "Yard", exits = listOf(ExitProto(Direction.SOUTH, start))),
          ),
        mobs = mobs,
      )
    return Fixture(protos = protos, features = features).w.also { it.memoryPulses = memoryPulses }
  }

  @Test
  fun `with window 0 nothing is recorded`() {
    val orc = MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"))
    val w = world(listOf(orc), memoryPulses = 0)
    val p = w.spawnPlayer("Ann", start)
    val m = w.spawnMob(orc.label, w.room(start))
    w.startCombat(p, m)
    w.recordDamage(m, p, 10)
    assertTrue(w.memoriesOf(m).isEmpty())
  }

  @Test
  fun `attack on fresh engagement and no attack for coupled owner engagement`() {
    val orc =
      MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"), vitals = mapOf("hp" to 200))
    val petProto =
      MobProto(Label("//t:pet"), "a pet", keywords = listOf("pet"), vitals = mapOf("hp" to 200))
    val w = world(listOf(orc, petProto), memoryPulses = 100)
    val owner = w.spawnPlayer("Owner", start)
    val pet = w.spawnMob(petProto.label, w.room(start))
    val m = w.spawnMob(orc.label, w.room(start))
    w.setOwner(pet, owner)

    w.runPulses(5)
    // Pet attacks orc: orc gets an attack memory for pet, and owner is coupled into combat without
    // giving orc an attack memory for owner.
    w.startCombat(pet, m)
    val attacks = w.memoriesOf(m, event = "attack")
    assertEquals(1, attacks.size)
    assertEquals(pet.id, attacks.single().key.subject)
    assertEquals(5L, attacks.single().pulse)

    // Repeat startCombat while already engaged does not add another attack memory.
    w.runPulses(3)
    w.startCombat(pet, m)
    assertEquals(1, w.memoriesOf(m, event = "attack").single().count)
  }

  @Test
  fun `damage accumulates amount and count and merging keeps first_pulse`() {
    val orc = MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"))
    val w = world(listOf(orc), memoryPulses = 100)
    val p = w.spawnPlayer("Ann", start)
    val m = w.spawnMob(orc.label, w.room(start))

    w.runPulses(4)
    w.recordDamage(m, p, 7)
    w.runPulses(6)
    w.recordDamage(m, p, 12)

    val mem = w.memoriesOf(m, subject = p, event = "damage").single()
    assertEquals(4L, mem.firstPulse)
    assertEquals(10L, mem.pulse)
    assertEquals(2, mem.count)
    assertEquals(19L, mem.amount)
    assertNull(mem.channel)
  }

  @Test
  fun `expiry after window is measured from latest pulse`() {
    val orc = MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"))
    val w = world(listOf(orc), memoryPulses = 10)
    val p = w.spawnPlayer("Ann", start)
    val m = w.spawnMob(orc.label, w.room(start))

    w.runPulses(5)
    w.remember(m, p, "taunt", channel = Channel.SOUND)
    w.runPulses(8)
    // At pulse 13 (within 10 of pulse 5), merge updates pulse to 13 and keeps firstPulse = 5.
    w.remember(m, p, "taunt")
    assertEquals(5L, w.memoriesOf(m, event = "taunt").single().firstPulse)
    assertEquals(13L, w.memoriesOf(m, event = "taunt").single().pulse)
    assertEquals(Channel.SOUND, w.memoriesOf(m, event = "taunt").single().channel)

    // At pulse 23 (13 + 10), still within window.
    w.runPulses(10)
    assertEquals(1, w.memoriesOf(m, event = "taunt").size)

    // At pulse 24 (more than 10 pulses since pulse 13), forgotten.
    w.runPulses(1)
    assertTrue(w.memoriesOf(m, event = "taunt").isEmpty())
  }

  @Test
  fun `cap of 256 evicts oldest pulse first`() {
    val orc = MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"))
    val w = world(listOf(orc), memoryPulses = 10_000)
    val p = w.spawnPlayer("Ann", start)
    val m = w.spawnMob(orc.label, w.room(start))
    // Clear initial arrive memory to count exact 300 keys.
    w.forget(m)

    for (i in 1..300) {
      w.runPulses(1)
      w.remember(m, p, "ev_$i")
    }
    val all = w.memoriesOf(m)
    assertEquals(MEMORY_MAX_ENTRIES, all.size)
    // Newest is ev_300 at pulse 300, oldest surviving is ev_45 at pulse 45.
    assertEquals("ev_300", all.first().key.event)
    assertEquals("ev_45", all.last().key.event)
  }

  @Test
  fun `arrive is recorded on spawn and on room move`() {
    val orc = MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"))
    val w = world(listOf(orc), memoryPulses = 100)
    w.runPulses(3)
    val m = w.spawnMob(orc.label, w.room(start))
    val spawnArrive = w.memoriesOf(m, event = "arrive").single()
    assertEquals(m.id, spawnArrive.key.subject)
    assertEquals(3L, spawnArrive.firstPulse)
    assertEquals(3L, spawnArrive.pulse)
    assertEquals(1, spawnArrive.count)

    w.runPulses(5)
    w.transition(m, w.room(start).exits.getValue(Direction.NORTH))
    val moveArrive = w.memoriesOf(m, event = "arrive").single()
    assertEquals(3L, moveArrive.firstPulse)
    assertEquals(8L, moveArrive.pulse)
    assertEquals(2, moveArrive.count)

    w.runPulses(4)
    w.teleport(m, w.room(start))
    val tpArrive = w.memoriesOf(m, event = "arrive").single()
    assertEquals(12L, tpArrive.pulse)
    assertEquals(3, tpArrive.count)
  }

  @Test
  fun `with combat feature off nothing is recorded and mob override beats world value`() {
    val orcDefault = MobProto(Label("//t:orc_def"), "an orc", keywords = listOf("orc"))
    val orcCustom =
      MobProto(Label("//t:orc_custom"), "a custom orc", keywords = listOf("orc"), memoryPulses = 20)
    val orcZero =
      MobProto(Label("//t:orc_zero"), "a forgetful orc", keywords = listOf("orc"), memoryPulses = 0)

    val wNoCombat =
      world(
        listOf(orcDefault, orcCustom),
        memoryPulses = 100,
        features = Features(setOf(Feature.SKILLS, Feature.LEVELING, Feature.STANDING)),
      )
    val mNoCombat = wNoCombat.spawnMob(orcCustom.label, wNoCombat.room(start))
    assertEquals(0L, wNoCombat.memoryPulsesOf(mNoCombat))
    wNoCombat.remember(mNoCombat, mNoCombat, "arrive")
    assertTrue(wNoCombat.memoriesOf(mNoCombat).isEmpty())

    val w = world(listOf(orcDefault, orcCustom, orcZero), memoryPulses = 50)
    val mDef = w.spawnMob(orcDefault.label, w.room(start))
    val mCus = w.spawnMob(orcCustom.label, w.room(start))
    val mZero = w.spawnMob(orcZero.label, w.room(start))

    assertEquals(50L, w.memoryPulsesOf(mDef))
    assertEquals(20L, w.memoryPulsesOf(mCus))
    assertEquals(0L, w.memoryPulsesOf(mZero))
    assertEquals(1, w.memoriesOf(mDef).size)
    assertEquals(1, w.memoriesOf(mCus).size)
    assertTrue(w.memoriesOf(mZero).isEmpty())
  }

  @Test
  fun `forget removes matching memories and leaves other subjects including target matches`() {
    val orc = MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"))
    val w = world(listOf(orc), memoryPulses = 100)
    val p1 = w.spawnPlayer("Ann", start)
    val p2 = w.spawnPlayer("Bob", start)
    val m = w.spawnMob(orc.label, w.room(start))
    w.forget(m)

    w.remember(m, p1, "attack")
    w.remember(m, p1, "damage", amount = 5)
    w.remember(m, p2, "heal", target = p1, amount = 10)
    w.remember(m, p2, "attack")

    // Forget by subject and event: only (p1, damage) is removed.
    assertEquals(1, w.forget(m, subject = p1.id, event = "damage"))
    assertEquals(3, w.memoriesOf(m).size)

    // Forget by subject p1: removes (p1, attack), leaves p2's heal targeting p1 and p2's attack.
    assertEquals(1, w.forget(m, subject = p1.id))
    val remaining = w.memoriesOf(m)
    assertEquals(2, remaining.size)
    assertTrue(remaining.all { it.key.subject == p2.id })
    assertTrue(remaining.any { it.key.target == p1.id })

    // Forget by event "attack": removes (p2, attack).
    assertEquals(1, w.forget(m, event = "attack"))
    assertEquals(1, w.memoriesOf(m).size)

    // Forget all remaining.
    assertEquals(1, w.forget(m))
    assertTrue(w.memoriesOf(m).isEmpty())
  }
}
