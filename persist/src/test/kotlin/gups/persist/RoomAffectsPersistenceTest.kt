package gups.persist

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Affect
import gups.core.model.ModifierPoint
import gups.core.world.quit
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomAffectsPersistenceTest {
  @Test
  fun `a room's affects survive a restart`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    val room = a.w.room(P.HALL)
    room.affects +=
      Affect(
        100,
        "ward",
        null,
        0,
        1000,
        modifiers = mapOf(ModifierPoint.TO_HIT to HookRef(Label("//t:mods.star"), "ra")),
        onExpire = HookRef(Label("//t:a.star"), "unward"),
        power = 150,
      )
    room.affects +=
      Affect(101, "bless", null, 0, 5000, addTags = setOf("holy"), removeTags = setOf("sealed"))
    room.dirty = true
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos())
    val room2 = b.w.room(P.HALL)
    assertEquals(2, room2.affects.size)
    val ward = room2.affects.single { it.name == "ward" }
    val bless = room2.affects.single { it.name == "bless" }
    assertEquals(100L, ward.id, "the affect keeps its id")
    assertEquals(1000L, ward.expiresAt, "the deadline survives")
    assertEquals(
      mapOf(ModifierPoint.TO_HIT to HookRef(Label("//t:mods.star"), "ra")),
      ward.modifiers,
    )
    assertEquals(HookRef(Label("//t:a.star"), "unward"), ward.onExpire)
    assertEquals(150, ward.power)
    assertEquals(101L, bless.id)
    assertEquals(setOf("holy"), bless.addTags)
    assertEquals(setOf("sealed"), bless.removeTags)
    b.close()
  }
}
