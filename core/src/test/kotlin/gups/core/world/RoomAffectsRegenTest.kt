package gups.core.world

import gups.core.fixtures.FixtureSchemas
import gups.core.model.DormantRecovery
import gups.core.model.ModifierPoint
import gups.core.model.Room
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A rat at 10 hp in `//c:r9` is dormant from pulse 0; a player arrives at pulse 1200 and it wakes
 * at 1201. Regen is pinned to 4 hp per world tick (10 ticks: 120 … 1200).
 */
class RoomAffectsRegenTest {
  private val fs = FixtureSchemas.FANTASY

  private fun hostHalving(host: RecordingHost) {
    host.modifiers["halve"] = { _, _, base ->
      @Suppress("UNCHECKED_CAST") (base as Map<String, Int>).mapValues { it.value / 2 }
    }
  }

  private fun world(
    recovery: DormantRecovery = DormantRecovery.REGEN,
    host: RecordingHost = RecordingHost(),
    setup: (Fixture, Room) -> Unit = { _, _ -> },
  ): Fixture {
    val f =
      Fixture(
        protos = C.protos(recovery = recovery),
        scripts = host,
        rules = FixedRules(fs).also { it.regenOf = { mapOf(fs.health to 4) } },
        bubble = true,
      )
    val room = f.w.room(C.r(9))
    f.w.spawnMob(C.RAT, room)
    val rat = room.actors.single()
    f.setVital(rat, fs.health, 10)
    setup(f, room)
    f.runTo(1200)
    f.player("Ann", at = C.r(9))
    f.run()
    return f
  }

  private fun hp(f: Fixture): Int =
    f.w.room(C.r(9)).actors.single { it.protoLabel == C.RAT }.current(fs.health)

  @Test
  fun `an affect that expires halfway through dormancy halves only the first half`() {
    val host = RecordingHost()
    hostHalving(host)
    val f =
      world(
        host = host,
        setup = { w, room ->
          w.w.applyRoomAffect(
            room,
            "ward",
            600,
            modifiers = mapOf(ModifierPoint.REGEN to mod("halve")),
          )
        },
      )
    // 4 ticks at 2 (halved), then 6 at 4: 8 + 24, not the old 40.
    assertEquals(42, hp(f))
  }

  @Test
  fun `an affect applied halfway through dormancy halves only the second half`() {
    val host = RecordingHost()
    hostHalving(host)
    val f =
      world(
        host = host,
        setup = { w, room ->
          w.runTo(600)
          w.w.applyRoomAffect(
            room,
            "ward",
            2000,
            modifiers = mapOf(ModifierPoint.REGEN to mod("halve")),
          )
        },
      )
    assertEquals(1, f.w.room(C.r(9)).affects.size, "still live at wake")
    // 4 ticks at 4, then 6 at 2: 16 + 12.
    assertEquals(38, hp(f))
  }

  @Test
  fun `an affect covering the whole dormancy halves all of it`() {
    val host = RecordingHost()
    hostHalving(host)
    val f =
      world(
        host = host,
        setup = { w, room ->
          w.w.applyRoomAffect(
            room,
            "ward",
            2000,
            modifiers = mapOf(ModifierPoint.REGEN to mod("halve")),
          )
        },
      )
    assertEquals(30, hp(f))
  }

  @Test
  fun `an affect that expired before the room went dormant halves nothing`() {
    val host = RecordingHost()
    hostHalving(host)
    val f =
      Fixture(
        protos = C.protos(recovery = DormantRecovery.REGEN),
        scripts = host,
        rules = FixedRules(fs).also { it.regenOf = { mapOf(fs.health to 4) } },
        bubble = true,
      )
    val room = f.w.room(C.r(9))
    val rat = f.w.spawnMob(C.RAT, room)
    f.setVital(rat, fs.health, 10)
    val ann = f.player("Ann", at = C.r(9))
    f.runTo(10)
    f.w.applyRoomAffect(room, "ward", 5, modifiers = mapOf(ModifierPoint.REGEN to mod("halve")))
    f.runTo(16)
    assertTrue(room.affects.isEmpty(), "it expired live at pulse 15")
    f.w.teleport(ann, f.w.room(C.r(0)))
    f.runTo(1200)
    assertTrue(room.isDormant)
    f.w.teleport(ann, f.w.room(C.r(9)))
    f.run()
    assertEquals(50, rat.current(fs.health))
  }

  @Test
  fun `two room affects that overlap give three segments`() {
    val host = RecordingHost()
    hostHalving(host)
    val f =
      world(
        host = host,
        setup = { w, room ->
          w.w.applyRoomAffect(
            room,
            "a",
            800,
            modifiers = mapOf(ModifierPoint.REGEN to mod("halve")),
          )
          w.runTo(400)
          w.w.applyRoomAffect(
            room,
            "b",
            800,
            modifiers = mapOf(ModifierPoint.REGEN to mod("halve")),
          )
        },
      )
    // [1,400) one halving: 3 ticks × 2. [400,800) both: 3 ticks × 1. [800,1200) one: 3 ticks × 2.
    // [1200,1201) neither: 1 tick × 4. Total 19.
    assertEquals(29, hp(f))
  }

  @Test
  fun `a mob affect expiring in the same span still splits correctly`() {
    val host = RecordingHost()
    hostHalving(host)
    val f =
      world(
        host = host,
        setup = { w, room ->
          val rat = room.actors.single()
          w.w.applyAffect(rat, AffectSpec("weakened", 600, flags = setOf("no_regen")))
          w.w.applyRoomAffect(
            room,
            "ward",
            2000,
            modifiers = mapOf(ModifierPoint.REGEN to mod("halve")),
          )
        },
      )
    // no_regen suppresses the first 4 ticks; the room halves the last 6: 12.
    assertEquals(22, hp(f))
  }

  @Test
  fun `a full-mode mob with a running no_regen affect gets the room-aware catch-up`() {
    val host = RecordingHost()
    hostHalving(host)
    val f =
      world(
        recovery = DormantRecovery.FULL,
        host = host,
        setup = { w, room ->
          val rat = room.actors.single()
          w.w.applyAffect(rat, AffectSpec("weakened", 3600, flags = setOf("no_regen")))
          w.w.applyRoomAffect(
            room,
            "ward",
            2000,
            modifiers = mapOf(ModifierPoint.REGEN to mod("halve")),
          )
        },
      )
    // The mob's own no_regen suppresses every segment of the fallback catch-up.
    assertEquals(10, hp(f))
  }

  @Test
  fun `a room affect with the no_regen flag does not suppress regen`() {
    val f =
      world(
        setup = { w, room -> w.w.applyRoomAffect(room, "ward", 2000, flags = setOf("no_regen")) }
      )
    assertEquals(50, hp(f))
  }

  @Test
  fun `catchUpAt is null after the wake, also when a rules export throws`() {
    val f =
      Fixture(
        protos = C.protos(recovery = DormantRecovery.REGEN),
        scripts = RecordingHost(),
        rules = FixedRules(fs).also { it.regenOf = { error("boom") } },
        bubble = true,
      )
    val room = f.w.room(C.r(9))
    f.w.spawnMob(C.RAT, room)
    f.w.applyRoomAffect(room, "ward", 600)
    f.runTo(1200)
    f.player("Ann", at = C.r(9))
    f.run()
    assertNull(f.w.catchUpAt)
  }
}
