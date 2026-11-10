package gups.core.world

import gups.core.Label
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.Direction
import gups.core.model.ExitProto
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorTest {
  private fun mob(name: String, vararg behaviors: Behavior, hp: Int = 40) =
    MobProto(
      Label("//t:$name"),
      name,
      listOf(name),
      vitals = mapOf("hp" to hp),
      behaviors = behaviors.toList(),
    )

  private fun fixture(vararg mobs: MobProto, rules: FixedRules = FixedRules(flee = 100)) =
    Fixture(rules = rules, protos = T.protos(extraMobs = mobs.toList()))

  @Test
  fun `aggressive mobs attack matching actors on sight, unless they outlevel the limit`() {
    val f = fixture(mob("brute", Aggressive(ActorFilter("players"), unlessLevelAbove = 5)))
    val brute = f.mob(Label("//t:brute"))
    val p = f.player("Ann")
    f.runTo(3)
    assertTrue(p.id in brute.fighting)
    assertEquals(p.id, brute.primaryTarget)

    val g = fixture(mob("brute", Aggressive(ActorFilter("players"), unlessLevelAbove = 5)))
    val b2 = g.mob(Label("//t:brute"))
    val hero = g.player("Bea")
    hero.level = 9
    g.runTo(20)
    assertFalse(b2.isEngaged)
  }

  @Test
  fun `aggressive notices a target only through a sensed channel`() {
    // Sight 1 (the builtin default) notices a player in the room.
    val see = fixture(mob("seer", Aggressive(ActorFilter("players"))))
    val seer = see.mob(Label("//t:seer"))
    val a = see.player("Ann")
    see.runTo(3)
    assertTrue(a.id in seer.fighting, "sight 1 notices in the room")

    // Both built-in senses at 0 notices nothing.
    val blind =
      fixture(
        mob("blind", Aggressive(ActorFilter("players")))
          .copy(senses = mapOf(Channel.SIGHT to 0, Channel.SOUND to 0))
      )
    val b = blind.mob(Label("//t:blind"))
    val bAnn = blind.player("Ann")
    blind.runTo(3)
    assertFalse(bAnn.id in b.fighting, "sight 0, sound 0: not noticed")

    // Sight 0 with builtin sound 1 notices it.
    val ear =
      fixture(
        mob("ear", Aggressive(ActorFilter("players"))).copy(senses = mapOf(Channel.SIGHT to 0))
      )
    val e = ear.mob(Label("//t:ear"))
    val eAnn = ear.player("Ann")
    ear.runTo(3)
    assertTrue(eAnn.id in e.fighting, "sound 1 notices")
  }

  @Test
  fun `aggressive skips a candidate the rules hide and attacks the next noticed one`() {
    val double = PerceiveRules { c -> c.source?.name != "Hid" }
    val f = fixture(mob("hunter", Aggressive(ActorFilter("players"))), rules = double)
    val hunter = f.mob(Label("//t:hunter"))
    val hid = f.player("Hid")
    val ann = f.player("Ann")
    f.runTo(3)
    assertTrue(ann.id in hunter.fighting, "attacks the noticed player")
    assertFalse(hid.id in hunter.fighting, "the hidden one is skipped")
  }

  @Test
  fun `aggressive asks can_perceive about a notice as the mob, of the candidate`() {
    val seen = mutableListOf<RulesContext>()
    val double = PerceiveRules { c ->
      seen += c
      true
    }
    val f = fixture(mob("hunter", Aggressive(ActorFilter("players"))), rules = double)
    val hunter = f.mob(Label("//t:hunter"))
    val ann = f.player("Ann")
    f.runTo(3)
    val c = seen.first()
    assertEquals("notice", c.event)
    assertEquals(Channel.SIGHT, c.channel)
    assertTrue(c.actor === hunter && c.target === hunter, "asked as the mob")
    assertTrue(c.source === ann, "about the candidate")
    assertTrue(c.room === hunter.room && c.sourceRoom === hunter.room, "in the mob's room")
    assertEquals(1, c.intensity)
    assertEquals(0, c.cost)
  }

  @Test
  fun `aggressive chance gates the look`() {
    // chance 0 draws nothing and never fights.
    val f = fixture(mob("brute", Aggressive(ActorFilter("players"), chance = 0)))
    val brute = f.mob(Label("//t:brute"))
    f.player("Ann")
    f.w.rng.force(ints = listOf(1))
    f.runTo(3)
    assertFalse(brute.isEngaged, "chance 0 never fights")
    assertEquals(1, f.w.rng.forcedRemaining, "chance 0 draws nothing")

    // chance 50 with a 50 notices and fights.
    val g = fixture(mob("brute", Aggressive(ActorFilter("players"), chance = 50)))
    val b2 = g.mob(Label("//t:brute"))
    val ann = g.player("Ann")
    g.w.rng.force(ints = List(20) { 50 })
    g.runTo(3)
    assertTrue(ann.id in b2.fighting, "a 50 passes chance 50")

    // chance 50 with only 51s never notices, though the look still draws.
    val h = fixture(mob("brute", Aggressive(ActorFilter("players"), chance = 50)))
    val b3 = h.mob(Label("//t:brute"))
    val ann2 = h.player("Ann")
    h.w.rng.force(ints = List(20) { 51 })
    h.runTo(3)
    assertFalse(ann2.id in b3.fighting, "a 51 misses chance 50")
    assertTrue(h.w.rng.forcedRemaining < 20, "the look drew")

    // A missed look only idles that think: a later passing roll attacks.
    h.w.rng.clearForced()
    h.w.rng.force(ints = List(20) { 1 })
    h.runTo(20)
    assertTrue(ann2.id in b3.fighting, "a later look that passes attacks")
  }

  @Test
  fun `aggressive unless_level_below spares a lower level and attacks the next`() {
    val f = fixture(mob("brute", Aggressive(ActorFilter("players"), unlessLevelBelow = 5)))
    val brute = f.mob(Label("//t:brute"))
    val bea = f.player("Bea")
    bea.level = 4
    val ann = f.player("Ann")
    ann.level = 5
    f.runTo(3)
    assertTrue(ann.id in brute.fighting, "level 5 is not below 5, attacked")
    assertFalse(bea.id in brute.fighting, "level 4 is below 5, spared")

    val g =
      fixture(
        mob(
          "brute",
          Aggressive(ActorFilter("players"), unlessLevelBelow = 5, unlessLevelAbove = 10),
        )
      )
    val b2 = g.mob(Label("//t:brute"))
    val low = g.player("Low")
    low.level = 4
    val mid = g.player("Mid")
    mid.level = 7
    val high = g.player("High")
    high.level = 11
    g.runTo(3)
    assertTrue(mid.id in b2.fighting, "level 7 is in the band 5 to 10")
    assertFalse(low.id in b2.fighting, "level 4 is below the band")
    assertFalse(high.id in b2.fighting, "level 11 is above the band")
  }

  @Test
  fun `aggressive does not ask can_perceive for a candidate toward or levels exclude`() {
    // A level above unless_level_above: not asked about.
    val r1 = PerceiveRules { true }
    val f1 =
      fixture(mob("brute", Aggressive(ActorFilter("players"), unlessLevelAbove = 5)), rules = r1)
    f1.mob(Label("//t:brute"))
    val p1 = f1.player("Ann")
    p1.level = 9
    f1.runTo(3)
    assertEquals(0, r1.looks.size, "a level above the limit is not asked about")

    // A player that toward does not match: not asked about.
    val r2 = PerceiveRules { true }
    val f2 = fixture(mob("brute", Aggressive(ActorFilter("mobs"))), rules = r2)
    f2.mob(Label("//t:brute"))
    f2.player("Ann")
    f2.runTo(3)
    assertEquals(0, r2.looks.size, "a player is not asked for a mob-only filter")
  }

  @Test
  fun `aggressive tries sensed channels in order and stops at the first noticed`() {
    // Only sound passes: sight is tried first and fails, then sound passes.
    val r = PerceiveRules { c -> c.channel == Channel.SOUND }
    val f = fixture(mob("ear", Aggressive(ActorFilter("players"))), rules = r)
    val ear = f.mob(Label("//t:ear"))
    val ann = f.player("Ann")
    f.runTo(3)
    assertTrue(ann.id in ear.fighting, "sound notices it")
    assertEquals(
      listOf(Channel.SIGHT.name, Channel.SOUND.name),
      r.looks.map { it.first },
      "sight is tried first, then sound",
    )

    // Both channels pass: sight passes, so sound is never asked.
    val r2 = PerceiveRules { true }
    val f2 = fixture(mob("seer", Aggressive(ActorFilter("players"))), rules = r2)
    f2.mob(Label("//t:seer"))
    f2.player("Ann")
    f2.runTo(3)
    assertEquals(
      listOf(Channel.SIGHT.name),
      r2.looks.map { it.first },
      "stops at the first noticed channel",
    )
  }

  @Test
  fun `assist joins an ally's fight against its opponent`() {
    val f = fixture(mob("helper", Assist(ActorFilter("goblin"))))
    val p = f.player("Ann")
    val goblin = f.mob(T.GOBLIN)
    val helper = f.mob(Label("//t:helper"))
    f.input(p, "kill goblin")
    f.runTo(6)
    assertTrue(goblin.id in p.fighting)
    assertTrue(p.id in helper.fighting)
  }

  @Test
  fun `flee runs at the threshold and beats every other proposal`() {
    val f = fixture(mob("coward", Aggressive(ActorFilter("players")), Flee(mapOf("hp" to 20))))
    val coward = f.mob(Label("//t:coward"))
    f.player("Ann")
    f.setVital(coward, "hp", 8)
    f.runTo(3)
    assertTrue(coward.room.label != T.HALL, "fled to ${coward.room.label}")
    assertFalse(coward.isEngaged)
  }

  @Test
  fun `flee ignores a listed vital the mob has no maximum of`() {
    val f = fixture(mob("calm", Flee(mapOf("mana" to 50))))
    val calm = f.mob(Label("//t:calm"))
    assertEquals(0, calm.max("mana"))
    f.runTo(10)
    assertEquals(T.HALL, calm.room.label)
  }

  @Test
  fun `fleeing combat doesn't slip past guarded exits`() {
    val f =
      fixture(
        mob("north_warden", Guard(Direction.NORTH)),
        mob("east_warden", Guard(Direction.EAST)),
      )
    f.mob(Label("//t:north_warden"))
    f.mob(Label("//t:east_warden"))
    val p = f.player("Ann")
    f.w.startCombat(p, f.mob(T.GOBLIN))
    f.input(p, "flee")
    f.run()
    // The vault door is locked and the other two exits are guarded, so every flee is denied.
    assertContains(f.out(p), "blocks your way.")
    assertEquals(T.HALL, p.room.label)
    assertTrue(p.isEngaged)
    assertEquals(4, p.waitState, "a denied flee lags like a failed one")
  }

  @Test
  fun `a guard bars its exit to non-allies while it can act`() {
    val f = fixture(mob("warden", Guard(Direction.NORTH)))
    val warden = f.mob(Label("//t:warden"))
    val p = f.player("Ann")
    f.input(p, "north")
    f.run()
    assertContains(f.out(p), "Warden blocks your way.")
    assertEquals(T.HALL, p.room.label)
    val g = f.mob(T.GOBLIN)
    f.w.actMove(g, Direction.NORTH)
    f.runTo(3)
    assertEquals(T.YARD, g.room.label, "mobs are the guard's allies by default")
    f.w.applyAffect(warden, AffectSpec("stun", 50, flags = setOf("stunned")))
    f.input(p, "north")
    f.run()
    assertEquals(T.YARD, p.room.label)
  }

  @Test
  fun `wander stays within its radius of home`() {
    val protos = C.protos(n = 12)
    val walker =
      MobProto(
        Label("//c:walker"),
        "walker",
        listOf("walker"),
        behaviors = listOf(Wander(radius = 2, chance = 100)),
      )
    val f =
      Fixture(
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            listOf(walker),
            zones = protos.zones.values.toList(),
          )
      )
    val m = f.w.spawnMob(walker.label, f.w.room(C.r(5)))
    val seen = mutableSetOf<String>()
    repeat(600) {
      f.run()
      seen += m.room.label.name
    }
    assertEquals(setOf("r3", "r4", "r5", "r6", "r7"), seen)
  }

  @Test
  fun `wander with chance 0 never steps and draws nothing`() {
    val protos = C.protos(n = 12)
    val walker =
      MobProto(
        Label("//c:walker0"),
        "walker0",
        listOf("walker0"),
        behaviors = listOf(Wander(radius = 2, chance = 0)),
      )
    val f =
      Fixture(
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            listOf(walker),
            zones = protos.zones.values.toList(),
          )
      )
    val m = f.w.spawnMob(walker.label, f.w.room(C.r(5)))
    val start = m.room.label
    f.w.rng.force(ints = listOf(5))
    repeat(60) { f.run() }
    assertEquals(start, m.room.label, "chance 0 must never move")
    assertEquals(1, f.w.rng.forcedRemaining, "chance 0 must not draw")
  }

  @Test
  fun `sentinel keeps a mob in its room whatever else it wants`() {
    val f = fixture(mob("statue", Wander(3, 100), Flee(mapOf("hp" to 90)), Sentinel))
    val statue = f.mob(Label("//t:statue"))
    f.setVital(statue, "hp", 1)
    f.runTo(100)
    assertEquals(T.HALL, statue.room.label)
  }

  @Test
  fun `follow trails its leader and pursue paths across the world`() {
    val f = fixture(mob("pup", Follow(ActorFilter("players"))))
    val pup = f.mob(Label("//t:pup"))
    val p = f.player("Ann")
    f.run(4) // the pup notices Ann
    f.input(p, "north")
    f.runTo(12)
    assertEquals(T.YARD, pup.room.label)

    val protos = C.protos(n = 8)
    val hunter =
      MobProto(
        Label("//c:hunter"),
        "hunter",
        listOf("hunter"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
      )
    val g =
      Fixture(
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            listOf(hunter),
            zones = protos.zones.values.toList(),
          )
      )
    val h = g.w.spawnMob(hunter.label, g.w.room(C.r(0)))
    g.player("Bea", at = C.r(6))
    g.runTo(60)
    assertEquals(C.r(6), h.room.label)
  }

  @Test
  fun `pursue ignores matches beyond its range`() {
    val protos = C.protos(n = 8)
    val hunter =
      MobProto(
        Label("//c:hunter_range"),
        "hunter_range",
        listOf("hunter_range"),
        behaviors = listOf(Pursue(ActorFilter("players"), range = 3)),
      )
    val f =
      Fixture(
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            listOf(hunter),
            zones = protos.zones.values.toList(),
          )
      )
    val h = f.w.spawnMob(hunter.label, f.w.room(C.r(0)))
    f.player("Bea", at = C.r(6))
    f.runTo(60)
    assertEquals(C.r(0), h.room.label, "a match 6 away is beyond range 3")

    val protos2 = C.protos(n = 8)
    val hunter6 =
      MobProto(
        Label("//c:hunter_range6"),
        "hunter_range6",
        listOf("hunter_range6"),
        behaviors = listOf(Pursue(ActorFilter("players"), range = 6)),
      )
    val g =
      Fixture(
        protos =
          Prototypes(
            protos2.rooms.values.toList(),
            listOf(hunter6),
            zones = protos2.zones.values.toList(),
          )
      )
    val h2 = g.w.spawnMob(hunter6.label, g.w.room(C.r(0)))
    g.player("Bea", at = C.r(6))
    g.runTo(60)
    assertEquals(C.r(6), h2.room.label, "range 6 reaches a match 6 away")
  }

  @Test
  fun `pursue with no range crosses the world`() {
    val protos = C.protos(n = 16)
    val hunter =
      MobProto(
        Label("//c:hunter_norange"),
        "hunter_norange",
        listOf("hunter_norange"),
        behaviors = listOf(Pursue(ActorFilter("players"), range = null)),
      )
    val f =
      Fixture(
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            listOf(hunter),
            zones = protos.zones.values.toList(),
          )
      )
    val h = f.w.spawnMob(hunter.label, f.w.room(C.r(0)))
    f.player("Bea", at = C.r(15))
    f.runTo(120)
    assertEquals(C.r(15), h.room.label, "no range searches the whole world")
  }

  private fun diamondRooms() =
    listOf(
      RoomProto(
        Label("//c:p0"),
        "P0",
        exits =
          listOf(
            ExitProto(Direction.EAST, Label("//c:p1")),
            ExitProto(Direction.NORTH, Label("//c:p2")),
            ExitProto(Direction.SOUTH, Label("//c:p3")),
          ),
      ),
      RoomProto(
        Label("//c:p1"),
        "P1",
        exits =
          listOf(
            ExitProto(Direction.WEST, Label("//c:p0")),
            ExitProto(Direction.EAST, Label("//c:p4")),
          ),
      ),
      RoomProto(
        Label("//c:p2"),
        "P2",
        exits =
          listOf(
            ExitProto(Direction.SOUTH, Label("//c:p0")),
            ExitProto(Direction.NORTH, Label("//c:p5")),
          ),
      ),
      RoomProto(Label("//c:p3"), "P3", exits = listOf(ExitProto(Direction.NORTH, Label("//c:p0")))),
      RoomProto(Label("//c:p4"), "P4", exits = listOf(ExitProto(Direction.WEST, Label("//c:p1")))),
      RoomProto(Label("//c:p5"), "P5", exits = listOf(ExitProto(Direction.SOUTH, Label("//c:p2")))),
    )

  /** The first room the mob enters after [start], watching up to 10 pulses. */
  private fun Fixture.firstRoomEntered(m: Mob, start: Label, pulses: Int = 10): Label? {
    var first: Label? = null
    repeat(pulses) {
      run()
      if (first == null && m.room.label != start) first = m.room.label
    }
    return first
  }

  @Test
  fun `pursue picks the nearest, then the lowest id`() {
    // Two players two exits away over different routes: the lower-id one wins the first step.
    val h1 =
      MobProto(
        Label("//c:h1"),
        "h1",
        listOf("h1"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
      )
    val f1 = Fixture(protos = Prototypes(diamondRooms(), listOf(h1)))
    val m1 = f1.w.spawnMob(h1.label, f1.w.room(Label("//c:p0")))
    val ann = f1.player("Ann", at = Label("//c:p4"))
    val bea = f1.player("Bea", at = Label("//c:p5"))
    assertTrue(ann.id < bea.id, "Ann has the lower id")
    assertEquals(
      Label("//c:p1"),
      f1.firstRoomEntered(m1, Label("//c:p0")),
      "the first step heads toward the lower-id player at the same distance",
    )

    // A nearer player is reached first whatever the ids.
    val h2 =
      MobProto(
        Label("//c:h2"),
        "h2",
        listOf("h2"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
      )
    val f2 = Fixture(protos = Prototypes(diamondRooms(), listOf(h2)))
    val m2 = f2.w.spawnMob(h2.label, f2.w.room(Label("//c:p0")))
    f2.player("Ann", at = Label("//c:p4"))
    f2.player("Bea", at = Label("//c:p5"))
    f2.player("Cy", at = Label("//c:p3"))
    assertEquals(
      Label("//c:p3"),
      f2.firstRoomEntered(m2, Label("//c:p0")),
      "the nearer player wins over both",
    )
  }

  @Test
  fun `pursue skips dead candidates`() {
    val protos = C.protos(n = 8)
    val hunter =
      MobProto(
        Label("//c:hunter_dead"),
        "hunter_dead",
        listOf("hunter_dead"),
        behaviors = listOf(Pursue(ActorFilter("players"), range = 3)),
      )
    val f =
      Fixture(
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            listOf(hunter),
            zones = protos.zones.values.toList(),
          )
      )
    val h = f.w.spawnMob(hunter.label, f.w.room(C.r(0)))
    val p = f.player("Bea", at = C.r(2))
    p.condition = Condition.DEAD
    f.runTo(40)
    assertEquals(C.r(0), h.room.label, "the hunter ignores the dead player and stays put")
  }

  @Test
  fun `pursue first step matches path_to`() {
    // Two equal-length routes from r0 to the target room.
    val r0 = Label("//c:q0")
    val rooms =
      listOf(
        RoomProto(
          r0,
          "Q0",
          exits =
            listOf(
              ExitProto(Direction.EAST, Label("//c:qA")),
              ExitProto(Direction.NORTH, Label("//c:qB")),
            ),
        ),
        RoomProto(
          Label("//c:qA"),
          "QA",
          exits = listOf(ExitProto(Direction.WEST, r0), ExitProto(Direction.EAST, Label("//c:qT"))),
        ),
        RoomProto(
          Label("//c:qB"),
          "QB",
          exits = listOf(ExitProto(Direction.SOUTH, r0), ExitProto(Direction.EAST, Label("//c:qT"))),
        ),
        RoomProto(
          Label("//c:qT"),
          "QT",
          exits =
            listOf(
              ExitProto(Direction.WEST, Label("//c:qA")),
              ExitProto(Direction.WEST, Label("//c:qB")),
            ),
        ),
      )
    val hunter =
      MobProto(
        Label("//c:q_h"),
        "q_h",
        listOf("q_h"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
      )
    val f = Fixture(protos = Prototypes(rooms, listOf(hunter)))
    val h = f.w.spawnMob(hunter.label, f.w.room(r0))
    val p = f.player("Bea", at = Label("//c:qT"))
    val proposed = Pursue(ActorFilter("players")).propose(f.w, h)!!
    assertEquals(f.w.pathTo(h.room, p.room)!!.first().name, proposed.commandLine)
  }

  @Test
  fun `pursue stays local however many players are online`() {
    val protos = C.protos(n = 12)
    val hunter =
      MobProto(
        Label("//c:hunter_many"),
        "hunter_many",
        listOf("hunter_many"),
        behaviors = listOf(Pursue(ActorFilter("players"), range = 3)),
      )
    val f =
      Fixture(
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            listOf(hunter),
            zones = protos.zones.values.toList(),
          )
      )
    val h = f.w.spawnMob(hunter.label, f.w.room(C.r(0)))
    repeat(20) { i -> f.player("P$i", at = C.r(4 + (i % 8))) }
    f.w.pathCache.clear()
    f.runTo(40)
    assertTrue(f.w.pathCache.isEmpty(), "the search never calls pathTo, so pathCache stays empty")
    assertEquals(C.r(0), h.room.label, "no match in range, the hunter stays put")
  }

  @Test
  fun `scavengers pick up what they can reach, but not corpses`() {
    val f = fixture(mob("magpie", Scavenge()))
    val magpie = f.mob(Label("//t:magpie"))
    val hall = f.w.room(T.HALL)
    val corpse =
      f.w.spawnItem(gups.core.model.Prototypes.CORPSE.label, ItemLocation.InRoom(hall.id, 0))
    val far = f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 4))
    val key = f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 0))
    f.runTo(10)
    assertEquals(ItemLocation.Carried(magpie.id), key.location)
    assertTrue(corpse.location is ItemLocation.InRoom)
    assertTrue(far.location is ItemLocation.InRoom, "out of reach at FAR")
  }

  @Test
  fun `think sees proposals sorted by priority and can override them`() {
    val seen = mutableListOf<List<String>>()
    val proto =
      mob("thinker", Wander(1, 100), Aggressive(ActorFilter("players")))
        .copy(
          think =
            ThinkFn { _, _, proposals ->
              seen += proposals.map { it.source }
              ThinkResult.Idle
            }
        )
    val f = fixture(proto)
    val m = f.mob(proto.label)
    f.player("Ann")
    f.run()
    assertEquals(listOf("aggressive", "wander"), seen.first())
    assertFalse(m.isEngaged)
    assertEquals(T.HALL, m.room.label)
  }
}

/** A rules double that records every `canPerceive` look and answers by [decide]. */
class PerceiveRules(private val decide: (RulesContext) -> Boolean) : FixedRules() {
  /** One entry per look: the channel asked and the candidate's name. */
  val looks = mutableListOf<Pair<String, String?>>()

  override fun canPerceive(c: RulesContext): Boolean {
    looks += (c.channel?.name ?: "?") to c.source?.name
    return decide(c)
  }
}
