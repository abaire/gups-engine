package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.FactionProto
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.STANDING_HISTORY_MAX
import gups.core.model.STANDING_MAX
import gups.core.model.STANDING_MIN
import gups.core.model.StandingHolder
import gups.core.model.StandingSubject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandingTest {
  private val factionA = Label("//t:faction_a")
  private val factionB = Label("//t:faction_b")
  private val fs = FixtureSchemas.current

  private fun createFixture(
    standingFeature: Boolean = true,
    factions: List<FactionProto> = emptyList(),
    extraMobs: List<MobProto> = emptyList(),
  ): Fixture {
    val features =
      if (standingFeature) Features.ALL else Features(Features.ALL.enabled - Feature.STANDING)
    val defaultFactions = listOf(FactionProto(factionA), FactionProto(factionB))
    val combinedFactions = (factions + defaultFactions).distinctBy { it.label }
    val protos =
      Prototypes(
        rooms = T.rooms,
        mobs = T.mobs(fs) + extraMobs,
        items = T.items,
        skills = T.skills(fs),
        factions = combinedFactions,
      )
    return Fixture(fs = fs, protos = protos, features = features)
  }

  @Test
  fun `implicit faction default and explicit factions`() {
    val mobWithFactionsProto =
      MobProto(
        Label("//t:custom_mob"),
        "custom mob",
        listOf("custom"),
        factions = listOf(factionA, factionB),
      )
    val f = createFixture(extraMobs = listOf(mobWithFactionsProto))
    val player = f.player("Alice")
    val defaultMob = f.mob(T.GOBLIN)
    val customMob = f.mob(mobWithFactionsProto.label)

    assertEquals(emptyList<Label>(), f.w.factionsOf(player))
    assertEquals(listOf(T.GOBLIN), f.w.factionsOf(defaultMob))
    assertEquals(listOf(factionA, factionB), f.w.factionsOf(customMob))
  }

  @Test
  fun `disposition parts in isolation and combined`() {
    val factionProtoA = FactionProto(label = factionA, standing = mapOf(factionB to -30))
    val mobAProto =
      MobProto(Label("//t:mob_a"), "mob A", listOf("mob_a"), factions = listOf(factionA))
    val mobBProto =
      MobProto(Label("//t:mob_b"), "mob B", listOf("mob_b"), factions = listOf(factionB))
    val f =
      createFixture(factions = listOf(factionProtoA), extraMobs = listOf(mobAProto, mobBProto))
    val mobA = f.mob(mobAProto.label)
    val mobB = f.mob(mobBProto.label)
    val player = f.player("Hero")

    // Initially declared: factionA -> factionB is -30
    val initialParts = f.w.dispositionParts(mobA, mobB)
    assertEquals(-30, initialParts.declared)
    assertEquals(0, initialParts.factionToActor)
    assertEquals(0, initialParts.factionToFaction)
    assertEquals(0, initialParts.personal)
    assertEquals(-30, initialParts.total)
    assertEquals(-30, f.w.disposition(mobA, mobB))

    // Faction to actor: factionA -> player is +20
    f.w.applyStanding(
      StandingHolder.Faction(factionA),
      StandingSubject.Of(player.id),
      20,
      "help",
      via = mobA,
    )
    val partsToPlayer = f.w.dispositionParts(mobA, player)
    assertEquals(0, partsToPlayer.declared)
    assertEquals(20, partsToPlayer.factionToActor)
    assertEquals(0, partsToPlayer.factionToFaction)
    assertEquals(0, partsToPlayer.personal)
    assertEquals(20, partsToPlayer.total)

    // Faction to faction: factionA -> factionB is +10
    f.w.applyStanding(
      StandingHolder.Faction(factionA),
      StandingSubject.Faction(factionB),
      10,
      "treaty",
      via = null,
    )
    val partsAfterFactionRecord = f.w.dispositionParts(mobA, mobB)
    assertEquals(-30, partsAfterFactionRecord.declared)
    assertEquals(0, partsAfterFactionRecord.factionToActor)
    assertEquals(10, partsAfterFactionRecord.factionToFaction)
    assertEquals(0, partsAfterFactionRecord.personal)
    assertEquals(-20, partsAfterFactionRecord.total)

    // Personal: mobA -> mobB is -40
    f.w.applyStanding(
      StandingHolder.Personal(mobA),
      StandingSubject.Of(mobB.id),
      -40,
      "attack",
      via = mobB,
    )
    val partsAfterPersonal = f.w.dispositionParts(mobA, mobB)
    assertEquals(-30, partsAfterPersonal.declared)
    assertEquals(0, partsAfterPersonal.factionToActor)
    assertEquals(10, partsAfterPersonal.factionToFaction)
    assertEquals(-40, partsAfterPersonal.personal)
    assertEquals(-60, partsAfterPersonal.total)
    assertEquals(-60, f.w.disposition(mobA, mobB))
  }

  @Test
  fun `standing clamp and self standing is max`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Hero")

    assertEquals(STANDING_MAX, f.w.disposition(mob, mob))
    assertEquals(STANDING_MAX, f.w.disposition(player, player))

    // Personal clamping
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      200,
      "favor",
      via = null,
    )
    assertEquals(STANDING_MAX, f.w.disposition(mob, player))

    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      -300,
      "harm",
      via = null,
    )
    assertEquals(STANDING_MIN, f.w.disposition(mob, player))
  }

  @Test
  fun `personal standing rejects faction subject`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    assertFailsWith<IllegalArgumentException> {
      f.w.applyStanding(
        StandingHolder.Personal(mob),
        StandingSubject.Faction(factionA),
        10,
        "test",
        via = null,
      )
    }
  }

  @Test
  fun `applyStanding history pruning by age and cap`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Hero")
    f.w.standingHistoryPulses = 100

    f.runTo(50)
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      10,
      "gift",
      via = null,
    )

    f.runTo(200) // 200 - 100 = 100 cutoff; entry at pulse 50 should be pruned
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      5,
      "gift",
      via = null,
    )

    val history = mob.personalStanding.getValue(player.id).history
    assertEquals(1, history.size)
    assertEquals(200, history.first().pulse)

    // Cap at 1024
    f.w.standingHistoryPulses = 50_000
    for (i in 1..1050) {
      f.run(1)
      f.w.applyStanding(
        StandingHolder.Personal(mob),
        StandingSubject.Of(player.id),
        1,
        "tick",
        via = null,
      )
    }
    assertEquals(STANDING_HISTORY_MAX, mob.personalStanding.getValue(player.id).history.size)
  }

  @Test
  fun `standingHistory filters and gained lost separation`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Hero")
    val otherMob = f.mob(T.DUMMY)

    f.runTo(10)
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      15,
      "heal",
      via = otherMob,
    )
    f.runTo(20)
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      -25,
      "attack",
      via = otherMob,
    )
    f.runTo(30)
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      5,
      "heal",
      via = null,
    )

    val allTotals =
      f.w.standingHistory(
        StandingHolder.Personal(mob),
        StandingSubject.Of(player.id),
        since = 0,
        via = null,
        event = null,
      )
    assertEquals(20L, allTotals.gained)
    assertEquals(25L, allTotals.lost)

    val sinceTotals =
      f.w.standingHistory(
        StandingHolder.Personal(mob),
        StandingSubject.Of(player.id),
        since = 15,
        via = null,
        event = null,
      )
    assertEquals(5L, sinceTotals.gained)
    assertEquals(25L, sinceTotals.lost)

    val viaTotals =
      f.w.standingHistory(
        StandingHolder.Personal(mob),
        StandingSubject.Of(player.id),
        since = 0,
        via = otherMob.id,
        event = null,
      )
    assertEquals(15L, viaTotals.gained)
    assertEquals(25L, viaTotals.lost)

    val eventTotals =
      f.w.standingHistory(
        StandingHolder.Personal(mob),
        StandingSubject.Of(player.id),
        since = 0,
        via = null,
        event = "attack",
      )
    assertEquals(0L, eventTotals.gained)
    assertEquals(25L, eventTotals.lost)
  }

  @Test
  fun `memo cleared by apply, clear, forgetFactions, and pulse`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Hero")

    assertEquals(0, f.w.disposition(mob, player))
    assertTrue(f.w.dispositionMemo.containsKey(mob.id to player.id))

    // applyStanding clears memo
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      10,
      "boost",
      via = null,
    )
    assertTrue(f.w.dispositionMemo.isEmpty())

    assertEquals(10, f.w.disposition(mob, player))
    assertTrue(f.w.dispositionMemo.containsKey(mob.id to player.id))

    // clearStanding clears memo
    f.w.clearStanding(StandingHolder.Personal(mob), StandingSubject.Of(player.id))
    assertTrue(f.w.dispositionMemo.isEmpty())

    assertEquals(0, f.w.disposition(mob, player))
    assertTrue(f.w.dispositionMemo.containsKey(mob.id to player.id))

    // forgetFactions clears memo
    f.w.forgetFactions(mob)
    assertTrue(f.w.dispositionMemo.isEmpty())

    assertEquals(0, f.w.disposition(mob, player))
    assertTrue(f.w.dispositionMemo.containsKey(mob.id to player.id))

    // runPulse clears memo
    f.w.runPulse()
    assertTrue(f.w.dispositionMemo.isEmpty())
  }

  @Test
  fun `pruneStanding drops expired entries and empty records`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Hero")
    f.w.standingHistoryPulses = 50

    f.runTo(10)
    // Record with 0 amount (net) and old history
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      10,
      "heal",
      via = null,
    )
    f.w.applyStanding(
      StandingHolder.Personal(mob),
      StandingSubject.Of(player.id),
      -10,
      "hit",
      via = null,
    )
    assertEquals(0, mob.personalStanding.getValue(player.id).amount)

    // Faction record with net 0 and old history
    f.w.applyStanding(
      StandingHolder.Faction(factionA),
      StandingSubject.Faction(factionB),
      5,
      "t",
      via = null,
    )
    f.w.applyStanding(
      StandingHolder.Faction(factionA),
      StandingSubject.Faction(factionB),
      -5,
      "t",
      via = null,
    )

    f.runTo(100)
    f.w.pruneStanding()

    assertTrue(mob.personalStanding.isEmpty())
    assertTrue(f.w.factionStanding.isEmpty())
  }

  @Test
  fun `raiseStandingEvent built-in events and pet proxy`() {
    val factionProtoA = FactionProto(label = factionA)
    val factionProtoB = FactionProto(label = factionB)
    val mobAProto =
      MobProto(Label("//t:mob_a"), "mob A", listOf("mob_a"), factions = listOf(factionA))
    val mobBProto =
      MobProto(Label("//t:mob_b"), "mob B", listOf("mob_b"), factions = listOf(factionB))
    val f =
      createFixture(
        factions = listOf(factionProtoA, factionProtoB),
        extraMobs = listOf(mobAProto, mobBProto),
      )

    val mobA = f.mob(mobAProto.label)
    val mobB = f.mob(mobBProto.label)
    val player = f.player("Hero")

    // 1. Attack: mobA attacks player -> player (victim) is Player, no personal delta; but if player
    // attacks mobA:
    f.w.raiseStandingEvent(StandingEvent("attack", affected = mobA, actor = player))
    assertEquals(-50, mobA.personalStanding.getValue(player.id).amount)

    // 2. Heal / Help: player heals mobA -> +5, capped at 25
    f.w.raiseStandingEvent(StandingEvent("heal", affected = mobA, actor = player, amount = 10))
    assertEquals(-45, mobA.personalStanding.getValue(player.id).amount)

    // Repeated heals up to cap 25
    for (i in 1..20) {
      f.w.raiseStandingEvent(StandingEvent("heal", affected = mobA, actor = player, amount = 10))
    }
    assertEquals(25, mobA.personalStanding.getValue(player.id).amount)

    // 3. Assist: player assists enemy of mobA -> mobA personal record of player -10
    f.w.raiseStandingEvent(StandingEvent("assist", affected = mobA, actor = player, helped = mobB))
    assertEquals(15, mobA.personalStanding.getValue(player.id).amount)

    // 4. Kill: mobA killed by mobB -> factionA toward mobB is -50, and factionA toward factionB is
    // -5
    f.w.raiseStandingEvent(StandingEvent("kill", affected = mobA, actor = mobB))
    assertEquals(-50, f.w.factionStanding[factionA to StandingSubject.Of(mobB.id)]?.amount)
    assertEquals(-5, f.w.factionStanding[factionA to StandingSubject.Faction(factionB)]?.amount)

    // 5. Pet proxy: when pet mobB (owned by player) attacks mobA -> event runs for mobB, then again
    // for player
    mobB.owner = player.id
    f.w.raiseStandingEvent(StandingEvent("attack", affected = mobA, actor = mobB))
    // mobA personal toward mobB should be -50, and toward owner (player) should have gained -50
    assertEquals(-50, mobA.personalStanding.getValue(mobB.id).amount)
    assertEquals(-35, mobA.personalStanding.getValue(player.id).amount) // was 15, 15 - 50 = -35

    // 6. Self event is ignored
    f.w.raiseStandingEvent(StandingEvent("attack", affected = mobA, actor = mobA))
  }

  @Test
  fun `feature standing off returns empty factions and 0 standing`() {
    val f = createFixture(standingFeature = false)
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Hero")

    assertEquals(emptyList<Label>(), f.w.factionsOf(mob))
    assertEquals(emptyList<Label>(), f.w.factionsOf(player))
    assertEquals(0, f.w.disposition(mob, player))
    assertEquals(0, f.w.disposition(mob, mob))
  }

  @Test
  fun `a change of owner forgets the pet's factions`() {
    val f = createFixture()
    val pet = f.mob(T.GOBLIN)
    val ann = f.player("Ann")
    f.w.factionAdjusters =
      listOf(
        FactionAdjuster { _, a, current ->
          if ((a as Mob).owner != null) listOf(factionA) else current
        }
      )

    assertEquals(listOf(T.GOBLIN), f.w.factionsOf(pet))
    f.w.setOwner(pet, ann)
    assertEquals(listOf(factionA), f.w.factionsOf(pet))
    f.w.clearOwner(pet)
    assertEquals(listOf(T.GOBLIN), f.w.factionsOf(pet))
  }

  @Test
  fun `an actor leaving the world drops its cached factions`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    f.w.factionsOf(mob)

    f.w.removeActor(mob)

    assertTrue(mob.id !in f.w.factionCache)
  }

  @Test
  fun `standing reads a record's own amount, without declared opinions`() {
    val f =
      createFixture(factions = listOf(FactionProto(factionA, standing = mapOf(factionB to -40))))
    val mob = f.mob(T.GOBLIN)
    val p = f.player("Ann")
    val a = StandingHolder.Faction(factionA)

    assertEquals(0, f.w.standing(a, StandingSubject.Faction(factionB)))
    f.w.applyStanding(a, StandingSubject.Faction(factionB), 15, "treaty")
    assertEquals(15, f.w.standing(a, StandingSubject.Faction(factionB)))
    f.w.applyStanding(a, StandingSubject.Of(p.id), -20, "theft")
    assertEquals(-20, f.w.standing(a, StandingSubject.Of(p.id)))
    f.w.applyStanding(StandingHolder.Personal(mob), StandingSubject.Of(p.id), 7, "heal")
    assertEquals(7, f.w.standing(StandingHolder.Personal(mob), StandingSubject.Of(p.id)))
    assertFailsWith<IllegalArgumentException> {
      f.w.standing(StandingHolder.Personal(mob), StandingSubject.Faction(factionA))
    }
  }
}
