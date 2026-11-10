package gups.core.world

import gups.core.Label
import gups.core.model.Effect
import gups.core.model.Mob
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnersTest {
  private class Seen(val actor: String, val target: String?, val event: String?)

  private fun recording(
    onDisengage: MutableList<String> = mutableListOf(),
    events: MutableList<Seen> = mutableListOf(),
    onSpawn: (Mob) -> Unit = {},
  ): Pair<Fixture, RecordingHost> {
    val host = RecordingHost()
    host.handlers[HookName.ON_OWNER_CHANGE] = { _, _, ctx ->
      events += Seen(ctx.actor!!.name, ctx.target?.name, ctx.event)
      null
    }
    host.handlers[HookName.ON_DISENGAGE] = { _, e, ctx ->
      onDisengage += "${e.name}:${ctx.event}"
      null
    }
    host.handlers[HookName.ON_SPAWN] = { _, e, _ ->
      (e as? Mob)?.let(onSpawn)
      null
    }
    return Fixture(scripts = host) to host
  }

  @Test
  fun `set replaces and clear removes, with reasons and events`() {
    val events = mutableListOf<Seen>()
    val (f, _) = recording(events = events)
    val ann = f.player("Ann")
    val sam = f.player("Sam")
    val wolf = f.mob(T.GOBLIN)

    assertTrue(f.w.setOwner(wolf, ann))
    assertEquals(ann.id, wolf.owner)
    assertTrue(ownerReason(ann.id) in wolf.activeReasons)
    assertFalse(f.w.setOwner(wolf, ann), "same owner changes nothing")
    assertEquals(1, events.size)

    assertTrue(f.w.setOwner(wolf, sam))
    assertEquals(sam.id, wolf.owner)
    assertFalse(ownerReason(ann.id) in wolf.activeReasons)
    assertTrue(ownerReason(sam.id) in wolf.activeReasons)
    assertEquals(listOf("set", "clear", "set"), events.map { it.event })
    assertEquals("Ann", events[1].target)
    assertEquals("Sam", events[2].target)

    assertTrue(f.w.clearOwner(wolf))
    assertNull(wolf.owner)
    assertFalse(ownerReason(sam.id) in wolf.activeReasons)
    assertEquals("clear", events.last().event)
    assertFalse(f.w.clearOwner(wolf), "clearing an unowned mob changes nothing")
    assertEquals(4, events.size)
  }

  @Test
  fun `an owned mob leaves its spawn rule so the zone respawns another`() {
    val den = Label("//t:den")
    val protos =
      T.protos(
        extraRooms =
          listOf(
            RoomProto(
              den,
              "A Den",
              spawns = listOf(SpawnRule(T.GOBLIN, count = 1, respawnPulses = 100)),
            )
          )
      )
    val f = Fixture(protos = protos)
    val ann = f.player("Ann", at = den)
    val m = f.w.room(den).actors.filterIsInstance<Mob>().single()
    f.w.setOwner(m, ann)
    assertNull(m.spawnedBy)
    f.runTo(120)
    assertEquals(2, f.w.room(den).actors.filterIsInstance<Mob>().size, "a replacement spawned")
  }

  @Test
  fun `petsOf lists in-world pets by id`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val a = f.mob(T.GOBLIN)
    val b = f.mob(T.DUMMY, T.YARD)
    val other = f.mob(T.GOBLIN)
    f.w.setOwner(b, ann)
    f.w.setOwner(a, ann)
    assertEquals(listOf(a, b).sortedBy { it.id }, f.w.petsOf(ann))
    assertFalse(other in f.w.petsOf(ann))
  }

  @Test
  fun `isOwnerSide covers the owner, its group, and pets of either`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val bo = f.player("Bo")
    val cy = f.player("Cy")
    f.w.groupAdd(bo, ann)
    val pet = f.mob(T.GOBLIN)
    val matePet = f.mob(T.GOBLIN)
    val strayPet = f.mob(T.GOBLIN)
    f.w.setOwner(pet, ann)
    f.w.setOwner(matePet, bo)
    f.w.setOwner(strayPet, cy)
    assertTrue(f.w.isOwnerSide(pet, ann))
    assertTrue(f.w.isOwnerSide(pet, bo))
    assertTrue(f.w.isOwnerSide(pet, matePet))
    assertFalse(f.w.isOwnerSide(pet, cy))
    assertFalse(f.w.isOwnerSide(pet, strayPet))
    assertFalse(f.w.isOwnerSide(f.mob(T.GOBLIN), ann), "an unowned mob has no side")
  }

  @Test
  fun `ownerOf ignores leader`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val m = f.mob(T.GOBLIN)
    m.leader = ann.id
    assertNull(f.w.ownerOf(m))
    f.w.setOwner(m, ann)
    assertEquals(ann, f.w.ownerOf(m))
  }

  @Test
  fun `gaining an owner ends fights with its side`() {
    val disengaged = mutableListOf<String>()
    val (f, _) = recording(onDisengage = disengaged)
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.startCombat(wolf, ann)
    assertTrue(ann.id in wolf.fighting)
    f.w.setOwner(wolf, ann)
    assertFalse(ann.id in wolf.fighting)
    assertFalse(wolf.id in ann.fighting)
    assertTrue("${wolf.name}:stop" in disengaged)
  }

  @Test
  fun `a pet can turn on its owner afterwards`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)
    f.w.startCombat(wolf, ann)
    assertTrue(ann.id in wolf.fighting)
  }

  @Test
  fun `a summoning player owns the summons and on_spawn sees both fields`() {
    var sawSummoner: Any? = "unset"
    var sawOwner: Any? = "unset"
    val (f, _) =
      recording(
        onSpawn = {
          if (it.proto.label == T.DUMMY) {
            sawSummoner = it.summonedBy
            sawOwner = it.owner
          }
        }
      )
    val ann = f.player("Ann")
    val pets = f.w.summon(ann, Effect.Summon(T.DUMMY, 2))
    assertEquals(ann.id, sawSummoner)
    assertEquals(ann.id, sawOwner)
    assertTrue(pets.all { it.owner == ann.id && ownerReason(ann.id) in it.activeReasons })
    val boss = f.mob(T.GOBLIN)
    val minions = f.w.summon(boss, Effect.Summon(T.DUMMY))
    assertTrue(minions.all { it.owner == null && it.summonedBy == boss.id })
  }

  @Test
  fun `summon fires on_owner_change for each owned mob in id order`() {
    val events = mutableListOf<Seen>()
    val (f, _) = recording(events = events)
    val ann = f.player("Ann")
    f.w.summon(ann, Effect.Summon(T.DUMMY, 2))
    assertEquals(listOf("set", "set"), events.map { it.event })
    assertEquals("Ann", events.first().target)
  }

  @Test
  fun `quit drops the owner reason but keeps the owner`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)
    f.w.quit(ann)
    assertEquals(ann.id, wolf.owner)
    assertFalse(ownerReason(ann.id) in wolf.activeReasons)
  }

  @Test
  fun `quit fires on_disengage for the pet's opponents and pet leaves room with message`() {
    val disengaged = mutableListOf<String>()
    val (f, _) = recording(onDisengage = disengaged)
    val ann = f.player("Ann")
    val sam = f.player("Sam")
    val wolf = f.mob(T.GOBLIN)
    val dummy = f.mob(T.DUMMY)
    f.w.setOwner(wolf, ann)
    f.w.startCombat(wolf, dummy)
    assertTrue(dummy.id in wolf.fighting)

    f.w.quit(ann)
    f.w.flushOutput()

    assertFalse(wolf.inWorld)
    assertFalse(wolf in f.w.room(T.HALL).actors)
    assertContains(f.sink(sam).transcript, "A goblin leaves with Ann.")
    assertTrue("${dummy.name}:stop" in disengaged)
  }

  @Test
  fun `restoreOwner drops a saved owner reason`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    wolf.activeReasons.add(ownerReason(ann.id))
    f.w.restoreOwner(wolf, ann.id)
    assertEquals(ann.id, wolf.owner)
    assertFalse(ownerReason(ann.id) in wolf.activeReasons)
  }
}
