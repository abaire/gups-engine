package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Direction
import gups.core.model.DormantRecovery
import gups.core.model.ExitProto
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.ZoneProto
import gups.core.rules.Regen
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A corridor `//c:r0` – `//c:r9`, east/west, plus a rat and a crumb. */
object C {
  fun r(i: Int) = Label("//c:r$i")

  val RAT = Label("//c:rat")
  val CRUMB = Label("//c:crumb")

  fun protos(
    n: Int = 10,
    recovery: DormantRecovery = DormantRecovery.FULL,
    flags: Map<Int, Set<String>> = emptyMap(),
    spawns: Map<Int, List<SpawnRule>> = emptyMap(),
    resetPulses: Int? = null,
    think: ThinkFn? = null,
  ) =
    Prototypes(
      rooms =
        (0 until n).map { i ->
          RoomProto(
            r(i),
            "Room $i",
            exits =
              listOfNotNull(
                if (i > 0) ExitProto(Direction.WEST, r(i - 1)) else null,
                if (i < n - 1) ExitProto(Direction.EAST, r(i + 1)) else null,
              ),
            flags = flags[i].orEmpty(),
            spawns = spawns[i].orEmpty(),
          )
        },
      mobs =
        listOf(
          MobProto(RAT, "a rat", listOf("rat"), hp = 100, dormantRecovery = recovery, think = think)
        ),
      items = listOf(ItemProto(CRUMB, "a crumb", listOf("crumb"))),
      zones = listOf(ZoneProto("c", resetPulses = resetPulses)),
    )
}

class BubbleTest {
  private fun active(f: Fixture) = f.w.rooms.filter { !it.isDormant }.map { it.label.name }.toSet()

  @Test
  fun `only rooms within three exits of a player are simulated, and the bubble follows them`() {
    val f = Fixture(protos = C.protos(flags = mapOf(9 to setOf("always_active"))), bubble = true)
    assertEquals(setOf("r9"), active(f))
    val p = f.player("Ann", at = C.r(0))
    f.run()
    assertEquals(setOf("r0", "r1", "r2", "r3", "r9"), active(f))
    f.w.teleport(p, f.w.room(C.r(5)))
    f.run()
    assertEquals(setOf("r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9"), active(f))
    f.w.quit(p)
    f.run()
    assertEquals(setOf("r9"), active(f))
  }

  @Test
  fun `incremental upkeep matches a full recompute through random walks`() {
    val rnd = Random(7)
    val n = 30
    // A tangle of rooms: a corridor plus random one-way exits.
    val rooms =
      (0 until n).map { i ->
        val exits = mutableListOf<ExitProto>()
        if (i > 0) exits += ExitProto(Direction.WEST, C.r(i - 1))
        if (i < n - 1) exits += ExitProto(Direction.EAST, C.r(i + 1))
        repeat(2) { k ->
          exits += ExitProto(Direction("door$k"), C.r(rnd.nextInt(n)), oneWay = true)
        }
        RoomProto(C.r(i), "Room $i", exits = exits)
      }
    val f =
      Fixture(
        protos = Prototypes(rooms, items = listOf(ItemProto(C.CRUMB, "a crumb", listOf("crumb")))),
        bubble = true,
      )
    val players = (0 until 3).map { f.player("P$it", at = C.r(rnd.nextInt(n))) }
    val crumb = f.w.spawnItem(C.CRUMB, ItemLocation.InRoom(f.w.room(C.r(0)).id, 0))
    repeat(200) {
      when (rnd.nextInt(4)) {
        0 -> f.w.teleport(players.random(rnd), f.w.room(C.r(rnd.nextInt(n))))
        1 -> f.w.setAnchor(crumb, "a${rnd.nextInt(2)}")
        2 -> f.w.clearAnchor(crumb, "a${rnd.nextInt(2)}")
        3 -> f.w.moveItem(crumb, ItemLocation.InRoom(f.w.room(C.r(rnd.nextInt(n))).id, 0))
      }
      f.run()
      assertEquals(f.w.activeRoomsFromScratch(), f.w.activeRooms)
    }
  }

  @Test
  fun `timers pause while dormant and resume on waking`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    val rat = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    val ref = HookRef(Label("//c:t.star"), "tick")
    f.w.after(rat, 10, ref)
    assertNull(rat.timers.single().due)
    assertEquals(10, rat.timers.single().remaining)
    f.runTo(50)
    val p = f.player("Ann", at = C.r(9))
    f.run() // pulse 51: wakes at phase 6
    assertEquals(61L, rat.timers.single().due)
    f.w.teleport(p, f.w.room(C.r(0)))
    f.runTo(55) // r9 sleeps at pulse 52, with 9 pulses left
    assertEquals(9, rat.timers.single().remaining)
    assertNull(rat.timers.single().due)
  }

  @Test
  fun `dormant mobs neither think nor fight, and an active one keeps going`() {
    var thoughts = 0
    val f =
      Fixture(
        protos =
          C.protos(
            think =
              ThinkFn { _, _, _ ->
                thoughts++
                ThinkResult.Idle
              }
          ),
        bubble = true,
      )
    val a = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    val b = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    f.runTo(20)
    assertEquals(0, thoughts)
    val p = f.player("Ann", at = C.r(9))
    f.run()
    f.w.startCombat(a, b)
    f.runTo(30)
    assertTrue(thoughts > 0)
    f.w.teleport(p, f.w.room(C.r(0)))
    f.run()
    assertTrue(f.w.room(C.r(9)).isDormant)
    assertFalse(a.isEngaged || b.isEngaged, "a room going dormant ends its fights")
    val before = thoughts
    f.runTo(60)
    assertEquals(before, thoughts)
    f.w.setActive(a, "test")
    f.runTo(70)
    assertTrue(thoughts > before, "an active mob thinks even in a dormant room")
    assertTrue(f.w.room(C.r(9)).isDormant, "active does not wake the room")
  }

  @Test
  fun `an anchor projects a bubble of its own`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    val crumb = f.w.spawnItem(C.CRUMB, ItemLocation.InRoom(f.w.room(C.r(9)).id, 0))
    f.w.setAnchor(crumb, "quest:1")
    f.run()
    assertEquals(setOf("r6", "r7", "r8", "r9"), active(f))
    f.w.setAnchor(crumb, "quest:2")
    f.w.clearAnchor(crumb, "quest:1")
    f.run()
    assertEquals(setOf("r6", "r7", "r8", "r9"), active(f), "still anchored while a reason is left")
    f.w.clearAnchor(crumb, "quest:2")
    f.run()
    assertEquals(emptySet(), active(f))
  }

  @Test
  fun `waking catches up respawns, resets and decay in one pass, then fires on_wake`() {
    val host = RecordingHost()
    val wakes = mutableListOf<String>()
    host.handlers[HookName.ON_WAKE] = { _, owner, ctx ->
      wakes += "${owner.name}@${ctx.elapsedPulses}"
      null
    }
    host.handlers[HookName.ON_RESET] = { _, owner, _ ->
      wakes += "reset ${owner.name}"
      null
    }
    val f =
      Fixture(
        protos =
          C.protos(
            spawns = mapOf(9 to listOf(SpawnRule(C.RAT, respawnPulses = 100))),
            resetPulses = 500,
          ),
        scripts = host,
        bubble = true,
      )
    val room = f.w.room(C.r(9))
    val rat = room.actors.single()
    f.w.damage(rat, 1000, gups.core.model.DamageType.BLUDGEON)
    assertFalse(rat.inWorld)
    val crumb = f.w.spawnItem(C.CRUMB, ItemLocation.InRoom(room.id, 0))
    crumb.decayAt = 300
    f.runTo(1000)
    assertTrue(
      room.actors.none { it.inWorld && it.protoLabel == C.RAT },
      "no respawn while dormant",
    )
    assertNotNull(f.w.item(crumb.id), "no decay while dormant")
    f.player("Ann", at = C.r(9))
    f.run()
    assertEquals(1, room.actors.count { it.protoLabel == C.RAT })
    assertNull(f.w.item(crumb.id))
    assertEquals(listOf("reset Room 9", "Room 9@1001"), wakes.filter { "Room 9" in it })
  }

  @Test
  fun `affects that ran out while dormant expire in order before on_wake`() {
    val host = RecordingHost()
    val order = mutableListOf<String>()
    host.handlers[HookName.ON_WAKE] = { _, owner, _ ->
      order += "wake ${owner.name}"
      null
    }
    val f = Fixture(protos = C.protos(), scripts = host, bubble = true)
    val rat = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    f.w.applyAffect(rat, "late", 300, onExpire = HookRef(Label("//c:t.star"), "late"))
    f.w.applyAffect(rat, "early", 100, onExpire = HookRef(Label("//c:t.star"), "early"))
    f.w.applyAffect(rat, "lasting", 5000)
    f.runTo(400)
    assertEquals(3, rat.affects.size, "affects don't tick while dormant")
    host.calls.clear()
    f.player("Ann", at = C.r(9))
    f.run()
    assertEquals(listOf("lasting"), rat.affects.map { it.name })
    assertEquals(
      listOf("ref:early@401", "ref:late@401"),
      host.calls.filter { it.startsWith("ref:") },
    )
    assertEquals("wake a rat", order.last())
  }
}

class DormantRecoveryTest {
  /** A rat at 10/100 hp, dormant from pulse 0 until a player arrives at pulse 1201. */
  private fun wakeAfter(
    recovery: DormantRecovery,
    setup: (Fixture, gups.core.model.Mob) -> Unit = { _, _ -> },
    host: RecordingHost = RecordingHost(),
  ): gups.core.model.Mob {
    val f = Fixture(protos = C.protos(recovery = recovery), scripts = host, bubble = true)
    val rat = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    rat.hp = 10
    setup(f, rat)
    f.runTo(1200)
    f.player("Ann", at = C.r(9))
    f.run()
    assertFalse(f.w.room(C.r(9)).isDormant)
    return rat
  }

  @Test
  fun `full recovery resets vitals unless a no_regen affect is still running`() {
    assertEquals(100, wakeAfter(DormantRecovery.FULL).hp)
    val held =
      wakeAfter(
        DormantRecovery.FULL,
        { f, rat -> f.w.applyAffect(rat, "weakened", 3600, flags = setOf("no_regen")) },
      )
    assertEquals(10, held.hp)
    // Expired during dormancy: the boss is back to full.
    assertEquals(
      100,
      wakeAfter(
          DormantRecovery.FULL,
          { f, rat -> f.w.applyAffect(rat, "weakened", 600, flags = setOf("no_regen")) },
        )
        .hp,
    )
  }

  @Test
  fun `none keeps vitals exactly as they were`() {
    assertEquals(10, wakeAfter(DormantRecovery.NONE).hp)
  }

  @Test
  fun `regen catches up one world tick at a time, piecewise across affects that expired`() {
    // 10 world ticks (120 … 1200) at 3 hp each (1 + con/4 + level/2).
    assertEquals(40, wakeAfter(DormantRecovery.REGEN).hp)
    // no_regen until pulse 600 suppresses exactly the ticks before it; 600 … 1200 regenerate.
    assertEquals(
      28,
      wakeAfter(
          DormantRecovery.REGEN,
          { f, rat -> f.w.applyAffect(rat, "weakened", 600, flags = setOf("no_regen")) },
        )
        .hp,
    )
    // Slowed regen (a halving modifier) until 600: 4 slow ticks of 1, then 6 of 3.
    val host = RecordingHost()
    host.modifiers["slow"] = { _, _, base ->
      (base as Regen).let { Regen(it.hp / 2, it.mana / 2, it.move / 2) }
    }
    val slowed =
      wakeAfter(
        DormantRecovery.REGEN,
        { f, rat ->
          f.w.applyAffect(rat, "slowed", 600, modifiers = mapOf(ModifierPoint.REGEN to mod("slow")))
        },
        host,
      )
    assertEquals(32, slowed.hp)
    assertTrue(slowed.affects.isEmpty())
  }
}
