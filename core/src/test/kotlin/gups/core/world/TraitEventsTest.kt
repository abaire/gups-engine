package gups.core.world

import gups.core.CASCADE_MAX_DEPTH
import gups.core.model.Actor
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.Trait
import gups.core.model.TraitEvent
import gups.core.model.TraitHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraitEventsTest {
  class Call(val bearer: Actor, val event: TraitEvent, val fields: Map<String, Any?>)

  private val calls = mutableListOf<Call>()

  /**
   * Registers trait [name] whose handler for each of [events] records the call, then runs [also].
   */
  private fun Fixture.trait(
    name: String,
    vararg events: TraitEvent,
    also: (World, Actor, Map<String, Any?>) -> Unit = { _, _, _ -> },
  ) {
    val handler = TraitHandler { world, bearer, event, fields ->
      calls += Call(bearer, event, fields)
      also(world, bearer, fields)
    }
    w.traits = w.traits + (name to Trait(name, handlers = events.associateWith { handler }))
  }

  private fun Fixture.type() = DamageType(fs.unarmed)

  private fun Fixture.of(event: TraitEvent) = calls.filter { it.event == event }

  @Test
  fun `damaged fires for the victim with the damage's fields and not for the source`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.DAMAGED)
    val ann = f.player("Ann")
    val dee = f.player("Dee", index = 1)
    f.w.grantTrait(ann, "watcher")
    f.w.grantTrait(dee, "watcher")
    f.w.damage(dee, mapOf(f.fs.health to 7), f.type(), ann)
    val call = f.of(TraitEvent.DAMAGED).single()
    assertEquals(dee, call.bearer)
    assertEquals(ann, call.fields["source"])
    assertEquals(7, call.fields["amount"])
    assertEquals(f.fs.unarmed, call.fields["type"])
    assertEquals(mapOf(f.fs.health to 7), call.fields["losses"])
    assertEquals(
      listOf(mapOf("type" to f.fs.unarmed, "losses" to mapOf(f.fs.health to 7))),
      call.fields["parts"],
    )
  }

  @Test
  fun `damaged doesn't fire for a gain-only call or a zero-loss call`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.DAMAGED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.setVital(ann, f.fs.energy, ann.max(f.fs.energy) - 5)
    f.w.damage(ann, mapOf(f.fs.energy to -2), f.type())
    f.w.damage(ann, mapOf(f.fs.health to 0), f.type())
    assertTrue(calls.isEmpty(), "${calls.size} calls")
  }

  @Test
  fun `damaged runs after the script on_damage hook`() {
    val host = RecordingHost()
    var marker = false
    host.handlers[HookName.ON_DAMAGE] = { _, owner, ctx ->
      if (owner === ctx.target) marker = true
      null
    }
    val f = Fixture(scripts = host)
    var seen: Boolean? = null
    f.trait("watcher", TraitEvent.DAMAGED) { _, _, _ -> seen = marker }
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.hurt(ann, 3)
    assertEquals(true, seen)
  }

  @Test
  fun `healed caps the amount at the room left and reports what was requested`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.HEALED)
    val ann = f.player("Ann")
    val dee = f.player("Dee", index = 1)
    f.w.grantTrait(ann, "watcher")
    f.setVital(ann, f.fs.health, ann.max(f.fs.health) - 4)
    f.w.heal(ann, f.fs.health, 10, dee)
    val call = f.of(TraitEvent.HEALED).single()
    assertEquals(ann, call.bearer)
    assertEquals(dee, call.fields["source"])
    assertEquals(f.fs.health, call.fields["vital"])
    assertEquals(4, call.fields["amount"])
    assertEquals(10, call.fields["requested"])
  }

  @Test
  fun `healed doesn't fire when the vital is full`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.HEALED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.heal(ann, f.fs.health, 10)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `healed fires with a null source when none is given`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.HEALED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.setVital(ann, f.fs.health, ann.max(f.fs.health) - 4)
    f.w.heal(ann, f.fs.health, 3)
    assertNull(f.of(TraitEvent.HEALED).single().fields["source"])
  }

  @Test
  fun `regeneration doesn't fire healed`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.HEALED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.setVital(ann, f.fs.health, 1)
    f.run(200)
    assertTrue(ann.current(f.fs.health) > 1, "the test needs regeneration to have happened")
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `handlers that damage each other are cut off at the cascade depth`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val dee = f.player("Dee", index = 1)
    f.trait("ping", TraitEvent.DAMAGED) { w, bearer, _ ->
      w.damage(if (bearer === ann) dee else ann, mapOf(f.fs.health to 1), f.type(), bearer)
    }
    f.w.grantTrait(ann, "ping")
    f.w.grantTrait(dee, "ping")
    f.hurt(ann, 1)
    assertEquals(CASCADE_MAX_DEPTH, calls.size)
    assertTrue(f.w.diagnostics.any { it.kind == "cascade" }, f.w.diagnostics.toString())
  }

  @Test
  fun `a throwing handler records a diagnostic and later traits still run`() {
    val f = Fixture()
    f.trait("a_first", TraitEvent.DAMAGED) { _, _, _ -> error("boom") }
    f.trait("b_second", TraitEvent.DAMAGED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "a_first")
    f.w.grantTrait(ann, "b_second")
    f.hurt(ann, 2)
    assertEquals(2, calls.size)
    assertTrue(
      f.w.diagnostics.any {
        it.kind == "plugin" && "trait 'a_first' handler DAMAGED threw: boom" in it.message
      },
      f.w.diagnostics.toString(),
    )
  }

  @Test
  fun `a dormant mob with the trait gets no call`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    f.trait("watcher", TraitEvent.DAMAGED)
    val rat = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    f.w.grantTrait(rat, "watcher")
    f.run(2)
    assertTrue(f.w.room(C.r(9)).isDormant)
    f.w.damage(rat, mapOf(f.fs.health to 3), f.type())
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `an awake mob with the trait gets the call`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.DAMAGED)
    val goblin = f.mob()
    f.w.grantTrait(goblin, "watcher")
    f.w.damage(goblin, mapOf(f.fs.health to 3), f.type())
    assertEquals(goblin, f.of(TraitEvent.DAMAGED).single().bearer)
  }

  @Test
  fun `died fires for the victim with its killer`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.DIED)
    val ann = f.player("Ann")
    val goblin = f.mob()
    f.w.grantTrait(goblin, "watcher")
    f.hurt(goblin, goblin.current(f.fs.health) + 1, source = ann)
    f.run()
    val call = f.of(TraitEvent.DIED).single()
    assertEquals(goblin, call.bearer)
    assertEquals(ann, call.fields["killer"])
  }

  @Test
  fun `died has a null killer for a death with no attacker`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.DIED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.hurt(ann, ann.current(f.fs.health) + 1)
    f.run()
    assertNull(f.of(TraitEvent.DIED).single().fields["killer"])
  }

  @Test
  fun `killed fires for the killer with the victim and not when there is no killer`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.KILLED)
    val ann = f.player("Ann")
    val goblin = f.mob()
    f.w.grantTrait(ann, "watcher")
    f.w.grantTrait(goblin, "watcher")
    f.hurt(goblin, goblin.current(f.fs.health) + 1, source = ann)
    f.run()
    val call = f.of(TraitEvent.KILLED).single()
    assertEquals(ann, call.bearer)
    assertEquals(goblin, call.fields["victim"])
    val dee = f.player("Dee", index = 1)
    f.w.grantTrait(dee, "watcher")
    f.hurt(dee, dee.current(f.fs.health) + 1)
    f.run()
    assertEquals(1, f.of(TraitEvent.KILLED).size)
  }

  @Test
  fun `a mob killer gets killed`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.KILLED)
    val ann = f.player("Ann")
    val goblin = f.mob()
    f.w.grantTrait(goblin, "watcher")
    f.hurt(ann, ann.current(f.fs.health) + 1, source = goblin)
    f.run()
    val call = f.of(TraitEvent.KILLED).single()
    assertEquals(goblin, call.bearer)
    assertEquals(ann, call.fields["victim"])
  }

  @Test
  fun `at death the victim's died runs before the killer's killed, between the hooks`() {
    val host = RecordingHost()
    val order = mutableListOf<String>()
    host.handlers[HookName.ON_DEATH] = { _, owner, ctx ->
      if (owner === ctx.target) order += "entity hook"
      null
    }
    // RecordingHost skips global on_death, so record it here.
    val scripts =
      object : ScriptHost by host {
        override fun invokeGlobal(world: World, hook: HookName, subject: Actor, ctx: HookContext) {
          if (hook == HookName.ON_DEATH) order += "global hook"
        }
      }
    val f = Fixture(scripts = scripts)
    f.trait("watcher", TraitEvent.DIED, TraitEvent.KILLED) { _, _, fields ->
      order += if ("victim" in fields) "killed" else "died"
    }
    f.w.pluginEvents.getOrPut(HookName.ON_DEATH) { mutableListOf() } += EventListener { _, _, _ ->
      order += "listener"
    }
    val ann = f.player("Ann")
    val goblin = f.mob()
    f.w.grantTrait(ann, "watcher")
    f.w.grantTrait(goblin, "watcher")
    f.hurt(goblin, goblin.current(f.fs.health) + 1, source = ann)
    f.run()
    assertEquals(listOf("entity hook", "died", "killed", "global hook", "listener"), order)
  }

  @Test
  fun `a died handler that damages its bearer doesn't start a second death`() {
    val host = RecordingHost()
    var deaths = 0
    host.handlers[HookName.ON_DEATH] = { _, owner, ctx ->
      if (owner === ctx.target) deaths++
      null
    }
    val f = Fixture(scripts = host)
    f.trait("watcher", TraitEvent.DIED) { w, bearer, _ ->
      w.damage(bearer, mapOf(f.fs.health to 1), f.type())
    }
    val goblin = f.mob()
    f.w.grantTrait(goblin, "watcher")
    f.hurt(goblin, goblin.current(f.fs.health) + 1)
    f.run()
    assertEquals(1, f.of(TraitEvent.DIED).size)
    assertEquals(1, deaths)
  }

  @Test
  fun `level_changed fires once per step with consecutive levels`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.LEVEL_CHANGED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.awardXp(ann, 10_000L)
    val ups = f.of(TraitEvent.LEVEL_CHANGED)
    assertTrue(ups.size >= 2, "needs a multi-level award, got ${ups.size}")
    assertEquals(ann.level - 1, ups.size)
    ups.forEachIndexed { i, c ->
      assertEquals(i + 1, c.fields["old_level"])
      assertEquals(i + 2, c.fields["new_level"])
    }
    calls.clear()
    assertEquals(1, f.w.levelDown(ann))
    val down = f.of(TraitEvent.LEVEL_CHANGED).single()
    assertEquals(ann.level + 1, down.fields["old_level"])
    assertEquals(ann.level, down.fields["new_level"])
  }

  @Test
  fun `level_changed doesn't fire with leveling off`() {
    val f = Fixture(features = Features(Features.ALL.enabled - Feature.LEVELING))
    f.trait("watcher", TraitEvent.LEVEL_CHANGED)
    val ann = f.player("Ann")
    ann.level = 2
    f.w.grantTrait(ann, "watcher")
    f.w.levelDownStep(ann)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `room_entered fires on a walk with the direction and the room left`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.ROOM_ENTERED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.input(ann, "north")
    f.run()
    val call = f.of(TraitEvent.ROOM_ENTERED).single()
    assertEquals(ann, call.bearer)
    assertEquals(f.w.room(T.HALL), call.fields["from_room"])
    assertEquals(Direction.NORTH.name, call.fields["direction"])
    assertEquals(T.YARD, ann.room.label)
  }

  @Test
  fun `room_entered fires on a flee`() {
    val f = Fixture(rules = FixedRules(flee = 100))
    f.trait("watcher", TraitEvent.ROOM_ENTERED)
    val ann = f.player("Ann")
    val dummy = f.mob(T.DUMMY)
    f.w.grantTrait(ann, "watcher")
    f.w.startCombat(ann, dummy)
    f.input(ann, "flee")
    f.run(2)
    val call = f.of(TraitEvent.ROOM_ENTERED).single()
    assertEquals(f.w.room(T.HALL), call.fields["from_room"])
    assertTrue(call.fields["direction"] is String)
  }

  @Test
  fun `room_entered fires on a teleport with a null direction`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.ROOM_ENTERED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.teleport(ann, f.w.room(T.YARD))
    f.run()
    val call = f.of(TraitEvent.ROOM_ENTERED).single()
    assertEquals(f.w.room(T.HALL), call.fields["from_room"])
    assertNull(call.fields["direction"])
  }

  @Test
  fun `room_entered doesn't fire on a refused move`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_EXIT] = { _, _, _ -> HookOutcome.Deny("No.") }
    val f = Fixture(scripts = host)
    f.trait("watcher", TraitEvent.ROOM_ENTERED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.input(ann, "north")
    f.run()
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `room_entered doesn't fire on respawn`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.ROOM_ENTERED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.teleport(ann, f.w.room(T.YARD))
    f.run()
    calls.clear()
    f.hurt(ann, ann.current(f.fs.health) + 1)
    f.run(2)
    assertEquals(T.HALL, ann.room.label, "the test needs the respawn to move Ann")
    assertTrue(calls.isEmpty())
  }
}
