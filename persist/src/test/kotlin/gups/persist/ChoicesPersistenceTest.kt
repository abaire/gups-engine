package gups.persist

import gups.core.model.Mob
import gups.core.model.Trait
import gups.core.world.grantTrait
import gups.core.world.quit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChoicesPersistenceTest {
  private val traits = mapOf("tough" to Trait("tough"), "ghostly" to Trait("ghostly"))

  @Test
  fun `answers and granted traits survive a restart`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos(), traits = traits)
    val ann = a.login("Ann")
    ann.choices["race"] = "elf"
    ann.choices["class"] = "mage"
    ann.dirty = true
    assertNull(a.w.grantTrait(ann, "tough"))
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos(), traits = traits)
    val ann2 = b.login("Ann")
    assertEquals(mapOf("race" to "elf", "class" to "mage"), ann2.choices.toMap())
    assertEquals(setOf("tough"), ann2.grantedTraits)
    assertEquals(listOf("tough"), ann2.traits.map { it.name })
    b.close()
  }

  @Test
  fun `an unknown granted trait is kept and ignored until it is known again`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos(), traits = traits)
    val ann = a.login("Ann")
    a.w.grantTrait(ann, "tough")
    a.w.grantTrait(ann, "ghostly")
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos(), traits = mapOf("tough" to Trait("tough")))
    val ann2 = b.login("Ann")
    assertEquals(setOf("ghostly", "tough"), ann2.grantedTraits)
    assertEquals(listOf("tough"), ann2.traits.map { it.name })
    b.w.quit(ann2)
    b.save()
    b.close()

    val c = PersistWorld(file, P.protos(), traits = traits)
    val ann3 = c.login("Ann")
    assertEquals(listOf("ghostly", "tough"), ann3.traits.map { it.name })
    c.close()
  }

  @Test
  fun `a trait granted to a mob survives a restart`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos(), traits = traits)
    val rat = a.w.actors.filterIsInstance<Mob>().first()
    assertNull(a.w.grantTrait(rat, "ghostly"))
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos(), traits = traits)
    val rat2 = b.w.actor(rat.id) as Mob
    assertEquals(setOf("ghostly"), rat2.grantedTraits)
    assertTrue(rat2.hasTrait("ghostly"))
    b.close()
  }
}
