package gups.core.world

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.MobProto
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeleportTest {
  private val host = RecordingHost()
  private val bouncer =
    MobProto(
      Label("//t:bouncer"),
      "bouncer",
      listOf("bouncer"),
      vitals = mapOf("hp" to 40),
      behaviors = listOf(Guard(Direction.NORTH)),
    )
  private val f =
    Fixture(
      rules = FixedRules(flee = 100),
      scripts = host,
      protos = T.protos(extraMobs = listOf(bouncer)),
    )

  @Test
  fun `a successful teleport moves the actor and returns null`() {
    val p = f.player("Ann")
    val result = f.w.teleport(p, f.w.room(T.YARD))
    f.run()
    assertNull(result)
    assertEquals(T.YARD, p.room.label)
  }

  @Test
  fun `an enter refusal is returned, moves nothing and sends nothing`() {
    f.w.restrictions =
      mapOf(RestrictionPoint.ENTER to listOf(Restriction { _, _ -> "You are pushed back." }))
    val p = f.player("Ann")
    f.out(p)
    val result = f.w.teleport(p, f.w.room(T.YARD))
    f.run()
    assertEquals("You are pushed back.", result)
    assertEquals(T.HALL, p.room.label)
    assertEquals("", f.out(p))
  }

  @Test
  fun `a before_enter veto is returned, sees no direction and the room left, and sends nothing`() {
    var seen: HookContext? = null
    host.handlers[HookName.BEFORE_ENTER] = { _, _, ctx ->
      seen = ctx
      HookOutcome.Deny("The yard is sealed.")
    }
    val p = f.player("Ann")
    f.out(p)
    val result = f.w.teleport(p, f.w.room(T.YARD))
    f.run()
    assertEquals("The yard is sealed.", result)
    assertEquals(T.HALL, p.room.label)
    assertEquals("", f.out(p))
    assertNull(seen?.direction)
    assertEquals(T.HALL, seen?.fromRoom?.label)
  }

  @Test
  fun `guards and before_exit of the room left do not run on a teleport`() {
    host.handlers[HookName.BEFORE_EXIT] = { _, _, _ -> HookOutcome.Deny("Stay.") }
    f.mob(Label("//t:bouncer"))
    val p = f.player("Ann")
    assertTrue(
      f.w.guardDenial(p, f.w.room(T.HALL), Direction.NORTH) != null,
      "the guard bars a walk",
    )
    val result = f.w.teleport(p, f.w.room(T.YARD))
    f.run()
    assertNull(result)
    assertEquals(T.YARD, p.room.label)
    assertTrue("before_exit:Ann" !in host.calls && host.calls.none { it.startsWith("before_exit") })
  }
}
