package gups.core.world

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.MobProto
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.StartingStats
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class VitalsTest(private val fs: FixtureSchema) {
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

  /** A vital that is not the first, so a hardcoded first vital would miss it. */
  private val last = fs.vitals.last()

  private fun rules(configure: FixedRules.() -> Unit = {}) =
    FixedRules(fs, damage = 5).apply(configure)

  // ---- changeVital -----------------------------------------------------------------------

  @Test
  fun `changeVital clamps at the maximum and allows negatives`() {
    val f = Fixture(rules = rules { conditionOf = { Condition.OK } })
    val g = f.mob(T.DUMMY)
    f.w.changeVital(g, fs.health, 500)
    assertEquals(1000, g.current(fs.health), "healing stops at the maximum")
    f.w.changeVital(g, fs.health, -1500)
    assertEquals(-500, g.current(fs.health), "there is no floor")
    assertEquals(1000, g.max(fs.health))
  }

  @Test
  fun `belowZeroSince is set on dropping below zero and cleared at zero or more`() {
    val f = Fixture(rules = rules { conditionOf = { Condition.OK } })
    val g = f.mob(T.DUMMY)
    f.run(5)
    f.w.changeVital(g, fs.health, -1000)
    assertEquals(0, g.current(fs.health))
    assertNull(g.vitals.getValue(fs.health).belowZeroSince, "exactly 0 is not below 0")
    f.w.changeVital(g, fs.health, -1)
    assertEquals(5L, g.vitals.getValue(fs.health).belowZeroSince)
    f.run(3)
    f.w.changeVital(g, fs.health, -10)
    assertEquals(5L, g.vitals.getValue(fs.health).belowZeroSince, "it keeps the first pulse")
    f.w.changeVital(g, fs.health, 11)
    assertEquals(0, g.current(fs.health))
    assertNull(g.vitals.getValue(fs.health).belowZeroSince, "back to 0 clears it")
  }

  @Test
  fun `each change asks condition exactly once`() {
    val rules = rules()
    val f = Fixture(rules = rules)
    val g = f.mob(T.DUMMY)
    rules.conditionCalls.clear()
    f.w.changeVital(g, fs.health, -5)
    assertEquals(1, rules.conditionCalls[g.name])
    f.w.changeVital(g, fs.health, 5)
    assertEquals(2, rules.conditionCalls[g.name])
  }

  // ---- Incapacitation ----------------------------------------------------------------------

  private fun incapacitating(): FixedRules = rules {
    conditionOf = { c ->
      when {
        c.actor.current(fs.health) <= -10 -> Condition.DEAD
        c.actor.current(fs.health) <= 0 -> Condition.INCAPACITATED
        else -> Condition.OK
      }
    }
  }

  @Test
  fun `an incapacitated actor is told, the room sees it, and it can't use tick-lane commands`() {
    val f = Fixture(rules = incapacitating())
    val p = f.player("Ann")
    val bob = f.player("Bob")
    f.out(p)
    f.out(bob)
    f.w.changeVital(p, fs.health, -p.current(fs.health))
    f.run()
    assertEquals(Condition.INCAPACITATED, p.condition)
    assertContains(f.out(p), "You are incapacitated.")
    assertContains(f.out(bob), "Ann collapses.")
    f.input(p, "north")
    assertContains(f.out(p), "You can't do that while incapacitated.")
    assertTrue(p.actionQueue.isEmpty())
    f.input(p, "look")
    assertContains(f.out(p), "The Hall")
  }

  @Test
  fun `an incapacitated actor makes no auto-attacks but stays engaged and can be targeted`() {
    val f = Fixture(rules = incapacitating())
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(p, g)
    f.w.changeVital(p, fs.health, -p.current(fs.health))
    f.runTo(8)
    assertEquals(1000, g.current(fs.health), "it never swung")
    assertTrue(p.isEngaged && g.isEngaged, "the fight goes on")
    assertEquals(-5, p.current(fs.health), "the dummy's swing still reaches it")
  }

  @Test
  fun `incapacitation interrupts a cast, holds recovery and keeps the queue`() {
    val f = Fixture(rules = incapacitating())
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob(T.DUMMY, index = 2)
    f.input(p, "cast bolt dummy")
    f.run(2)
    assertTrue(p.isCasting)
    f.input(p, "north")
    f.input(p, "north")
    val queued = p.actionQueue.size
    f.w.changeVital(p, fs.health, -p.current(fs.health))
    f.run()
    assertFalse(p.isCasting, "a stun-interrupt cast is broken")
    assertContains(f.out(p), "Your concentration is broken!")
    assertEquals(0, p.waitState, "the half lag of the broken cast is held")
    assertEquals(queued, p.actionQueue.size, "the queue is kept")
    f.w.changeVital(p, fs.health, 5)
    f.run()
    assertEquals(Condition.OK, p.condition)
    assertEquals(4 / 2 - 1, p.waitState, "the held recovery is restored")
    assertContains(f.out(p), "You come to.")
    assertEquals(1000, g.current(fs.health))
  }

  @Test
  fun `an incapacitated actor regenerates and wakes with a message to the room`() {
    val f = Fixture(rules = incapacitating().also { it.regenOf = { mapOf(fs.health to 6) } })
    val p = f.player("Ann")
    val bob = f.player("Bob")
    f.w.changeVital(p, fs.health, -p.current(fs.health))
    f.out(p)
    f.out(bob)
    f.runTo(120)
    assertEquals(6, p.current(fs.health))
    assertEquals(Condition.OK, p.condition)
    assertContains(f.out(p), "You come to.")
    assertContains(f.out(bob), "Ann stirs.")
  }

  // ---- Death ----------------------------------------------------------------------------------

  private fun lastVitalKills(): FixedRules = rules {
    conditionOf = { c -> if (c.actor.current(last) <= 0) Condition.DEAD else Condition.OK }
  }

  @Test
  fun `a vital other than the first can kill outside combat, crediting the last damager`() {
    val f = Fixture(rules = lastVitalKills())
    val p = f.player("Ann")
    val g = f.mob(T.GOBLIN)
    assertFalse(g.isEngaged)
    f.w.damage(g, mapOf(last to 1000), DamageType(fs.unarmed), p)
    assertFalse(g.inWorld, "the goblin died of its $last")
    assertEquals(100L, p.xp, "the last damager gets the kill")
    assertEquals(1, f.w.room(T.HALL).items.count { it.name == "the corpse of a goblin" })
  }

  @Test
  fun `negative regen kills outside combat`() {
    val f = Fixture(rules = lastVitalKills().also { it.regenOf = { mapOf(last to -1000) } })
    val g = f.mob(T.GOBLIN)
    f.runTo(119)
    assertTrue(g.inWorld)
    f.runTo(120)
    assertFalse(g.inWorld, "it died at the world tick")
  }

  @Test
  fun `an affect tick that lowers a vital kills`() {
    val host = RecordingHost()
    host.refHandlers["rot"] = { w, owner, _ ->
      w.changeVital(owner as gups.core.model.Actor, last, -1000)
    }
    val f = Fixture(rules = lastVitalKills(), scripts = host)
    val g = f.mob(T.GOBLIN)
    f.w.applyAffect(g, "rot", 1000, tickEvery = 10, onTick = mod("rot"))
    f.runTo(9)
    assertTrue(g.inWorld)
    f.runTo(10)
    assertFalse(g.inWorld)
  }

  @Test
  fun `a condition that uses belowZeroSince fires on the right world tick`() {
    val f =
      Fixture(
        rules =
          rules {
            regenOf = { emptyMap() }
            conditionOf = { c ->
              val since = c.actor.vitals.getValue(last).belowZeroSince
              if (since != null && c.world.pulse - since >= 60) Condition.DEAD else Condition.OK
            }
          }
      )
    val g = f.mob(T.GOBLIN)
    f.runTo(100)
    f.w.changeVital(g, last, -g.current(last) - 1)
    f.runTo(120)
    assertTrue(g.inWorld, "only 20 pulses below zero at the first tick")
    f.runTo(239)
    assertTrue(g.inWorld)
    f.runTo(240)
    assertFalse(g.inWorld, "the next world tick sees 140 pulses")
  }

  @Test
  fun `a same-tier heal of the killing vital saves a mortally wounded actor`() {
    val f = Fixture(rules = rules())
    val g = f.mob(T.GOBLIN)
    val saved = f.mob(T.GOBLIN)
    f.w.runTiers(listOf(g, saved)) { a ->
      if (a === saved) {
        f.w.changeVital(a, fs.health, -100)
        assertEquals(Condition.DEAD, a.condition, "mortally wounded until the tier ends")
        assertTrue(a.inWorld)
        f.w.heal(a, fs.health, 100)
      } else {
        f.w.changeVital(a, fs.health, -100)
      }
    }
    assertFalse(g.inWorld, "unhealed, it dies when the tier ends")
    assertTrue(saved.inWorld, "healed above 0 before the tier ended")
    assertEquals(Condition.OK, saved.condition)
  }

  // ---- Respawn --------------------------------------------------------------------------------

  @Test
  fun `a respawned player gets the vitals on_player_death returns`() {
    val f =
      Fixture(
        rules =
          rules {
            deathOf = { c ->
              PlayerDeathOutcome(
                (c.actor as gups.core.model.Player).recallRoom,
                mapOf(fs.health to 7),
                0,
              )
            }
          }
      )
    val p = f.player("Ann")
    p.recallRoom = T.YARD
    f.w.changeVital(p, fs.health, -1000)
    assertTrue(p.inWorld)
    assertEquals(T.YARD, p.room.label)
    assertEquals(7, p.current(fs.health))
    assertEquals(Condition.OK, p.condition)
    assertEquals(
      p.max(fs.energy),
      p.current(fs.energy),
      "the vitals it leaves out go to their maximum",
    )
  }

  @Test
  fun `a respawn that is still dead is a rules error and fills every vital`() {
    val f =
      Fixture(
        rules =
          rules {
            deathOf = { c ->
              PlayerDeathOutcome(
                (c.actor as gups.core.model.Player).recallRoom,
                mapOf(fs.health to -5),
                0,
              )
            }
          }
      )
    val p = f.player("Ann")
    f.w.changeVital(p, fs.health, -1000)
    assertEquals(Condition.OK, p.condition)
    for (name in fs.vitals) assertEquals(p.max(name), p.current(name), name)
    assertTrue(f.w.diagnostics.any { it.kind == "rules" }, "the rules error is recorded")
  }

  // ---- Damage events -------------------------------------------------------------------------

  @Test
  fun `mitigate losses on two vitals are both applied and on_damage sees the total and breakdown`() {
    val host = RecordingHost()
    var seen: Pair<Int?, Map<String, Int>?>? = null
    host.handlers[HookName.ON_DAMAGE] = { _, owner, ctx ->
      if (owner === ctx.target) seen = ctx.amount to ctx.losses
      null
    }
    val f =
      Fixture(
        rules =
          rules {
            mitigation = { _, amount, _ -> mapOf(fs.health to amount, fs.energy to amount / 2) }
          },
        scripts = host,
      )
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.w.applyEffect(Effect.Damage(Dice(1, 1, 9), DamageType(fs.magic)), p, g)
    assertEquals(1000 - 10, g.current(fs.health))
    assertEquals(g.max(fs.energy) - 5, g.current(fs.energy))
    assertEquals(15 to mapOf(fs.health to 10, fs.energy to 5), seen)
  }

  @Test
  fun `a raw damage verb lowers one vital and reports just that loss`() {
    val host = RecordingHost()
    var losses: Map<String, Int>? = null
    host.handlers[HookName.ON_DAMAGE] = { _, owner, ctx ->
      if (owner === ctx.target) losses = ctx.losses
      null
    }
    val f = Fixture(scripts = host)
    val g = f.mob(T.DUMMY)
    f.w.damage(g, mapOf(fs.energy to 4), DamageType(fs.magic))
    assertEquals(mapOf(fs.energy to 4), losses)
    assertEquals(g.max(fs.energy) - 4, g.current(fs.energy))
    assertEquals(1000, g.current(fs.health))
  }

  // ---- Costs and movement -----------------------------------------------------------------

  @Test
  fun `a skill cost on another vital is refused when short, naming the vital, and paid at resolve`() {
    val f = Fixture(rules = rules {})
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    f.mob(T.DUMMY, index = 2)
    f.setVital(p, fs.energy, 5)
    f.out(p)
    f.input(p, "cast bolt dummy")
    f.run()
    assertContains(f.out(p), "You don't have enough ${fs.schema.vitalLabel(fs.energy)}.")
    f.setVital(p, fs.energy, 50)
    f.input(p, "cast bolt dummy")
    f.run(2)
    assertEquals(50, p.current(fs.energy), "nothing is paid during the warmup")
    f.run(4)
    assertEquals(40, p.current(fs.energy))
  }

  @Test
  fun `move_cost spends several vitals and refuses when any is short`() {
    val f = Fixture(rules = rules { moveCostOf = { mapOf(fs.stamina to 1, fs.health to 2) } })
    val p = f.player("Ann")
    val before = p.current(fs.stamina)
    f.input(p, "north")
    f.run()
    assertEquals(T.YARD, p.room.label)
    assertEquals(before - 1, p.current(fs.stamina))
    assertEquals(48, p.current(fs.health))
    f.setVital(p, fs.health, 1)
    f.out(p)
    f.input(p, "south")
    f.run()
    assertEquals("You are too exhausted.\n", f.out(p))
    assertEquals(T.YARD, p.room.label)
  }

  // ---- starting_stats -------------------------------------------------------------------------

  @Test
  fun `a new player gets the numbers starting_stats gives, a vital that starts empty included`() {
    val f = Fixture(rules = rules())
    val p = f.w.spawnPlayer("Zed", T.HALL)
    for ((name, max) in fs.playerMax) {
      assertEquals(max, p.max(name), name)
      assertEquals(if (name in fs.startsEmpty) 0 else max, p.current(name), name)
    }
    for (stat in fs.stats) assertEquals(if (stat == fs.armor) 0 else 10, p.stat(stat), stat)
  }

  @Test
  fun `a mob spawn overrides the prototype's declared values and a missing current equals the maximum`() {
    val mobs = Label("//t:ogre")
    val proto = MobProto(mobs, "an ogre", listOf("ogre"), vitals = mapOf(fs.health to 33))
    val rules = rules {
      startingOf = { c -> StartingStats(max = mapOf(fs.health to c.actor.max(fs.health) * 2)) }
    }
    val f = Fixture(rules = rules, protos = T.protos(extraMobs = listOf(proto)))
    val ogre = f.mob(mobs)
    assertEquals(66, ogre.max(fs.health), "the rules override the declared maximum")
    assertEquals(66, ogre.current(fs.health), "a current value it leaves out equals the maximum")
    rules.startingOf = {
      StartingStats(max = mapOf(fs.health to 40), current = mapOf(fs.health to 12))
    }
    val hurt = f.mob(mobs)
    assertEquals(40, hurt.max(fs.health))
    assertEquals(12, hurt.current(fs.health))
  }

  @Test
  fun `starting_stats runs before the actor enters the world`() {
    var inWorld: Boolean? = null
    val f =
      Fixture(
        rules =
          rules {
            startingOf = { c ->
              inWorld = c.actor.inWorld
              StartingStats()
            }
          }
      )
    val g = f.mob(T.GOBLIN)
    assertEquals(false, inWorld)
    assertTrue(g.inWorld)
  }

  @Test
  fun `a spawn's condition is settled at once, and a dead start is a rules error that fills it`() {
    val rules = rules {
      conditionOf = { c ->
        val now = c.actor.current(fs.health)
        when {
          now <= 0 -> Condition.DEAD
          now < 10 -> Condition.INCAPACITATED
          else -> Condition.OK
        }
      }
      startingOf = { StartingStats(max = mapOf(fs.health to 50), current = mapOf(fs.health to 5)) }
    }
    val f = Fixture(rules = rules)
    val weak = f.mob(T.DUMMY)
    assertEquals(Condition.INCAPACITATED, weak.condition)
    assertFalse(f.w.canAct(weak))
    rules.startingOf = {
      StartingStats(max = mapOf(fs.health to 50), current = mapOf(fs.health to 0))
    }
    val dead = f.mob(T.DUMMY)
    assertEquals(Condition.OK, dead.condition)
    assertEquals(50, dead.current(fs.health))
    assertTrue(
      f.w.diagnostics.any { it.kind == "rules" && "starting_stats" in it.message },
      "the rules error is recorded",
    )
  }

  @Test
  fun `an actor restored dead dies at the next world tick`() {
    val f = Fixture(rules = rules { regenOf = { emptyMap() } })
    val g = f.mob(T.DUMMY)
    f.w.restoreActorNumbers(
      g,
      g.baseStats.toMap(),
      mapOf(fs.health to VitalState(0, g.max(fs.health))),
      g.resists.toMap(),
      Condition.DEAD,
    )
    assertTrue(g.inWorld)
    f.run(WORLD_TICK_PULSES)
    assertFalse(g.inWorld, "the pending death was processed")
  }

  @Test
  fun `a vital driven far below zero is diagnosed once`() {
    val f = Fixture(rules = rules { conditionOf = { Condition.OK } })
    val g = f.mob(T.DUMMY)
    f.w.changeVital(g, fs.health, -100_000)
    f.w.changeVital(g, fs.health, -100_000)
    assertEquals(1, f.w.diagnostics.count { "below -65535" in it.message })
  }
}
