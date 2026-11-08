package gups.core.world

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.MobProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeashBehaviorTest {
  private fun corridor(proto: MobProto, n: Int = 8, vararg extra: MobProto): Fixture {
    val c = C.protos(n = n)
    return Fixture(
      protos =
        Prototypes(
          c.rooms.values.toList(),
          listOf(proto) + extra.toList() + c.mobs.values.toList(),
          zones = c.zones.values.toList(),
        )
    )
  }

  @Test
  fun `pursue stays inside the leash`() {
    val far =
      MobProto(
        Label("//c:hunter"),
        "hunter",
        listOf("hunter"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
        leash = 3,
      )
    val f = corridor(far)
    val h = f.w.spawnMob(far.label, f.w.room(C.r(0)))
    f.player("Bea", at = C.r(6))
    f.runTo(60)
    assertTrue(h.room.label in (0..3).map(C::r), "the hunter never goes past r3: ${h.room.label}")

    val near =
      MobProto(
        Label("//c:hunter2"),
        "hunter2",
        listOf("hunter2"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
        leash = 3,
      )
    val g = corridor(near)
    val h2 = g.w.spawnMob(near.label, g.w.room(C.r(0)))
    g.player("Bea", at = C.r(2))
    val visited = mutableListOf<Label>()
    while (g.w.pulse < 60) {
      g.run(1)
      visited += h2.room.label
    }
    assertTrue(visited.contains(C.r(2)), "a player inside the leash is reached: $visited")
  }

  @Test
  fun `a pulled guardian walks all the way home`() {
    val proto =
      MobProto(
        Label("//c:guardian"),
        "guardian",
        listOf("guardian"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
        leash = 3,
      )
    val f = corridor(proto)
    val h = f.w.spawnMob(proto.label, f.w.room(C.r(0)))
    val p = f.player("Bea", at = C.r(0))
    val visited = mutableListOf<Label>()
    repeat(3) {
      f.input(p, "east")
      repeat(4) {
        f.run(1)
        visited += h.room.label
      }
    }
    repeat(4) {
      f.input(p, "east")
      repeat(4) {
        f.run(1)
        visited += h.room.label
      }
    }
    assertTrue(visited.contains(C.r(3)), "the player pulls the guardian to its leash edge")
    assertEquals(C.r(7), p.room.label, "the player is past the leash")
    f.runTo(300)
    assertEquals(C.r(0), h.room.label, "the guardian walks home and stays")
  }

  @Test
  fun `a leashed wanderer returns to its home ground, not all the way home`() {
    val proto =
      MobProto(
        Label("//c:wanderer"),
        "wanderer",
        listOf("wanderer"),
        behaviors = listOf(Wander(radius = 1, chance = 0)),
        leash = 4,
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(4)))
    m.homeRoom = C.r(0)
    f.runTo(300)
    assertEquals(C.r(1), m.room.label, "the home ground is within(home, min(1, 4))")
  }

  @Test
  fun `chase outranks the return proposal`() {
    val proto =
      MobProto(
        Label("//c:chaser"),
        "chaser",
        listOf("chaser"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
        leash = 5,
      )
    val f = corridor(proto)
    val h = f.w.spawnMob(proto.label, f.w.room(C.r(4)))
    h.homeRoom = C.r(0)
    f.player("Bea", at = C.r(2))
    val visited = mutableListOf<Label>()
    while (f.w.pulse < 60) {
      f.run(1)
      visited += h.room.label
    }
    assertTrue(visited.contains(C.r(2)), "it reaches the player: $visited")
    val after = visited.drop(visited.indexOf(C.r(2)))
    assertTrue(
      after.all { it == C.r(2) },
      "it stays with the player instead of stepping home: $visited",
    )
    assertNull(h.awaySince, "being with its quarry is a break in its time away")
  }

  @Test
  fun `a pursuer away from home holds its return while its quarry is in the room`() {
    val seen = mutableListOf<List<String>>()
    val proto =
      MobProto(
        Label("//c:stalker"),
        "stalker",
        listOf("stalker"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
        leash = 3,
        think =
          ThinkFn { _, _, proposals ->
            seen += proposals.map { it.source }
            ThinkResult.Accept
          },
      )
    val f = corridor(proto)
    val h = f.w.spawnMob(proto.label, f.w.room(C.r(2)))
    h.homeRoom = C.r(0)
    f.player("Bea", at = C.r(2))
    f.runTo(60)
    assertEquals(C.r(2), h.room.label)
    assertTrue(seen.isNotEmpty() && seen.none { "leash" in it }, "no return proposal: $seen")
  }

  @Test
  fun `a pursuer with its quarry in the room still goes home when that room is past its leash`() {
    val proto =
      MobProto(
        Label("//c:tethered"),
        "tethered",
        listOf("tethered"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
        leash = 1,
      )
    val f = corridor(proto)
    val h = f.w.spawnMob(proto.label, f.w.room(C.r(3)))
    h.homeRoom = C.r(0)
    f.player("Bea", at = C.r(3))
    f.runTo(100)
    assertEquals(C.r(0), h.room.label)
  }

  @Test
  fun `sentinel removes the return proposal`() {
    val proto =
      MobProto(
        Label("//c:sentinel"),
        "sentinel",
        listOf("sentinel"),
        behaviors = listOf(Sentinel),
        leash = 5,
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(5)))
    f.runTo(300)
    assertEquals(C.r(5), m.room.label, "sentinel never leaves its room")
  }

  @Test
  fun `an engaged mob outside its home ground gets no return proposal`() {
    val seen = mutableListOf<Pair<Boolean, List<String>>>()
    val proto =
      MobProto(
        Label("//c:soldier"),
        "soldier",
        listOf("soldier"),
        behaviors = listOf(Aggressive(ActorFilter("mobs"))),
        leash = 3,
        think =
          ThinkFn { _, mob, proposals ->
            seen += mob.isEngaged to proposals.map { it.source }
            ThinkResult.Accept
          },
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(4)))
    m.homeRoom = C.r(0)
    f.w.spawnMob(C.RAT, f.w.room(C.r(4)))
    f.runTo(12)
    assertTrue(m.isEngaged)
    assertTrue(seen.any { it.first }, "a think in engagement: $seen")
    assertTrue(
      seen.filter { it.first }.all { "leash" !in it.second },
      "no return proposal while engaged: $seen",
    )
    assertNull(m.awaySince, "awaySince is cleared while engaged")
  }

  @Test
  fun `the return proposal sorts before wander and both step toward home`() {
    val seen = mutableListOf<List<Pair<String, String>>>()
    val proto =
      MobProto(
        Label("//c:order"),
        "order",
        listOf("order"),
        behaviors = listOf(Wander(3, 100)),
        leash = 4,
        think =
          ThinkFn { _, _, proposals ->
            seen += proposals.map { it.source to it.commandLine }
            ThinkResult.Idle
          },
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(4)))
    m.homeRoom = C.r(0)
    f.run(1)
    assertEquals(
      listOf("leash" to "west", "wander" to "west"),
      seen.first(),
      "the leash proposal at priority 20 sorts first, then wander, both toward home",
    )
  }

  @Test
  fun `wander never crosses the leash`() {
    val proto =
      MobProto(
        Label("//c:walker"),
        "walker",
        listOf("walker"),
        behaviors = listOf(Wander(5, 100)),
        leash = 1,
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(0)))
    val visited = mutableListOf<Label>()
    repeat(200) {
      f.run(1)
      visited += m.room.label
    }
    assertTrue(visited.all { it in listOf(C.r(0), C.r(1)) }, "visited $visited")
  }

  @Test
  fun `an un-leashed wanderer outside its radius steps home and gets no leash proposal`() {
    val seen = mutableListOf<List<String>>()
    val proto =
      MobProto(
        Label("//c:stray"),
        "stray",
        listOf("stray"),
        behaviors = listOf(Wander(2, 100)),
        think =
          ThinkFn { _, _, proposals ->
            seen += proposals.map { it.source }
            ThinkResult.Accept
          },
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(5)))
    m.homeRoom = C.r(0)
    f.run(3)
    assertTrue(m.room.label in listOf(C.r(2), C.r(3), C.r(4)), "it steps toward home")
    f.runTo(200)
    assertTrue(seen.all { "leash" !in it }, "an un-leashed mob never gets a leash proposal")
  }

  @Test
  fun `follow gives up past the leash and resumes when the leader returns`() {
    val proto =
      MobProto(
        Label("//c:pack"),
        "pack",
        listOf("pack"),
        behaviors = listOf(Follow(ActorFilter("players"))),
        leash = 2,
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(0)))
    val p = f.player("Bea", at = C.r(0))
    f.run(2)
    assertEquals(p.id, m.leader)
    repeat(2) {
      f.input(p, "east")
      f.run(4)
    }
    assertEquals(C.r(2), m.room.label, "follows to the edge of the leash")
    f.input(p, "east")
    f.run(4)
    f.runTo(300)
    assertEquals(C.r(0), m.room.label, "walks home without bouncing")
    assertEquals(p.id, m.leader, "keeps its leader")
    repeat(2) {
      f.input(p, "west")
      f.run(4)
    }
    assertEquals(C.r(1), p.room.label, "the leader is back inside the leash")
    f.run(8)
    val visited = mutableListOf<Label>()
    repeat(40) {
      f.run(1)
      visited += m.room.label
    }
    assertTrue(visited.all { it == C.r(1) }, "follows again and stays with its leader: $visited")
  }

  @Test
  fun `follow still forgets a leader more than three exits away while it is outside the leash`() {
    val proto =
      MobProto(
        Label("//c:hound"),
        "hound",
        listOf("hound"),
        behaviors = listOf(Follow(ActorFilter("players"))),
        leash = 1,
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(0)))
    val p = f.player("Bea", at = C.r(0))
    f.run(2)
    assertEquals(p.id, m.leader)
    repeat(5) {
      f.input(p, "east")
      f.run(1)
    }
    assertEquals(C.r(5), p.room.label)
    f.run(12)
    assertNull(m.leader, "a leader past the 3-exit limit is forgotten")
  }

  @Test
  fun `an unleashed override lets a pursuer cross the chain, and reset brings the leash back`() {
    val seen = mutableListOf<List<String>>()
    val proto =
      MobProto(
        Label("//c:crosser"),
        "crosser",
        listOf("crosser"),
        behaviors = listOf(Pursue(ActorFilter("players"))),
        leash = 2,
        think =
          ThinkFn { _, _, proposals ->
            seen += proposals.map { it.source }
            ThinkResult.Accept
          },
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(0)))
    val p = f.player("Bea", at = C.r(6))
    f.runTo(30)
    assertEquals(C.r(0), m.room.label, "a player outside the leash is never pursued")
    f.w.setLeash(m, null)
    f.runTo(200)
    assertEquals(C.r(6), m.room.label, "an unleashed pursuer crosses the chain")
    assertTrue(seen.none { "leash" in it }, "an unleashed mob gets no return proposal")
    seen.clear()
    f.w.resetLeash(m)
    f.runTo(500)
    assertEquals(C.r(0), m.room.label, "the reset leash brings it all the way home")
    assertTrue(seen.any { "leash" in it }, "the return proposal resumes after the reset")
  }

  @Test
  fun `an owned mob ignores its leash and follows its owner`() {
    val seen = mutableListOf<List<String>>()
    val proto =
      MobProto(
        Label("//c:pet"),
        "pet",
        listOf("pet"),
        leash = 1,
        think =
          ThinkFn { _, _, proposals ->
            seen += proposals.map { it.source }
            ThinkResult.Accept
          },
      )
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(0)))
    val p = f.player("Bea", at = C.r(0))
    f.w.setOwner(m, p)
    repeat(5) {
      f.input(p, "east")
      f.run(4)
    }
    f.runTo(300)
    assertEquals(C.r(5), m.room.label, "heel follows the owner past the leash")
    assertTrue(seen.none { "leash" in it }, "an owned mob gets no return proposal")
  }

  @Test
  fun `awaySince counts pulses away from home and clears on arrival`() {
    val proto = MobProto(Label("//c:exile"), "exile", listOf("exile"), leash = 3)
    val f = corridor(proto)
    val m = f.w.spawnMob(proto.label, f.w.room(C.r(4)))
    m.homeRoom = C.r(0)
    f.run(1)
    val away = m.awaySince
    assertNotNull(away, "set on the first think outside the home ground")
    f.run(2)
    assertEquals(away, m.awaySince, "unchanged on later thinks")
    f.runTo(300)
    assertEquals(C.r(0), m.room.label)
    assertNull(m.awaySince, "cleared once home")
  }

  @Test
  fun `closing a door invalidates the home area cache`() {
    val w = Fixture().w
    val hall = w.room(T.HALL)
    w.setDoor(hall, Direction.SOUTH, DoorState.OPEN)
    val open = w.homeArea(hall, 2)
    assertTrue(w.room(T.VAULT) in open)
    w.setDoor(hall, Direction.SOUTH, DoorState.CLOSED)
    val closed = w.homeArea(hall, 2)
    assertFalse(w.room(T.VAULT) in closed, "a closed door cuts the route")
    assertFalse(closed === open, "the cache was cleared, not reused")
  }
}
