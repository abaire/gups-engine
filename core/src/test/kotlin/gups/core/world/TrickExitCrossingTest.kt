package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Direction
import gups.core.model.ExitProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.model.Room
import gups.core.model.RoomProto
import gups.core.model.TrackSize
import gups.core.model.ZoneProto
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class TrickExitCrossingTest(private val fs: FixtureSchema) {
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

  private val corridor = Label("//z:corridor")
  private val vaultA = Label("//z:vault_a")
  private val vaultB = Label("//z:vault_b")
  private val rat = Label("//z:rat")

  private fun protos(
    candidates: Map<Label, Int> = mapOf(vaultA to 1, vaultB to 1),
    pick: ExitPicker? = null,
    entryIndex: Int = 0,
    sizeA: TrackSize = TrackSize.NORMAL,
    sizeB: TrackSize = TrackSize.NORMAL,
  ) =
    Prototypes(
      rooms =
        listOf(
          RoomProto(
            corridor,
            "The Corridor",
            exits =
              listOf(
                ExitProto(
                  Direction.NORTH,
                  to = null,
                  entryIndex = entryIndex,
                  oneWay = true,
                  candidates = candidates,
                  pick = pick,
                )
              ),
          ),
          RoomProto(vaultA, "Vault A", size = sizeA),
          RoomProto(vaultB, "Vault B", size = sizeB),
        ),
      mobs = listOf(MobProto(rat, "a rat", listOf("rat"), vitals = mapOf(fs.health to 100))),
      zones = listOf(ZoneProto("z")),
    )

  private fun zeroCostRules() =
    object : FixedRules(fs) {
      override fun moveCost(c: RulesContext): Map<String, Int> = emptyMap()

      override fun movePace(c: RulesContext): Int = 0
    }

  @Test
  fun `uniform roll lands in both candidates over a seeded batch`() {
    val f = Fixture(seed = 1, fs = fs, rules = zeroCostRules(), protos = protos())
    val p = f.player("Ann", at = corridor)
    f.run()

    val counts = mutableMapOf<Label, Int>()
    repeat(200) {
      f.w.placeActor(p, f.w.room(corridor), 0)
      p.moveFreeAt = 0
      f.input(p, "north")
      f.run()
      val landed = p.room.label
      assertTrue(landed == vaultA || landed == vaultB, "landed in $landed")
      counts[landed] = (counts[landed] ?: 0) + 1
    }
    assertTrue((counts[vaultA] ?: 0) > 0, "vaultA should appear: $counts")
    assertTrue((counts[vaultB] ?: 0) > 0, "vaultB should appear: $counts")
  }

  @Test
  fun `weighted roll favours the higher-weight candidate`() {
    val f =
      Fixture(
        seed = 7,
        fs = fs,
        rules = zeroCostRules(),
        protos = protos(candidates = mapOf(vaultA to 9, vaultB to 1)),
      )
    val p = f.player("Ann", at = corridor)
    f.run()

    val counts = mutableMapOf<Label, Int>()
    repeat(200) {
      f.w.placeActor(p, f.w.room(corridor), 0)
      p.moveFreeAt = 0
      f.input(p, "north")
      f.run()
      val landed = p.room.label
      assertTrue(landed == vaultA || landed == vaultB)
      counts[landed] = (counts[landed] ?: 0) + 1
    }
    val countA = counts[vaultA] ?: 0
    val countB = counts[vaultB] ?: 0
    assertTrue(countA > countB * 3, "expected vaultA ($countA) >> vaultB ($countB)")
  }

  @Test
  fun `before_exit, before_enter and on_exit all see the same picked room`() {
    val host = RecordingHost()
    var beforeExitTo: Label? = null
    var beforeEnterOwner: Label? = null
    var onExitTo: Label? = null
    host.handlers[HookName.BEFORE_EXIT] = { _, _, ctx ->
      beforeExitTo = ctx.toRoom?.label
      null
    }
    host.handlers[HookName.BEFORE_ENTER] = { _, owner, _ ->
      beforeEnterOwner = (owner as Room).label
      null
    }
    host.handlers[HookName.ON_EXIT] = { _, _, ctx ->
      onExitTo = ctx.toRoom?.label
      null
    }
    val f = Fixture(seed = 3, fs = fs, scripts = host, protos = protos())
    val p = f.player("Ann", at = corridor)
    f.run()

    f.input(p, "north")
    f.run()
    val landed = p.room.label
    assertTrue(landed == vaultA || landed == vaultB)
    assertEquals(landed, beforeExitTo)
    assertEquals(landed, beforeEnterOwner)
    assertEquals(landed, onExitTo)
  }

  @Test
  fun `pick function chooses the destination when it returns a candidate`() {
    val f = Fixture(seed = 1, fs = fs, protos = protos(pick = ExitPicker { _, _, _, _ -> vaultB }))
    val p = f.player("Ann", at = corridor)
    f.run()

    repeat(10) {
      f.w.placeActor(p, f.w.room(corridor), 0)
      p.moveFreeAt = 0
      f.input(p, "north")
      f.run()
      assertEquals(vaultB, p.room.label)
    }
  }

  private fun assertRollsAllCandidates(pick: ExitPicker) {
    val f = Fixture(seed = 1, fs = fs, rules = zeroCostRules(), protos = protos(pick = pick))
    val p = f.player("Ann", at = corridor)
    f.run()

    val seen = mutableSetOf<Label>()
    repeat(200) {
      f.w.placeActor(p, f.w.room(corridor), 0)
      p.moveFreeAt = 0
      f.input(p, "north")
      f.run()
      seen += p.room.label
    }
    assertEquals(setOf(vaultA, vaultB), seen)
  }

  @Test
  fun `pick returning null falls back to the weighted roll`() {
    assertRollsAllCandidates(ExitPicker { _, _, _, _ -> null })
  }

  @Test
  fun `pick returning a non-candidate label falls back to the weighted roll`() {
    val bogus = Label("//z:not_a_candidate")
    assertRollsAllCandidates(ExitPicker { _, _, _, _ -> bogus })
  }

  @Test
  fun `enter restriction refusal denies crossing without rerolling or charging cost`() {
    var pickCalls = 0
    val picker = ExitPicker { _, _, _, _ ->
      pickCalls++
      vaultA
    }
    val rules =
      object : FixedRules(fs) {
        override fun moveCost(c: RulesContext): Map<String, Int> = mapOf(fs.stamina to 5)

        override fun movePace(c: RulesContext): Int = 100
      }
    val f = Fixture(fs = fs, rules = rules, protos = protos(pick = picker))
    f.w.restrictions =
      mapOf(
        RestrictionPoint.ENTER to
          listOf(Restriction { _, req -> if (req.room?.label == vaultA) "Sealed shut." else null })
      )
    val p = f.player("Ann", at = corridor)
    f.run()
    f.out(p)
    val stamina = p.current(fs.stamina)

    f.input(p, "north")
    f.run()

    assertEquals("Sealed shut.", f.out(p).trim())
    assertEquals(corridor, p.room.label)
    assertEquals(stamina, p.current(fs.stamina))
    assertEquals(0L, f.w.moveDebt(p))
    assertEquals(1, pickCalls)
  }

  @Test
  fun `before_enter veto denies crossing without rerolling or charging cost`() {
    var pickCalls = 0
    val picker = ExitPicker { _, _, _, _ ->
      pickCalls++
      vaultA
    }
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_ENTER] = { _, owner, _ ->
      if ((owner as Room).label == vaultA) HookOutcome.Deny("A ward turns you back.") else null
    }
    val rules =
      object : FixedRules(fs) {
        override fun moveCost(c: RulesContext): Map<String, Int> = mapOf(fs.stamina to 5)

        override fun movePace(c: RulesContext): Int = 100
      }
    val f = Fixture(fs = fs, rules = rules, scripts = host, protos = protos(pick = picker))
    val p = f.player("Ann", at = corridor)
    f.run()
    f.out(p)
    val stamina = p.current(fs.stamina)

    f.input(p, "north")
    f.run()

    assertEquals("A ward turns you back.", f.out(p).trim())
    assertEquals(corridor, p.room.label)
    assertEquals(stamina, p.current(fs.stamina))
    assertEquals(0L, f.w.moveDebt(p))
    assertEquals(1, pickCalls)
  }

  @Test
  fun `successful crossing spends move_cost and advances moveFreeAt`() {
    val rules =
      object : FixedRules(fs) {
        override fun moveCost(c: RulesContext): Map<String, Int> = mapOf(fs.stamina to 7)

        override fun movePace(c: RulesContext): Int = 150
      }
    val f = Fixture(fs = fs, rules = rules, protos = protos())
    val p = f.player("Ann", at = corridor)
    f.run()
    val staminaBefore = p.current(fs.stamina)

    f.input(p, "north")
    f.run()

    assertTrue(p.room.label == vaultA || p.room.label == vaultB)
    assertEquals(staminaBefore - 7, p.current(fs.stamina))
    assertTrue(p.moveFreeAt > 0L)
  }

  @Test
  fun `walk pinned in its own tier is cancelled without spending cost or debt`() {
    val rules =
      object : FixedRules(fs) {
        override fun moveCost(c: RulesContext): Map<String, Int> = mapOf(fs.stamina to 5)

        override fun movePace(c: RulesContext): Int = 100
      }
    val f = Fixture(fs = fs, rules = rules, protos = protos())
    val walker = f.player("Ann", at = corridor)
    val killer = f.player("Bob", at = corridor)
    f.run()
    f.out(walker)
    val stamina = walker.current(fs.stamina)

    f.input(walker, "north")
    f.input(killer, "kill ${walker.name}")
    f.run()

    assertEquals(corridor, walker.room.label)
    assertEquals(stamina, walker.current(fs.stamina))
    assertEquals(0L, f.w.moveDebt(walker))
    assertContains(f.out(walker), "pinned")
  }

  @Test
  fun `successful flee lands in a candidate and fires on_disengage with flee`() {
    val host = RecordingHost()
    var disengageReason: String? = null
    host.handlers[HookName.ON_DISENGAGE] = { _, _, ctx ->
      disengageReason = ctx.event
      null
    }
    val f = Fixture(fs = fs, rules = FixedRules(fs, flee = 100), scripts = host, protos = protos())
    val p = f.player("Ann", at = corridor)
    val mob = f.w.spawnMob(rat, f.w.room(corridor))
    f.run()

    f.input(p, "kill rat")
    f.run()
    assertTrue(mob.id in p.fighting)

    f.input(p, "flee")
    f.run()

    assertTrue(p.room.label == vaultA || p.room.label == vaultB)
    assertEquals("flee", disengageReason)
  }

  @Test
  fun `flee vetoed in the picked room counts as a failed flee with flee_fail_lag`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_ENTER] = { _, owner, _ ->
      if ((owner as Room).label == vaultA) HookOutcome.Deny("No escape!") else null
    }
    val rules =
      object : FixedRules(fs, flee = 100) {
        override fun fleeFailLag(c: RulesContext): Int = 4
      }
    val f =
      Fixture(
        fs = fs,
        rules = rules,
        scripts = host,
        protos = protos(pick = ExitPicker { _, _, _, _ -> vaultA }),
      )
    val p = f.player("Ann", at = corridor)
    f.w.spawnMob(rat, f.w.room(corridor))
    f.run()
    f.out(p)

    f.input(p, "kill rat")
    f.run()
    f.out(p)

    f.input(p, "flee")
    f.run(1)

    assertEquals(corridor, p.room.label)
    assertContains(f.out(p), "No escape!")
    assertTrue(p.waitState >= 3, "expected flee_fail_lag applied, got waitState=${p.waitState}")
  }

  @Test
  fun `entry_index -1 lands at the back of each candidate track`() {
    for (target in listOf(vaultA, vaultB)) {
      val f =
        Fixture(
          fs = fs,
          protos =
            protos(
              pick = ExitPicker { _, _, _, _ -> target },
              entryIndex = -1,
              sizeA = TrackSize.CRAMPED,
              sizeB = TrackSize.LARGE,
            ),
        )
      val p = f.player("Ann", at = corridor)
      f.run()

      f.input(p, "north")
      f.run()

      val dest = f.w.room(target)
      assertEquals(dest.trackSize - 1, p.trackIndex)
    }
  }
}
