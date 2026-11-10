package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.FactionProto
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.StandingDelta
import gups.core.model.StandingHolder
import gups.core.model.StandingSubject
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals

class StandingEventsTest {
  private val fs = FixtureSchemas.current
  private val factionGoblin = Label("//t:goblins")
  private val factionElf = Label("//t:elves")

  private class RecordingRules(fs: gups.core.fixtures.FixtureSchema) : FixedRules(fs) {
    val events = mutableListOf<RulesContext>()

    override fun standingChange(c: RulesContext): List<StandingDelta>? {
      events += c
      return null
    }
  }

  private fun createFixture(
    rules: FixedRules = FixedRules(fs),
    factions: List<FactionProto> = emptyList(),
    extraMobs: List<MobProto> = emptyList(),
    extraItems: List<ItemProto> = emptyList(),
  ): Fixture {
    val protos =
      Prototypes(
        rooms = T.rooms,
        mobs = T.mobs(fs) + extraMobs,
        items = T.items + extraItems,
        skills = T.skills(fs),
        factions = factions,
      )
    return Fixture(fs = fs, rules = rules, protos = protos, features = Features.ALL)
  }

  @Test
  fun `attack event on fresh engagement applies personal minus 50 once`() {
    val f = createFixture()
    val p = f.player("Alice")
    val mob = f.mob(T.GOBLIN)

    f.w.startCombat(p, mob)
    assertEquals(-50, f.w.disposition(mob, p))

    // Starting combat again while already engaged adds nothing
    f.w.startCombat(p, mob)
    assertEquals(-50, f.w.disposition(mob, p))
  }

  @Test
  fun `mob aggressive attack on player raises attack with no personal record on player`() {
    val f = createFixture()
    val p = f.player("Alice")
    val mob = f.mob(T.GOBLIN)

    f.w.startCombat(mob, p)
    // Mob has no record of player from being the initiator
    assertEquals(0, f.w.disposition(mob, p))
    // Player has no personal standing
    assertEquals(0, f.w.disposition(p, mob))
  }

  @Test
  fun `pet attacks mob applies minus 50 for pet and owner`() {
    val f = createFixture()
    val p = f.player("Alice")
    val pet = f.mob(T.DUMMY)
    f.w.setOwner(pet, p)
    val foe = f.mob(T.GOBLIN)

    f.w.startCombat(pet, foe)
    assertEquals(-50, f.w.disposition(foe, pet))
    assertEquals(-50, f.w.disposition(foe, p))
  }

  @Test
  fun `kill event applies faction deltas to killer and killer factions`() {
    val factions = listOf(FactionProto(factionGoblin), FactionProto(factionElf))
    val goblinProto =
      MobProto(
        Label("//t:custom_goblin"),
        "goblin",
        listOf("goblin"),
        factions = listOf(factionGoblin),
      )
    val elfProto =
      MobProto(Label("//t:custom_elf"), "elf", listOf("elf"), factions = listOf(factionElf))
    val f = createFixture(factions = factions, extraMobs = listOf(goblinProto, elfProto))

    val goblin = f.mob(goblinProto.label)
    val elf = f.mob(elfProto.label)

    f.hurt(goblin, 1000, source = elf)

    // Victim's faction Goblin -> killer Elf: -50
    assertEquals(
      -50,
      f.w.standing(StandingHolder.Faction(factionGoblin), StandingSubject.Of(elf.id)),
    )
    // Victim's faction Goblin -> killer faction Elf: -5
    assertEquals(
      -5,
      f.w.standing(StandingHolder.Faction(factionGoblin), StandingSubject.Faction(factionElf)),
    )
  }

  @Test
  fun `heal event increases personal standing up to 25 and assists opponents`() {
    val f = createFixture()
    val p = f.player("Alice")
    val mob = f.mob(T.GOBLIN)
    val enemy = f.mob(T.DUMMY)

    // Mob is hurt
    f.setVital(mob, fs.health, 10)
    f.w.heal(mob, fs.health, 10, source = p)
    assertEquals(5, f.w.disposition(mob, p))

    // Repeated heals cap at 25
    for (i in 1..10) {
      f.setVital(mob, fs.health, 10)
      f.w.heal(mob, fs.health, 10, source = p)
    }
    assertEquals(25, f.w.disposition(mob, p))

    // Healing player while fighting enemy raises assist on enemy (-10)
    f.w.startCombat(p, enemy)
    val healer = f.player("Bob")
    f.setVital(p, fs.health, 20)
    f.w.heal(p, fs.health, 10, source = healer)
    assertEquals(-10, f.w.disposition(enemy, healer))

    // Healing full health actor raises nothing
    val fullMob = f.mob(T.GOBLIN)
    val before = f.w.disposition(fullMob, healer)
    f.w.heal(fullMob, fs.health, 10, source = healer)
    assertEquals(before, f.w.disposition(fullMob, healer))
  }

  @Test
  fun `affect application help on engaged mob and assist on opponents`() {
    val f = createFixture()
    val p = f.player("Alice")
    val mob = f.mob(T.GOBLIN)
    val enemy = f.mob(T.DUMMY)

    // Helpful affect outside combat does not raise help
    f.w.applyAffect(mob, AffectSpec("bless", duration = 100, source = p), hostile = false)
    assertEquals(0, f.w.disposition(mob, p))

    // Helpful affect while engaged raises help (+5)
    f.w.startCombat(mob, enemy)
    f.w.applyAffect(mob, AffectSpec("shield", duration = 100, source = p), hostile = false)
    assertEquals(5, f.w.disposition(mob, p))
    // And assist on enemy (-10)
    assertEquals(-10, f.w.disposition(enemy, p))

    // Hostile affect (bane) raises no help or assist
    val enemy2 = f.mob(T.GOBLIN)
    f.w.startCombat(p, enemy2)
    assertEquals(-50, f.w.disposition(enemy2, p)) // from attack event
    f.w.applyAffect(enemy2, AffectSpec("curse", duration = 100, source = p), hostile = true)
    assertEquals(-50, f.w.disposition(enemy2, p)) // Bane does not raise help
  }

  @Test
  fun `give command raises give standing event`() {
    val rules = RecordingRules(fs)
    val itemProto = ItemProto(Label("//t:apple"), "an apple", listOf("apple"))
    val f = createFixture(rules = rules, extraItems = listOf(itemProto))
    val p = f.player("Alice")
    val mob = f.mob(T.GOBLIN)
    f.w.spawnItem(itemProto.label, ItemLocation.Carried(p.id))

    f.input(p, "give apple to goblin")
    f.run(3)

    val giveEvent = rules.events.firstOrNull { it.event == "give" }
    assertEquals(mob, giveEvent?.actor) // affected in RulesContext is actor
    assertEquals(p, giveEvent?.target) // acting actor in RulesContext is target
    assertEquals(1L, giveEvent?.amount)
  }
}
