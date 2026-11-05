package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomAffectsTest {
  private val hooks = Label("//t:hooks.star")

  @Test
  fun `apply sets a 64-bit deadline, and a sub-1 duration is refused`() {
    val f = Fixture()
    val room = f.w.room(T.HALL)
    val a = f.w.applyRoomAffect(room, "ward", 100)
    assertEquals(100L, a!!.expiresAt - f.w.pulse)
    assertTrue(room.dirty)
    val forever = f.w.applyRoomAffect(room, "haste", Long.MAX_VALUE)
    assertEquals(Long.MAX_VALUE, forever!!.expiresAt)
    f.run(1000)
    assertTrue(room.affects.any { it.name == "haste" })
    assertFailsWith<IllegalArgumentException> { f.w.applyRoomAffect(room, "slow", 0) }
  }

  @Test
  fun `a same-name affect is replaced only by one that expires later`() {
    val f = Fixture()
    val room = f.w.room(T.HALL)
    val first = f.w.applyRoomAffect(room, "ward", 100)!!
    val shorter = f.w.applyRoomAffect(room, "ward", 50)
    assertSame(first, shorter, "a shorter re-apply leaves the old one")
    assertEquals(1, room.affects.size)
    val longer = f.w.applyRoomAffect(room, "ward", 200)
    assertNotSame(first, longer, "a longer re-apply replaces with a new id")
    assertEquals(1, room.affects.size)
    assertEquals(200L, longer!!.expiresAt - f.w.pulse)
  }

  @Test
  fun `a room affect expires with its hook, and the message reaches only the room`() {
    val host = RecordingHost()
    var owner: Entity? = null
    var reason: AffectEndReason? = null
    host.refHandlers["gone"] = { _, o, ctx ->
      owner = o
      reason = ctx.affectEndReason
    }
    val f = Fixture(scripts = host)
    val ann = f.player("Ann")
    val bob = f.player("Bob", at = T.YARD)
    val room = f.w.room(T.HALL)
    f.w.applyRoomAffect(
      room,
      "ward",
      3,
      expireMessage = "The ward fades.",
      onExpire = HookRef(hooks, "gone"),
    )
    f.runTo(f.w.pulse + 2)
    assertEquals(1, room.affects.size)
    f.run()
    assertTrue(room.affects.isEmpty())
    assertTrue(room.dirty)
    assertEquals(room, owner, "the hook's self is the room")
    assertEquals(AffectEndReason.EXPIRED, reason)
    assertEquals(listOf("ref:gone@3"), host.calls.filter { it.startsWith("ref:") })
    assertTrue("The ward fades." in f.out(ann))
    assertTrue(f.out(bob).isEmpty(), "no message outside the room")
  }

  @Test
  fun `removeRoomAffect ends the affect as removed, with no message`() {
    val host = RecordingHost()
    var owner: Entity? = null
    var reason: AffectEndReason? = null
    host.refHandlers["gone"] = { _, o, ctx ->
      owner = o
      reason = ctx.affectEndReason
    }
    val f = Fixture(scripts = host)
    val ann = f.player("Ann")
    val room = f.w.room(T.HALL)
    f.w.applyRoomAffect(
      room,
      "ward",
      10,
      expireMessage = "Gone.",
      onExpire = HookRef(hooks, "gone"),
    )
    assertTrue(f.w.removeRoomAffect(room, "ward"))
    assertTrue(room.affects.isEmpty())
    assertTrue(room.dirty)
    assertEquals(room, owner)
    assertEquals(AffectEndReason.REMOVED, reason)
    assertTrue(f.out(ann).isEmpty(), "no message on a removal")
    assertFalse(f.w.removeRoomAffect(room, "ward"))
  }

  @Test
  fun `on_tick fires every tickEvery pulses, and never on the expiry pulse`() {
    val host = RecordingHost()
    val f = Fixture(scripts = host)
    val room = f.w.room(T.HALL)
    f.w.applyRoomAffect(room, "burn", 10, tickEvery = 2, onTick = HookRef(hooks, "tick"))
    f.runTo(9)
    assertEquals(
      listOf("ref:tick@2", "ref:tick@4", "ref:tick@6", "ref:tick@8"),
      host.calls.filter { it.startsWith("ref:") },
    )
    f.run()
    assertEquals(
      listOf("ref:tick@2", "ref:tick@4", "ref:tick@6", "ref:tick@8"),
      host.calls.filter { it.startsWith("ref:") },
      "no tick on the expiry pulse",
    )
    assertTrue(room.affects.isEmpty())
  }

  @Test
  fun `a dormant room's affects neither tick nor expire`() {
    val host = RecordingHost()
    val f = Fixture(protos = C.protos(), scripts = host, bubble = true)
    val room = f.w.room(C.r(9))
    f.w.applyRoomAffect(
      room,
      "rot",
      100,
      tickEvery = 2,
      onTick = HookRef(Label("//c:t.star"), "tick"),
    )
    f.run(30)
    assertTrue(host.calls.none { it.startsWith("ref:") }, "no ticks while dormant")
    assertEquals(1, room.affects.size, "no expiry while dormant")
  }

  @Test
  fun `a dormant room with an active reason still ticks its affects`() {
    val host = RecordingHost()
    val f = Fixture(protos = C.protos(), scripts = host, bubble = true)
    val room = f.w.room(C.r(9))
    assertTrue(f.w.setActive(room, "quest:1"))
    f.w.applyRoomAffect(
      room,
      "rot",
      100,
      tickEvery = 2,
      onTick = HookRef(Label("//c:t.star"), "tick"),
    )
    f.run(5)
    assertEquals(listOf("ref:tick@2", "ref:tick@4"), host.calls.filter { it.startsWith("ref:") })
  }
}
