package gups.core.world

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.fixtures.FixtureSchemas
import gups.core.model.ContainerProto
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.EmptyAction
import gups.core.model.ExitProto
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.Respawn
import gups.core.model.Roll
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.Until
import gups.core.model.ZoneProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemSpawnRulesTest {
  private val hall = Label("//s:hall")
  private val yard = Label("//s:yard")
  private val goblin = Label("//s:goblin")
  private val gem = Label("//s:gem")
  private val ruby = Label("//s:ruby")
  private val arrow = Label("//s:arrow")
  private val potion = Label("//s:potion")
  private val rot = Label("//s:rot")
  private val chest = Label("//s:chest")
  private val key = Label("//s:key")
  private val smallBox = Label("//s:small_box")

  private fun protos(spawns: List<SpawnRule>): Prototypes =
    Prototypes(
      rooms =
        listOf(
          RoomProto(
            hall,
            "Hall",
            exits = listOf(ExitProto(Direction.NORTH, yard)),
            spawns = spawns,
          ),
          RoomProto(yard, "Yard", exits = listOf(ExitProto(Direction.SOUTH, hall))),
        ),
      mobs =
        listOf(
          MobProto(
            goblin,
            "a goblin",
            listOf("goblin"),
            vitals = mapOf(FixtureSchemas.current.health to 10),
          )
        ),
      items =
        listOf(
          ItemProto(gem, "a gem", listOf("gem"), weight = 2),
          ItemProto(ruby, "a ruby", listOf("ruby"), weight = 2),
          ItemProto(arrow, "an arrow", listOf("arrow"), weight = 1, stackable = true),
          ItemProto(potion, "a potion", listOf("potion"), charges = 1, empty = EmptyAction.Destroy),
          ItemProto(rot, "a scrap", listOf("scrap"), decayPulses = WORLD_TICK_PULSES),
          ItemProto(key, "a key", listOf("key")),
          ItemProto(
            chest,
            "a chest",
            listOf("chest"),
            container =
              ContainerProto(capacity = 5, maxWeight = 50, state = DoorState.LOCKED, key = key),
          ),
          ItemProto(
            smallBox,
            "a small box",
            listOf("box"),
            container = ContainerProto(capacity = 1, maxWeight = 2, state = DoorState.OPEN),
          ),
        ),
      zones = listOf(ZoneProto("s")),
    )

  private fun itemsOf(f: Fixture, label: Label): List<Item> =
    f.w.allEntities.filterIsInstance<Item>().filter { it.protoLabel == label }

  private fun unstartedWorld(spawns: List<SpawnRule>): World {
    val fs = FixtureSchemas.current
    return World(
      protos(spawns),
      fs.schema,
      FixedRules(fs),
      ScriptHost.NONE,
      WorldConfig(seed = 1, checkInvariants = true, strict = true),
    )
  }

  @Test
  fun `gone item keeps counting when picked up, and destroying it releases and arms the timer`() {
    val f =
      Fixture(
        protos =
          protos(
            listOf(SpawnRule(gem, max = 1, respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES))))
          )
      )
    val state = f.w.spawnStates.single()
    val copy = itemsOf(f, gem).single()
    assertEquals(1, state.tracked)
    assertEquals(hall, copy.spawnHome)
    assertNull(state.respawnAt)

    val p = f.player("Ann", hall)
    f.w.moveItem(copy, ItemLocation.Carried(p.id))
    f.w.placeActor(p, f.w.room(yard), 0)
    f.runTo(WORLD_TICK_PULSES * 2L)
    assertEquals(1, state.tracked)
    assertNull(state.respawnAt)

    f.w.destroy(copy)
    assertEquals(0, state.tracked)
    assertEquals(f.w.pulse + WORLD_TICK_PULSES, state.respawnAt)

    f.run(WORLD_TICK_PULSES)
    assertEquals(1, state.tracked)
    assertEquals(1, f.w.room(hall).items.count { it.protoLabel == gem })
  }

  @Test
  fun `decay and empty release a gone copy`() {
    val f =
      Fixture(
        protos =
          protos(
            listOf(
              SpawnRule(rot, max = 1, respawn = Respawn.Never),
              SpawnRule(potion, max = 1, respawn = Respawn.Never),
            )
          )
      )
    val rotState = f.w.spawnStates[0]
    val potionState = f.w.spawnStates[1]
    val pItem = itemsOf(f, potion).single()
    assertEquals(1, rotState.tracked)
    assertEquals(1, potionState.tracked)

    // Spend the potion's last charge -> EmptyAction.Destroy releases it.
    f.w.spendCharge(pItem)
    assertEquals(0, potionState.tracked)

    // Advance one world tick -> scrap decays and is released.
    f.runTo(WORLD_TICK_PULSES.toLong())
    assertEquals(0, rotState.tracked)
  }

  @Test
  fun `merging copy into another stack releases it, while merging another into copy keeps it`() {
    val f = Fixture(protos = protos(listOf(SpawnRule(arrow, max = 1, respawn = Respawn.Never))))
    val state = f.w.spawnStates.single()
    val copy = itemsOf(f, arrow).single()
    val hallRoom = f.w.room(hall)

    // Spawn an untracked stack AFTER copy (higher id) and merge it into copy.
    val extra = f.w.spawnItem(arrow, ItemLocation.InRoom(hallRoom.id, 0), count = 2)
    val mergedIntoCopy = f.w.stack(extra)
    assertEquals(copy.id, mergedIntoCopy.id)
    assertEquals(3, copy.count)
    assertEquals(1, state.tracked)
    assertEquals(state, copy.spawnedBy)

    // Move copy to yard where an untracked stack already sits with lower id, then merge copy into
    // it.
    val yardRoom = f.w.room(yard)
    val target = f.w.spawnItem(arrow, ItemLocation.InRoom(yardRoom.id, 0), count = 1)
    f.w.moveItem(copy, ItemLocation.InRoom(yardRoom.id, 0))
    val mergedIntoTarget = f.w.stack(copy)
    assertEquals(target.id, mergedIntoTarget.id)
    assertEquals(0, state.tracked)
    assertNull(target.spawnedBy)
  }

  @Test
  fun `transform keeps the copy counting`() {
    val f = Fixture(protos = protos(listOf(SpawnRule(gem, max = 1, respawn = Respawn.Never))))
    val state = f.w.spawnStates.single()
    val copy = itemsOf(f, gem).single()

    f.w.transformItem(copy, ruby)
    assertEquals(ruby, copy.protoLabel)
    assertEquals(1, state.tracked)
    assertEquals(state, copy.spawnedBy)
  }

  @Test
  fun `taken counts while carried by a mob in the room, releases after leaving, keeps if dropped back before tick`() {
    val f =
      Fixture(
        protos =
          protos(
            listOf(
              SpawnRule(
                gem,
                max = 1,
                until = Until.TAKEN,
                respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES)),
              )
            )
          )
      )
    val state = f.w.spawnStates.single()
    val copy = itemsOf(f, gem).single()
    val hallRoom = f.w.room(hall)
    val yardRoom = f.w.room(yard)

    // Carried by a mob in the room: still in home room's location chain -> still counts.
    val mob = f.w.spawnMob(goblin, hallRoom)
    f.w.moveItem(copy, ItemLocation.Carried(mob.id))
    f.run(WORLD_TICK_PULSES)
    assertEquals(1, state.tracked)
    assertNull(state.respawnAt)

    // Player walks out with it, then drops it back in the hall before the next world tick -> keeps
    // counting.
    val p = f.player("Ann", hall)
    f.w.moveItem(copy, ItemLocation.Carried(p.id))
    f.w.placeActor(p, yardRoom, 0)
    f.run(WORLD_TICK_PULSES / 2)
    f.w.placeActor(p, hallRoom, 0)
    f.w.moveItem(copy, ItemLocation.InRoom(hallRoom.id, 0))
    f.run(WORLD_TICK_PULSES / 2)
    assertEquals(1, state.tracked)
    assertNull(state.respawnAt)

    // Player walks out and stays out across a world tick -> released and timer armed.
    f.w.moveItem(copy, ItemLocation.Carried(p.id))
    f.w.placeActor(p, yardRoom, 0)
    f.run(WORLD_TICK_PULSES)
    assertEquals(0, state.tracked)
    assertNull(copy.spawnedBy)
    assertEquals(f.w.pulse + WORLD_TICK_PULSES, state.respawnAt)
  }

  @Test
  fun `stacks roll size, taking part keeps the rest counting, taking all or destroying all releases`() {
    val w =
      unstartedWorld(
        listOf(
          SpawnRule(
            arrow,
            max = 1,
            until = Until.TAKEN,
            stack = Roll(3, 5),
            respawn = Respawn.Never,
          ),
          SpawnRule(arrow, max = 1, until = Until.GONE, stack = Roll(3, 5), respawn = Respawn.Never),
        )
      )
    w.rng.force(listOf(4, 5))
    w.start()

    val takenState = w.spawnStates[0]
    val goneState = w.spawnStates[1]
    val takenCopy = w.item(takenState.live.single())!!
    val goneCopy = w.item(goneState.live.single())!!
    assertEquals(4, takenCopy.count)
    assertEquals(5, goneCopy.count)

    // Taking 1 off the taken stack leaves the rest counting; split part is untracked.
    val p = w.spawnPlayer("Ann", hall)
    val splitPart = w.moveItem(takenCopy, ItemLocation.Carried(p.id), count = 1)
    assertNull(splitPart.spawnedBy)
    assertEquals(3, takenCopy.count)
    w.placeActor(p, w.room(yard), 0)
    w.runPulses(WORLD_TICK_PULSES)
    assertEquals(1, takenState.tracked)

    // Taking the rest out of the room releases it at the next world tick.
    w.placeActor(p, w.room(hall), 0)
    w.moveItem(takenCopy, ItemLocation.Carried(p.id))
    w.placeActor(p, w.room(yard), 0)
    w.runPulses(WORLD_TICK_PULSES)
    assertEquals(0, takenState.tracked)

    // Partial destroy of the gone stack leaves it counting; destroying the rest releases it.
    w.destroy(goneCopy, count = 2)
    assertEquals(3, goneCopy.count)
    assertEquals(1, goneState.tracked)
    w.destroy(goneCopy, count = 3)
    assertEquals(0, goneState.tracked)
  }

  @Test
  fun `container rule places into lowest-id chest even if locked`() {
    val w =
      unstartedWorld(listOf(SpawnRule(gem, max = 1, container = chest, respawn = Respawn.Never)))
    val hallRoom = w.room(hall)
    val firstChest = w.spawnItem(chest, ItemLocation.InRoom(hallRoom.id, 0))
    val secondChest = w.spawnItem(chest, ItemLocation.InRoom(hallRoom.id, 0))
    assertEquals(DoorState.LOCKED, firstChest.container!!.state)

    w.start()
    val state = w.spawnStates.single()
    assertEquals(1, state.live.size)
    assertTrue(state.held.isEmpty())
    val copy = w.item(state.live.single())!!
    assertEquals(ItemLocation.Contained(firstChest.id), copy.location)
    assertTrue(secondChest.contents.isEmpty())
  }

  @Test
  fun `missing or full chest holds copy and retries at world tick when awake, not while dormant`() {
    val f =
      Fixture(
        protos =
          protos(listOf(SpawnRule(gem, max = 2, container = smallBox, respawn = Respawn.Never))),
        bubble = true,
      )
    val state = f.w.spawnStates.single()
    val hallRoom = f.w.room(hall)

    // No box at start -> both copies held (never left on floor).
    assertEquals(0, state.live.size)
    assertEquals(2, state.held.size)
    assertTrue(hallRoom.items.isEmpty())

    // Spawn a box (capacity 1, maxWeight 2) while hall is still dormant: world tick must NOT retry.
    val box = f.w.spawnItem(smallBox, ItemLocation.InRoom(hallRoom.id, 0))
    assertTrue(hallRoom.isDormant)
    f.run(WORLD_TICK_PULSES)
    assertEquals(0, state.live.size)
    assertEquals(2, state.held.size)

    // Player enters hall -> room wakes and places 1 copy into box; 2nd stays held because box is
    // full.
    val p = f.player("Ann", hall)
    f.run(1)
    assertEquals(1, state.live.size)
    assertEquals(1, state.held.size)
    assertEquals(1, box.contents.size)

    // Next world tick while box is still full -> 2nd stays held.
    f.run(WORLD_TICK_PULSES)
    assertEquals(1, state.live.size)
    assertEquals(1, state.held.size)

    // Take the first gem out of the box -> at next world tick the 2nd held copy is placed into box.
    val firstCopy = box.contents.single()
    f.w.moveItem(firstCopy, ItemLocation.Carried(p.id))
    f.run(WORLD_TICK_PULSES)
    assertEquals(2, state.live.size)
    assertTrue(state.held.isEmpty())
    assertEquals(1, box.contents.size)
  }

  @Test
  fun `logout moves gone copy to away so it still counts and does not refill`() {
    val f =
      Fixture(
        protos =
          protos(
            listOf(SpawnRule(gem, max = 1, respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES))))
          )
      )
    val state = f.w.spawnStates.single()
    val copy = itemsOf(f, gem).single()
    val p = f.player("Ann", hall)
    f.w.moveItem(copy, ItemLocation.Carried(p.id))

    f.w.quit(p)
    assertEquals(0, state.live.size)
    assertEquals(setOf(copy.id), state.away)
    assertEquals(1, state.tracked)
    assertNull(state.respawnAt)

    f.run(WORLD_TICK_PULSES * 2)
    assertEquals(0, state.live.size)
    assertEquals(1, state.away.size)
    assertTrue(f.w.room(hall).items.isEmpty())
  }

  @Test
  fun `mob death releases a carried copy`() {
    val f =
      Fixture(
        protos =
          protos(
            listOf(SpawnRule(gem, max = 1, respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES))))
          )
      )
    val state = f.w.spawnStates.single()
    val copy = itemsOf(f, gem).single()
    val mob = f.w.spawnMob(goblin, f.w.room(hall))
    f.w.moveItem(copy, ItemLocation.Carried(mob.id))

    // Non-stowed mob removal (e.g. despawn or death without moving item to corpse) releases it.
    f.w.despawn(mob)
    assertEquals(0, state.live.size)
    assertTrue(state.away.isEmpty())
    assertEquals(0, state.tracked)
    assertNotNull(state.respawnAt)
  }
}
