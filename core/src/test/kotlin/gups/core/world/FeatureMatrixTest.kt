package gups.core.world

import gups.core.fixtures.FixtureSchemas
import gups.core.model.ItemLocation
import gups.core.model.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Every valid combination of features runs the same scripted scenario under the invariant check.
 */
class FeatureMatrixTest {
  companion object {
    /** The 12 valid sets: `track` needs `combat`. */
    @JvmStatic
    fun validSets(): List<Features> =
      (0 until 16)
        .map { bits ->
          Features(Feature.entries.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet())
        }
        .filter { it.problems().isEmpty() }
  }

  @Test
  fun `twelve of the sixteen feature sets are valid`() {
    assertEquals(12, validSets().size)
  }

  private fun host() =
    RecordingHost().also { h ->
      h.handlers[HookName.ON_USE] = { w, _, ctx ->
        HookOutcome.Allow.also { w.send(ctx.actor!!, "It hums.") }
      }
    }

  private fun scenario(features: Features, seed: Long): String {
    val f = Fixture(seed = seed, scripts = host(), features = features)
    val log = StringBuilder()
    val ann = f.player("Ann")
    fun say(line: String) {
      f.input(ann, line)
      f.run(6)
      log.append("> ").append(line).append('\n').append(f.out(ann))
    }

    // Two rooms, an item picked up, worn and used.
    f.w.spawnItem(T.SWORD, ItemLocation.InRoom(ann.room.id, 0))
    say("north")
    say("south")
    say("get sword")
    say("wield sword")
    say("use sword")
    say("score")
    say("help")

    if (Feature.COMBAT in features) {
      val goblin = f.mob(T.GOBLIN, index = 0)
      if (Feature.TRACK in features) {
        f.w.placeActor(goblin, goblin.room, 2)
        say("advance goblin")
        say("retreat")
        say("advance goblin")
        say("advance goblin")
      }
      say("kill goblin")
      f.run(60)
      log.append(f.out(ann))
      assertFalse(goblin.inWorld, "the goblin dies: $features")
      if (Feature.LEVELING in features) assertTrue(ann.xp > 0, "a kill earns XP: $features")
      else assertEquals(0L, ann.xp)
    }

    if (Feature.SKILLS in features) {
      ann.skills[T.ZAP] = 100
      ann.skills[T.BOLT] = 100
      val dummy = f.mob(T.DUMMY, index = 0)
      val before = dummy.current(f.fs.health)
      say("cast bolt dummy")
      f.run(10)
      log.append(f.out(ann))
      assertTrue(dummy.current(f.fs.health) < before, "the cast lands: $features")
    }

    if (Feature.LEVELING in features) {
      f.w.awardXp(ann, 5_000)
      assertTrue(ann.level > 1, "a level-up: $features")
      log.append(f.out(ann))
    }

    // Death through `condition`, by a raw damage verb.
    val victim = f.player("Bea")
    f.w.damage(victim, mapOf(f.fs.health to 1000), gups.core.model.DamageType(f.fs.unarmed))
    f.run(2)
    log.append(f.out(victim))
    assertTrue(victim.inWorld)
    assertTrue(victim.current(f.fs.health) > 0, "respawned: $features")
    f.run(30)
    assertTrue(f.w.checkInvariants().isEmpty(), "${f.w.checkInvariants()}")
    return log.toString()
  }

  @ParameterizedTest
  @MethodSource("validSets")
  fun `the scenario runs and is deterministic for a seed`(features: Features) {
    FixtureSchemas.current = FixtureSchemas.FANTASY
    assertEquals(scenario(features, 3), scenario(features, 3), "deterministic: $features")
  }

  @ParameterizedTest
  @MethodSource("validSets")
  fun `no player-visible line mentions a feature that is off`(features: Features) {
    val out = scenario(features, 5)
    if (Feature.LEVELING !in features) assertFalse("experience" in out)
    if (Feature.SKILLS !in features) assertFalse("casting" in out)
    if (Feature.TRACK !in features) assertFalse("Beside you" in out)
  }

  @Test
  fun `a player spawned in a world without the track sits at index 0`() {
    val f = Fixture(features = Features(setOf(Feature.COMBAT)))
    val p: Player = f.player("Ann", index = 2)
    assertEquals(0, p.trackIndex)
    assertEquals(1, p.room.trackSize)
  }
}
