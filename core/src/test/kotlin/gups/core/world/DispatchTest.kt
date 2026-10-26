package gups.core.world

import gups.core.CASCADE_MAX_DEPTH
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DispatchTest {
  @Test
  fun `before hooks fire on the subject, the actor, the room, other actors, then items`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_GET] = { _, _, _ -> null }
    val f = Fixture(scripts = host)
    val hall = f.w.room(T.HALL)
    val p = f.player("Ann")
    val dummy = f.mob(T.DUMMY)
    val goblin = f.mob()
    f.w.spawnItem(T.BAG, ItemLocation.Carried(goblin.id))
    f.w.spawnItem(T.BOW, ItemLocation.InRoom(hall.id, 0))
    f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 0))
    f.input(p, "get key")
    f.run()
    assertEquals(
      listOf(
          "a brass key",
          "Ann",
          "The Hall",
          "a training dummy",
          "a goblin",
          "a short bow",
          "a bag",
        )
        .map { "before_get:$it" },
      host.calls,
    )
    assertEquals(dummy.id.v + 1, goblin.id.v)
  }

  @Test
  fun `carried items fire grouped by owner in owner order`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_GET] = { _, _, _ -> null }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    val goblin = f.mob()
    f.w.spawnItem(T.KEY, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    // Ann's bag has the higher id, but Ann's items still come before the goblin's.
    f.w.spawnItem(T.BOW, ItemLocation.Carried(goblin.id))
    f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    f.input(p, "get key")
    f.run()
    assertEquals(
      listOf("a brass key", "Ann", "The Hall", "a goblin", "a bag", "a short bow").map {
        "before_get:$it"
      },
      host.calls,
    )
  }

  @Test
  fun `the first deny short-circuits`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_GET] = { _, owner, _ ->
      if (owner.name == "The Hall") HookOutcome.Deny("It's glued down.") else null
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    f.mob()
    f.w.spawnItem(T.KEY, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.input(p, "get key")
    f.run()
    assertEquals(
      listOf("before_get:a brass key", "before_get:Ann", "before_get:The Hall"),
      host.calls,
    )
    assertEquals("It's glued down.\n", f.out(p))
    assertTrue(p.inventory.isEmpty())
  }

  @Test
  fun `cascades deeper than the limit are dropped with a diagnostic`() {
    val host = RecordingHost()
    var depth = 0
    host.handlers[HookName.ON_DAMAGE] = { w, owner, ctx ->
      if (owner === ctx.target) {
        depth++
        w.damage(ctx.target, 1, DamageType.FIRE)
      }
      null
    }
    val f = Fixture(scripts = host)
    val g = f.mob(T.DUMMY)
    f.w.damage(g, 1, DamageType.FIRE)
    assertEquals(CASCADE_MAX_DEPTH, depth)
    assertEquals(1000 - CASCADE_MAX_DEPTH - 1, g.hp)
    assertTrue(f.w.diagnostics.any { it.kind == "cascade" })
  }

  @Test
  fun `a crashing before hook allows the action`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_EXIT] = { _, _, _ -> error("boom") }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    f.input(p, "north")
    f.run()
    assertEquals(T.YARD, p.room.label)
    assertTrue(f.w.diagnostics.any { it.kind == "script" && "boom" in it.message })
  }

  @Test
  fun `observer hooks do not fire on the actor itself`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_SAY] = { _, _, _ -> null }
    val f = Fixture(scripts = host)
    val g = f.mob()
    f.mob(T.DUMMY)
    f.w.act(g, "say hi")
    assertEquals(listOf("on_say:The Hall", "on_say:a training dummy"), host.calls)
  }

  @Test
  fun `the actor's items observe its events even though the actor does not`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_SAY] = { _, _, _ -> null }
    val f = Fixture(scripts = host)
    val g = f.mob()
    val dummy = f.mob(T.DUMMY)
    f.w.spawnItem(T.BAG, ItemLocation.Carried(g.id))
    f.w.spawnItem(T.BOW, ItemLocation.Carried(dummy.id))
    f.w.act(g, "say hi")
    assertEquals(
      listOf("The Hall", "a training dummy", "a bag", "a short bow").map { "on_say:$it" },
      host.calls,
    )
  }
}
