package gups.core.world

import gups.core.model.Prototypes
import gups.core.model.Trait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraitsTest {
  @Test
  fun `a reload's prototype traits refill a full mob to the new maximum and keep granted ones`() {
    val f = Fixture()
    val hp = f.fs.health
    f.w.traits =
      mapOf(
        "tough" to Trait("tough", maxVitalDeltas = mapOf(hp to 5)),
        "blessed" to Trait("blessed", maxVitalDeltas = mapOf(hp to 1)),
      )
    val goblin = f.mob()
    assertNull(f.w.grantTrait(goblin, "blessed"))
    val base = goblin.baseMax(hp)
    assertEquals(base, goblin.current(hp), "gaining a trait leaves current")
    f.w.restoreVitals(goblin)

    val mobs =
      T.mobs(f.fs).map { if (it.label == T.GOBLIN) it.copy(traits = setOf("tough")) else it }
    f.w.applyPrototypes(Prototypes(T.rooms, mobs, T.items, T.skills(f.fs)))
    assertEquals(listOf("blessed", "tough"), goblin.traits.map { it.name })
    assertEquals(base, goblin.baseMax(hp))
    assertEquals(base + 6, goblin.max(hp))
    assertEquals(base + 6, goblin.current(hp), "a full mob stays full")
    assertTrue(f.w.invariantViolations().isEmpty())
  }
}
