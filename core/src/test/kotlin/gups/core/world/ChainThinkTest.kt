package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Actor
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.ThinkOrigin
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FixedRules] that pins `move_pace` and overrides `chain_think` with [chain]. */
private class PaceChain(val pace: Int, val chain: ((RulesContext) -> Boolean?)? = null) :
  FixedRules() {
  override fun movePace(c: RulesContext): Int? = pace

  override fun chainThink(c: RulesContext): Boolean? = chain?.invoke(c)
}

/** [FixedRules] whose `move_pace` depends on the actor, so mobs and players can differ. */
private class ActorPace(val pace: (Actor) -> Int) : FixedRules() {
  override fun movePace(c: RulesContext): Int? = c.actor?.let(pace)
}

/**
 * Chained thinks: when an action a mob's think chose finishes, `chain_think` decides whether the
 * mob thinks again as soon as it can act, ignoring `think_every`, so chasers keep up at their pace
 * instead of their cadence.
 */
class ChainThinkTest {
  private fun protosWith(vararg mobs: MobProto): Prototypes {
    val c = C.protos(n = 10)
    return Prototypes(
      c.rooms.values.toList(),
      c.mobs.values.toList() + mobs,
      zones = c.zones.values.toList(),
    )
  }

  private fun mob(
    name: String,
    vararg behaviors: Behavior,
    think: ThinkFn? = null,
    every: Int = 1,
  ) =
    MobProto(
      Label("//c:$name"),
      name,
      listOf(name),
      vitals = mapOf(FixtureSchemas.current.health to 40),
      behaviors = behaviors.toList(),
      think = think,
      thinkEvery = every,
    )

  @Test
  fun `a pursuer catches up at its pace, and stops catching up when chain_think is false`() {
    fun run(chain: ((RulesContext) -> Boolean?)?): Mob {
      val f =
        Fixture(
          rules = PaceChain(50, chain),
          protos = protosWith(mob("hunter", Pursue(ActorFilter("players")), every = 4)),
        )
      val m = f.w.spawnMob(Label("//c:hunter"), f.w.room(C.r(0)))
      val p = f.player("Ann", at = C.r(3))
      repeat(6) { f.input(p, "east") }
      f.run(6)
      return m
    }
    val caught = run(null)
    assertEquals(C.r(9), caught.room.label, "the chained chaser caught up to the walker")
    val behind = run({ false })
    assertTrue(
      behind.room.label.name.drop(1).toInt() < 9,
      "the unchained chaser is still behind at ${behind.room.label}",
    )
  }

  @Test
  fun `a heel pet at the owner's pace holds the gap and does not open it`() {
    val f = Fixture(rules = PaceChain(100), protos = protosWith(mob("pet", Heel(), every = 4)))
    val m = f.w.spawnMob(Label("//c:pet"), f.w.room(C.r(0)))
    val owner = f.player("Ann", at = C.r(0))
    f.w.setOwner(m, owner)
    fun idx(label: Label) = label.name.drop(1).toInt()
    var maxGap = 0
    repeat(9) {
      f.input(owner, "east")
      f.run()
      maxGap = maxOf(maxGap, idx(owner.room.label) - idx(m.room.label))
    }
    assertTrue(maxGap <= 5, "the gap never exceeds think_every + 1; it was $maxGap")
    f.run(6)
    assertEquals(
      owner.room.label,
      m.room.label,
      "the pet catches up within think_every + 1 of the owner stopping",
    )
  }

  @Test
  fun `wander does not chain by default`() {
    val f =
      Fixture(
        rules = PaceChain(50),
        protos = protosWith(mob("wanderer", Wander(radius = 3, chance = 100), every = 4)),
      )
    val m = f.w.spawnMob(Label("//c:wanderer"), f.w.room(C.r(4)))
    f.run()
    assertTrue(m.room.label != C.r(4), "the wanderer moved on its think")
    assertTrue(!m.chainDue, "a wander step doesn't chain")
    val roomAfter1 = m.room.label
    f.run()
    assertEquals(roomAfter1, m.room.label, "a wander holds on the next pulse rather than chaining")
  }

  @Test
  fun `the ask sees the behavior, command, exit and a rising chain`() {
    val seen = mutableListOf<String>()
    val f =
      Fixture(
        rules =
          PaceChain(50) { c ->
            seen += "${c.behavior}|${c.command}|${c.exit?.to}|${c.chain}"
            true
          },
        protos = protosWith(mob("hunter", Pursue(ActorFilter("players")), every = 4)),
      )
    val m = f.w.spawnMob(Label("//c:hunter"), f.w.room(C.r(0)))
    val p = f.player("Ann", at = C.r(6))
    repeat(6) { f.input(p, "east") }
    f.run(6)
    assertTrue(seen.isNotEmpty(), "the chained asks ran")
    val first = seen.first().split('|')
    assertEquals("pursue", first[0], "the behavior is pursue")
    assertEquals("east", first[1], "the command is the direction walked")
    assertEquals(C.r(1).toString(), first[2], "the exit is the one just taken, out of r0")
    val chains = seen.map { it.split('|')[3].toInt() }.distinct().sorted()
    assertTrue(
      chains.first() == 0 && chains.contains(1),
      "chain counts 0, 1, 2, ... across the chain",
    )
  }

  @Test
  fun `a denied move is not asked and the mob is back on its cadence`() {
    var asks = 0
    val think = ThinkFn { w, mob, _ ->
      w.act(mob, "east")
      ThinkResult.Idle
    }
    val f =
      Fixture(
        rules =
          PaceChain(50) {
            asks++
            true
          },
        protos = protosWith(mob("walker", think = think, every = 4)),
      )
    // r9 has no exit east, so the queued walk is always refused: it must never be asked.
    val m = f.w.spawnMob(Label("//c:walker"), f.w.room(C.r(9)))
    f.run(16)
    assertEquals(0, asks, "a refused move asks nothing")
  }

  @Test
  fun `a warmup action is asked at completion, with no exit`() {
    var exitSeen: Label? = null
    var asked = false
    val f =
      Fixture(
        rules =
          PaceChain(100) { c ->
            asked = true
            exitSeen = c.exit?.to
            true
          },
        protos = protosWith(mob("dummy")),
      )
    val m = f.w.spawnMob(Label("//c:dummy"), f.w.room(C.r(0)))
    f.w.execute(
      m,
      object : Action {
        override val warmup: Int = 2

        override fun validate(w: World, actor: Actor): String? = null

        override fun resolve(w: World, actor: Actor): Int = 0
      },
      origin = ThinkOrigin("think", "cast", false),
    )
    f.run(3)
    assertTrue(asked, "the ask came when the warmup resolved")
    assertEquals(null, exitSeen, "a non-move ask carries no exit")
  }

  @Test
  fun `a warmup paused by a stun is still asked at completion`() {
    var asked = false
    val f =
      Fixture(
        rules =
          PaceChain(100) {
            asked = true
            true
          },
        protos = protosWith(mob("dummy")),
      )
    val m = f.w.spawnMob(Label("//c:dummy"), f.w.room(C.r(0)))
    f.w.execute(
      m,
      object : Action {
        override val warmup: Int = 3

        override fun validate(w: World, actor: Actor): String? = null

        override fun resolve(w: World, actor: Actor): Int = 0
      },
      origin = ThinkOrigin("think", "cast", false),
    )
    f.w.applyAffect(m, AffectSpec("stun", 2, flags = setOf("stunned")))
    f.run(8)
    assertTrue(asked, "the ask came when the paused warmup resolved")
  }

  @Test
  fun `a quarry is caught in an extra round and stays caught, in either id order`() {
    for (mobFirst in listOf(true, false)) {
      val f =
        Fixture(
          rules = ActorPace { if (it is Mob) 50 else 100 },
          protos =
            protosWith(
              mob(
                "hunter",
                Aggressive(ActorFilter("players")),
                Pursue(ActorFilter("players")),
                every = 4,
              )
            ),
        )
      val m: Mob
      val p: Player
      if (mobFirst) {
        m = f.w.spawnMob(Label("//c:hunter"), f.w.room(C.r(0)))
        p = f.player("Ann", at = C.r(3))
      } else {
        p = f.player("Ann", at = C.r(3))
        m = f.w.spawnMob(Label("//c:hunter"), f.w.room(C.r(0)))
      }
      repeat(6) { f.input(p, "east") }
      f.run(8)
      val order = if (mobFirst) "mob id lower" else "mob id higher"
      assertTrue(p.room.label != C.r(9), "the walker was stopped short of r9, $order")
      assertEquals(p.room.label, m.room.label, "the hunter is with its quarry, $order")
      assertTrue(m.fighting.contains(p.id), "the two are engaged, $order")
      assertContains(f.out(p), "pinned", message = "a later walk was refused as pinned, $order")
    }
  }

  @Test
  fun `a chained non-move action ends the extra rounds for the pulse`() {
    val thinks = mutableListOf<Pair<Long, String?>>()
    val think = ThinkFn { w, _, proposals ->
      thinks += w.pulse to proposals.firstOrNull()?.source
      ThinkResult.Accept
    }
    val f =
      Fixture(
        rules = PaceChain(25) { true },
        protos =
          protosWith(
            mob(
              "hunter",
              Aggressive(ActorFilter("players")),
              Pursue(ActorFilter("players")),
              think = think,
              every = 4,
            )
          ),
      )
    val m = f.w.spawnMob(Label("//c:hunter"), f.w.room(C.r(0)))
    val p = f.player("Ann", at = C.r(2))
    f.run(2)
    val attack = thinks.indexOfFirst { it.second == "aggressive" }
    assertTrue(attack >= 0, "the hunter attacked: $thinks")
    val pulse = thinks[attack].first
    assertTrue(
      thinks.indexOfFirst { it.first == pulse } < attack,
      "the attack came from a chained think in an extra round: $thinks",
    )
    assertEquals(
      attack,
      thinks.indexOfLast { it.first == pulse },
      "no think follows the attack in its pulse, though chain_think is always true: $thinks",
    )
    assertTrue(m.fighting.contains(p.id))
  }

  @Test
  fun `an actor lagged earlier in an extra round sits that round out`() {
    lateinit var walker: Player
    var thinks = 0
    val think = ThinkFn { w, mob, _ ->
      // The chained think in the first extra round stuns the walker before its turn comes.
      if (++thinks == 2) walker.waitState = 3
      w.act(mob, "east")
      ThinkResult.Idle
    }
    val f =
      Fixture(
        rules = PaceChain(50) { true },
        protos = protosWith(mob("stunner", think = think, every = 4)),
      )
    f.w.spawnMob(Label("//c:stunner"), f.w.room(C.r(5)))
    walker = f.player("Ann", at = C.r(0))
    repeat(4) { f.input(walker, "east") }
    f.run()
    assertEquals(2, thinks, "the stunner chained into the first extra round")
    assertEquals(C.r(1), walker.room.label, "the lagged walker takes no extra move")
  }

  @Test
  fun `an actor whose queue is cleared earlier in an extra round sits that round out`() {
    lateinit var walker: Player
    var thinks = 0
    val think = ThinkFn { w, mob, _ ->
      if (++thinks == 2) walker.actionQueue.clear()
      w.act(mob, "east")
      ThinkResult.Idle
    }
    val f =
      Fixture(
        rules = PaceChain(50) { true },
        protos = protosWith(mob("clearer", think = think, every = 4)),
      )
    f.w.spawnMob(Label("//c:clearer"), f.w.room(C.r(5)))
    walker = f.player("Ann", at = C.r(0))
    repeat(4) { f.input(walker, "east") }
    f.run()
    assertEquals(2, thinks, "the clearer chained into the first extra round")
    assertEquals(C.r(1), walker.room.label, "with nothing queued, the walker stays")
  }

  @Test
  fun `the ask sees the room the mob just entered`() {
    var roomAtAsk: Label? = null
    val f =
      Fixture(
        rules =
          PaceChain(50) { c ->
            roomAtAsk = c.room?.label
            true
          },
        protos = protosWith(mob("hunter", Pursue(ActorFilter("players")), every = 4)),
      )
    val m = f.w.spawnMob(Label("//c:hunter"), f.w.room(C.r(0)))
    f.player("Ann", at = C.r(6))
    f.run(6)
    assertEquals(m.room.label, roomAtAsk, "the last ask saw the room the mob is in")
  }

  @Test
  fun `a walk cancelled by a hostile pin asks nothing, in either id order`() {
    for (mobFirst in listOf(true, false)) {
      var asks = 0
      val think = ThinkFn { w, mob, _ ->
        w.act(mob, "east")
        ThinkResult.Idle
      }
      val f =
        Fixture(
          rules =
            PaceChain(100) {
              asks++
              true
            },
          protos = protosWith(mob("walker", think = think, every = 4)),
        )
      val m: Mob
      val p: Player
      if (mobFirst) {
        m = f.w.spawnMob(Label("//c:walker"), f.w.room(C.r(0)))
        p = f.player("Ann", at = C.r(0))
      } else {
        p = f.player("Ann", at = C.r(0))
        m = f.w.spawnMob(Label("//c:walker"), f.w.room(C.r(0)))
      }
      f.input(p, "kill walker")
      f.run()
      val order = if (mobFirst) "mob id lower" else "mob id higher"
      assertEquals(C.r(0), m.room.label, "pinned in its tier, the walk is cancelled, $order")
      assertEquals(0, asks, "a cancelled walk asks nothing, $order")
      assertTrue(!m.chainDue, "the mob is back on its cadence, $order")
    }
  }
}
