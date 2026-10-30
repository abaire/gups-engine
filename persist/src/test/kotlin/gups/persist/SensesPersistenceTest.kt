package gups.persist

import gups.core.model.Channel
import gups.core.model.Mob
import gups.core.world.applyAffect
import gups.core.world.quit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SensesPersistenceTest {
  @Test
  fun `base senses and the sense deltas of affects survive a restart`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    ann.baseSenses[Channel.SOUND] = 3
    ann.baseSenses[Channel.SIGHT] = 0
    ann.dirty = true
    a.w.applyAffect(ann, "attuned", 5000, senseDeltas = mapOf(Channel.SOUND to 2))
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos())
    val ann2 = b.login("Ann")
    assertEquals(3, ann2.baseSenses[Channel.SOUND])
    assertEquals(0, ann2.sense(Channel.SIGHT))
    assertEquals(5, ann2.sense(Channel.SOUND))
    b.close()
  }

  @Test
  fun `a mob's base senses are saved only when they differ from its prototype`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos())
    val rats = a.w.actors.filterIsInstance<Mob>().sortedBy { it.id }
    rats[0].baseSenses[Channel.SOUND] = 3
    rats[0].dirty = true
    a.save()
    val saved = a.persistence.db.entityRows().filter { it.kind == "mob" }.sortedBy { it.id }
    assertContains(saved[0].payload, "\"senses\"")
    assertFalse("\"senses\"" in saved[1].payload)
    a.close()

    val b = PersistWorld(file, P.protos())
    val rats2 = b.w.actors.filterIsInstance<Mob>().sortedBy { it.id }
    assertEquals(3, rats2[0].baseSenses[Channel.SOUND])
    assertEquals(1, rats2[1].baseSenses[Channel.SOUND])
    b.close()
  }
}
