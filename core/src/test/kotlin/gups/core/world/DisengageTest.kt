package gups.core.world

import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Actor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

/** The `on_disengage` hook: the reason it fires with, and who it fires for. */
@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class DisengageTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @BeforeEach
  fun useSchema() {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  /** Records the (actor, event) pairs the hook fires for, on the attached and global paths. */
  private class Recorder {
    val host = RecordingHost()
    val events = mutableSetOf<Pair<String, String>>()

    fun recordEvents() {
      host.handlers[HookName.ON_DISENGAGE] = { _, owner, ctx ->
        if (owner === ctx.actor) events += ctx.actor.name to (ctx.event ?: "")
        null
      }
    }
  }

  @Test
  fun `a stop_combat frees both fighters and fires stop for each`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    f.w.startCombat(ann, bob)
    f.w.stopCombat(ann, bob)
    assertEquals(setOf("Ann" to "stop", "Bob" to "stop"), r.events)
    assertTrue(r.host.calls.contains("on_disengage:global"), "the global path fires too")
  }

  @Test
  fun `a successful flee fires flee for the fleeing actor and the opponent it leaves`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(rules = FixedRules(flee = 100), scripts = r.host)
    val ann = f.player("Ann")
    val dummy = f.mob(T.DUMMY)
    f.w.startCombat(ann, dummy)
    f.input(ann, "flee")
    f.run(2)
    assertEquals(setOf("Ann" to "flee", "a training dummy" to "flee"), r.events)
  }

  @Test
  fun `a transition without a reason fires stop for both fighters`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val dummy = f.mob(T.DUMMY)
    f.w.startCombat(ann, dummy)
    val exit = f.w.room(T.HALL).exits[gups.core.model.Direction.NORTH]!!
    f.w.transition(ann, exit)
    assertEquals(setOf("Ann" to "stop", "a training dummy" to "stop"), r.events)
  }

  @Test
  fun `a death fires death for the freed opponent but not for the dying actor`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val goblin = f.mob()
    f.w.startCombat(ann, goblin)
    f.hurt(goblin, goblin.current(fs.health) + 1, source = ann)
    assertEquals(setOf("Ann" to "death"), r.events)
  }

  @Test
  fun `a three-way fight fires only for the actor whose last opponent leaves`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(scripts = r.host)
    val a = f.player("Ann")
    val b = f.player("Bob")
    val c = f.player("Cal")
    f.w.startCombat(a, b)
    f.w.startCombat(a, c)
    f.w.startCombat(b, c)
    f.w.stopCombat(a, b)
    f.w.stopCombat(a, c)
    assertEquals(setOf("Ann" to "stop"), r.events, "only Ann, whose last opponent left, fires")
  }

  @Test
  fun `a null reason fires no hook at all`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    f.w.startCombat(ann, bob)
    f.w.disengageAll(ann, null)
    assertTrue(r.host.calls.none { it.startsWith("on_disengage") }, "null reason fires nothing")
  }

  @Test
  fun `a hook that restarts combat leaves a consistent engagement`() {
    val r = Recorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    r.host.handlers[HookName.ON_DISENGAGE] = { w, owner, ctx ->
      if (owner === ctx.actor && !ctx.actor.fighting.contains(bob.id)) w.startCombat(ctx.actor, bob)
      null
    }
    f.w.startCombat(ann, bob)
    f.w.stopCombat(ann, bob)
    assertTrue(ann.isEngaged && bob.isEngaged, "they are fighting again")
    assertTrue(ann.fighting.contains(bob.id) && bob.fighting.contains(ann.id))
  }

  /** A hook that re-engages the freed actor with [target], as a vengeful script would. */
  private fun reengage(r: Recorder, target: () -> Actor) {
    r.host.handlers[HookName.ON_DISENGAGE] = { w, owner, ctx ->
      if (owner === ctx.actor && ctx.actor !== target()) w.startCombat(ctx.actor, target())
      null
    }
  }

  @Test
  fun `a transition fires after the move, so a hook can't re-engage across rooms`() {
    val r = Recorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val dummy = f.mob(T.DUMMY)
    reengage(r) { ann }
    f.w.startCombat(ann, dummy)
    val exit = f.w.room(T.HALL).exits[gups.core.model.Direction.NORTH]!!
    f.w.transition(ann, exit)
    assertTrue(r.host.calls.any { it.startsWith("on_disengage:") }, "the hook still fires")
    assertTrue(ann.room !== dummy.room)
    assertFalse(ann.isEngaged || dummy.isEngaged, "no engagement spans two rooms")
  }

  @Test
  fun `a mob that leaves a fight for a dormant room still gets the hook`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(protos = C.protos(), scripts = r.host, bubble = true)
    val ann = f.player("Ann", at = C.r(0))
    val rat = f.w.spawnMob(C.RAT, f.w.room(C.r(0)))
    f.run()
    f.w.startCombat(ann, rat)
    f.w.teleport(rat, f.w.room(C.r(9)))
    assertTrue(f.w.room(C.r(9)).isDormant, "the rat landed out of every bubble")
    assertEquals(setOf("Ann" to "stop", "a rat" to "stop"), r.events)
  }

  @Test
  fun `a quit fires after the removal, for the opponents only`() {
    val r = Recorder()
    r.recordEvents()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    f.w.startCombat(ann, bob)
    f.w.quit(ann)
    assertEquals(setOf("Bob" to "stop"), r.events, "the quitter has left before the hook runs")
  }

  @Test
  fun `a quit fires after the removal, so a hook can't re-engage the quitter`() {
    val r = Recorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    reengage(r) { ann }
    f.w.startCombat(ann, bob)
    f.w.quit(ann)
    assertFalse(bob.isEngaged, "no engagement points at a removed actor")
  }

  @Test
  fun `a death fires after the removal, so a hook can't re-engage the dead`() {
    val r = Recorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val goblin = f.mob()
    reengage(r) { goblin }
    f.w.startCombat(ann, goblin)
    f.hurt(goblin, goblin.current(fs.health) + 1, source = ann)
    assertTrue(r.host.calls.any { it.startsWith("on_disengage:") }, "the hook still fires")
    assertFalse(goblin.inWorld)
    assertFalse(ann.isEngaged, "no engagement points at a removed actor")
  }

  @Test
  fun `an on_death hook can't re-engage the dying actor`() {
    val r = Recorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val goblin = f.mob()
    r.host.handlers[HookName.ON_DEATH] = { w, _, ctx ->
      w.startCombat(ctx.actor!!, ctx.target!!)
      null
    }
    f.w.startCombat(ann, goblin)
    f.hurt(goblin, goblin.current(fs.health) + 1, source = ann)
    assertTrue(r.host.calls.any { it.startsWith("on_death:") }, "the hook ran")
    assertFalse(goblin.inWorld)
    assertFalse(ann.isEngaged, "no engagement points at a removed actor")
  }

  @Test
  fun `a hook that starts and stops combat again is bounded by the cascade guard`() {
    val r = Recorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    r.host.handlers[HookName.ON_DISENGAGE] = { w, owner, ctx ->
      if (owner === ctx.actor) {
        w.startCombat(ctx.actor, bob)
        w.stopCombat(ctx.actor)
      }
      null
    }
    f.w.startCombat(ann, bob)
    f.w.stopCombat(ann, bob)
    assertTrue(
      f.w.diagnostics.any { it.kind == "cascade" && "dropped" in it.message },
      "the cascade guard dropped the runaway recursion",
    )
  }
}
