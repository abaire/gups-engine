package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.MobProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The leash state: prototype value, per-instance override, the verbs, validation and reload. */
class LeashStateTest {
  private fun proto(leash: Int?, label: String = "leashed") =
    MobProto(
      Label("//t:$label"),
      label,
      listOf(label),
      vitals = mapOf(FixtureSchemas.current.health to 40),
      leash = leash,
    )

  private fun fixture(p: MobProto) = Fixture(protos = T.protos(extraMobs = listOf(p)))

  @Test
  fun `a proto without a leash gives null`() {
    val p = proto(leash = null)
    val f = fixture(p)
    val m = f.w.spawnMob(p.label, f.w.room(T.HALL))
    assertNull(m.leash)
  }

  @Test
  fun `the prototype value is the effective leash`() {
    val p = proto(leash = 4)
    val f = fixture(p)
    val m = f.w.spawnMob(p.label, f.w.room(T.HALL))
    assertEquals(4, m.leash)
  }

  @Test
  fun `the override wins over the prototype, and reset restores it`() {
    val p = proto(leash = 4)
    val f = fixture(p)
    val m = f.w.spawnMob(p.label, f.w.room(T.HALL))
    f.w.setLeash(m, 2)
    assertEquals(2, m.leash)
    // An override of null (unleashed) must not fall back to the prototype's 4.
    f.w.setLeash(m, null)
    assertNull(m.leash)
    f.w.resetLeash(m)
    assertEquals(4, m.leash)
  }

  @Test
  fun `setLeash rejects a negative value`() {
    val p = proto(leash = 4)
    val f = fixture(p)
    val m = f.w.spawnMob(p.label, f.w.room(T.HALL))
    assertFailsWith<IllegalArgumentException> { f.w.setLeash(m, -1) }
    assertEquals(4, m.leash, "the failed set changes nothing")
  }

  @Test
  fun `a reload changes the leash for unoverridden mobs and leaves overrides alone`() {
    val p = proto(leash = 4)
    val f = fixture(p)
    val m1 = f.w.spawnMob(p.label, f.w.room(T.HALL), 0)
    val m2 = f.w.spawnMob(p.label, f.w.room(T.HALL), 1)
    f.w.setLeash(m2, 2)
    val next = Prototypes(T.rooms, T.mobs(f.fs) + p.copy(leash = 6), T.items, T.skills(f.fs))
    f.w.applyPrototypes(next)
    assertEquals(6, m1.leash, "no override: the new prototype value applies")
    assertEquals(2, m2.leash, "an override is not touched by the reload")
  }

  @Test
  fun `resetLeash on an unoverridden mob changes nothing`() {
    val p = proto(leash = 4)
    val f = fixture(p)
    val m = f.w.spawnMob(p.label, f.w.room(T.HALL))
    f.w.resetLeash(m)
    assertEquals(4, m.leash)
    assertNull(m.leashOverride)
  }
}
