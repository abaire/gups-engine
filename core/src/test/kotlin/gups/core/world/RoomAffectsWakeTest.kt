package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.model.ItemLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomAffectsWakeTest {
  private val hooks = Label("//c:t.star")

  @Test
  fun `a room affect that ran out while dormant expires on wake, before on_wake`() {
    val host = RecordingHost()
    var reason: AffectEndReason? = null
    host.refHandlers["gone"] = { _, _, ctx -> reason = ctx.affectEndReason }
    host.handlers[HookName.ON_WAKE] = { _, _, _ -> null }
    val f = Fixture(protos = C.protos(), scripts = host, bubble = true)
    val room = f.w.room(C.r(9))
    f.w.applyRoomAffect(
      room,
      "ward",
      10,
      expireMessage = "The ward fades.",
      onExpire = HookRef(hooks, "gone"),
    )
    f.runTo(20)
    assertEquals(1, room.affects.size, "no expiry while dormant")
    host.calls.clear()
    f.player("Ann", at = C.r(9))
    f.run()
    assertTrue(room.affects.isEmpty())
    assertEquals(AffectEndReason.EXPIRED, reason)
    assertEquals(listOf("ref:gone@21"), host.calls.filter { it.startsWith("ref:") })
    assertTrue(
      host.calls.indexOf("ref:gone@21") < host.calls.indexOf("on_wake:Room 9"),
      "the hook ran before on_wake",
    )
  }

  @Test
  fun `on wake, expired affects end in room, mob, item order`() {
    val host = RecordingHost()
    val f = Fixture(protos = C.protos(), scripts = host, bubble = true)
    val room = f.w.room(C.r(9))
    val rat = f.w.spawnMob(C.RAT, room)
    val crumb = f.w.spawnItem(C.CRUMB, ItemLocation.InRoom(room.id, 0))
    f.w.applyRoomAffect(room, "ward", 10, onExpire = HookRef(hooks, "room_gone"))
    f.w.applyAffect(rat, AffectSpec("weakened", 10, onExpire = HookRef(hooks, "mob_gone")))
    f.w.applyItemAffect(crumb, AffectSpec("rot", 10, onExpire = HookRef(hooks, "item_gone")))
    f.runTo(20)
    host.calls.clear()
    f.player("Ann", at = C.r(9))
    f.run()
    assertEquals(
      listOf("ref:room_gone@21", "ref:mob_gone@21", "ref:item_gone@21"),
      host.calls.filter { it.startsWith("ref:") },
    )
    assertTrue(room.affects.isEmpty())
    assertTrue(rat.affects.isEmpty())
    assertTrue(crumb.affects.isEmpty())
  }

  @Test
  fun `a room affect that has not expired survives the wake with its deadline`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    val room = f.w.room(C.r(9))
    val a = f.w.applyRoomAffect(room, "ward", 100)!!
    f.runTo(20)
    f.player("Ann", at = C.r(9))
    f.run()
    assertEquals(a, room.affects.single())
    assertEquals(100L, a.expiresAt)
  }

  @Test
  fun `effective tags drop an expired affect's tags during dormancy`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    val room = f.w.room(C.r(9))
    f.w.applyRoomAffect(room, "ward", 10, addTags = setOf("warded"))
    f.runTo(5)
    assertTrue("warded" in room.tagsAt(f.w.roomClock))
    f.runTo(20)
    assertTrue("warded" !in room.tagsAt(f.w.roomClock), "the affect expired at pulse 10")
  }

  @Test
  fun `a semi-permanent room affect survives the wake`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    val room = f.w.room(C.r(9))
    val a = f.w.applyRoomAffect(room, "ward", Long.MAX_VALUE)
    f.runTo(20)
    f.player("Ann", at = C.r(9))
    f.run()
    assertEquals(a, room.affects.single())
  }

  @Test
  fun `a room affect's on_tick doesn't run while dormant or get replayed on wake`() {
    val host = RecordingHost()
    val f = Fixture(protos = C.protos(), scripts = host, bubble = true)
    val room = f.w.room(C.r(9))
    f.w.applyRoomAffect(room, "burn", 100, tickEvery = 2, onTick = HookRef(hooks, "tick"))
    f.runTo(20)
    assertTrue(host.calls.none { it.startsWith("ref:") }, "no ticks while dormant")
    f.player("Ann", at = C.r(9))
    f.run()
    assertTrue(host.calls.none { it.startsWith("ref:") }, "no replay on the wake pulse")
    f.run(3)
    assertEquals(listOf("ref:tick@22", "ref:tick@24"), host.calls.filter { it.startsWith("ref:") })
  }
}
