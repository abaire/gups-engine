package gups.core.world

import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The move pace: its default, its export override, its modifier clamp and the room its chain reads.
 */
class MovePaceTest {
  @Test
  fun `without an export the pace is 100`() {
    val f = Fixture()
    val p = f.player("Ann")
    assertEquals(100, f.w.movePaceOf(RulesContext(f.w, p)))
  }

  @Test
  fun `an export returning 300 gives 300`() {
    val f =
      Fixture(
        rules =
          object : FixedRules() {
            override fun movePace(c: RulesContext) = 300
          }
      )
    val p = f.player("Ann")
    assertEquals(300, f.w.movePaceOf(RulesContext(f.w, p)))
  }

  @Test
  fun `a room modifier below the floor clamps to 25 and traces`() {
    val host = RecordingHost()
    host.modifiers["slow"] = { _, _, base -> (base as Int) - 1000 }
    val f =
      Fixture(
        rules = FixedRules(),
        scripts = host,
        protos =
          Prototypes(
            listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.MOVE_PACE to mod("slow")))) +
              T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    val p = f.player("Ann")
    val traces = mutableListOf<String>()
    f.w.onRules = { _, line -> traces += line }
    assertEquals(25, f.w.movePaceOf(RulesContext(f.w, p)))
    assertTrue(traces.any { "clamp 25" in it }, traces.toString())
  }

  @Test
  fun `the chain runs with the room leaving the mover, not the one arriving`() {
    val host = RecordingHost()
    host.modifiers["hall_only"] = { _, _, base -> (base as Int) + 50 }
    val f =
      Fixture(
        rules = FixedRules(),
        scripts = host,
        protos =
          Prototypes(
            // The hall (room 0, where Ann stands) has a move_pace modifier; the yard (room 1) does
            // not.
            listOf(
              T.rooms[0].copy(modifiers = mapOf(ModifierPoint.MOVE_PACE to mod("hall_only")))
            ) + T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    val p = f.player("Ann")
    // Ann is in the hall; the room she leaves is the hall, whose modifier adds 50.
    val c = RulesContext(f.w, p, room = p.room, exit = f.w.room(p.room.label).exits.values.first())
    assertEquals(150, f.w.movePaceOf(c))
  }
}
