package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Respawn
import gups.core.model.Roll
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class DespawnTest(private val fs: FixtureSchema) {
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

  @Test
  fun `despawning a spawn-rule mob leaves no corpse and lets the rule respawn it`() {
    val den = Label("//t:den")
    val protos =
      T.protos(
        extraRooms =
          listOf(
            RoomProto(
              den,
              "A Den",
              spawns = listOf(SpawnRule(T.GOBLIN, max = 1, respawn = Respawn.Timed(Roll.of(100)))),
            )
          )
      )
    val f = Fixture(protos = protos)
    val g = f.w.room(den).actors.filterIsInstance<Mob>().single()
    f.w.despawn(g)
    assertFalse(g.inWorld)
    assertTrue(f.w.room(den).items.isEmpty())

    f.runTo(120)
    val fresh = f.w.room(den).actors.filter { it.name == "a goblin" }
    assertEquals(1, fresh.size)
  }

  @Test
  fun `despawning a mob destroys what it carries and wears`() {
    val f = Fixture()
    val g = f.mob(T.GOBLIN)
    val carried = f.w.spawnItem(T.KEY, ItemLocation.Carried(g.id))
    val worn = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(g.id, "wield"))

    f.w.despawn(g)
    assertFalse(g.inWorld)
    assertNull(f.w.item(carried.id))
    assertNull(f.w.item(worn.id))
    assertTrue(f.w.room(T.HALL).items.isEmpty())
  }

  @Test
  fun `despawning a mob fighting a player ends the player's engagement and awards no XP`() {
    val f = Fixture(rules = FixedRules(damage = 1))
    val p = f.player("Ann")
    val g = f.mob(T.GOBLIN)
    f.w.startCombat(p, g)
    f.run()
    assertTrue(p.isEngaged)

    f.w.despawn(g)
    assertTrue(p.fighting.isEmpty())
    assertFalse(p.isEngaged)
    assertEquals(0L, p.xp)
  }

  @Test
  fun `despawning an owned pet removes it and clears petsOf`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val pet = f.mob(T.GOBLIN)
    f.w.setOwner(pet, ann)
    assertEquals(listOf(pet), f.w.petsOf(ann))

    f.w.despawn(pet)
    assertFalse(pet.inWorld)
    assertTrue(f.w.petsOf(ann).isEmpty())
  }

  @Test
  fun `opponents hear on_disengage even when an on_despawn listener takes the mob out first`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    val pet = f.mob(T.GOBLIN)
    // Engaged before it has an owner, so the owner isn't drawn into the fight.
    f.w.startCombat(bob, pet)
    f.w.setOwner(pet, ann)
    assertTrue(bob.isEngaged)
    assertFalse(ann.isEngaged)
    val disengaged = mutableListOf<Pair<String, String?>>()
    var stowedEarly = false
    f.w.pluginEvents.getOrPut(HookName.ON_DESPAWN) { mutableListOf() } += EventListener { w, _, _ ->
      // The owner logging out stows the pet before despawn gets to remove it.
      w.quit(ann)
      stowedEarly = !pet.inWorld
    }
    f.w.pluginEvents.getOrPut(HookName.ON_DISENGAGE) { mutableListOf() } +=
      EventListener { _, subject, ctx ->
        disengaged += subject.name to ctx.event
      }

    f.w.despawn(pet)
    assertTrue(stowedEarly, "the listener must take the pet out before despawn removes it")
    assertFalse(pet.inWorld)
    assertFalse(bob.isEngaged)
    assertEquals(listOf<Pair<String, String?>>("Bob" to "stop"), disengaged)
  }

  @Test
  fun `despawning a mob not in the world throws IllegalArgumentException`() {
    val f = Fixture()
    val g = f.mob(T.GOBLIN)
    f.w.despawn(g)
    val err = assertFailsWith<IllegalArgumentException> { f.w.despawn(g) }
    assertContains(err.message.orEmpty(), "despawn: ${g.name} is not in the world")
  }
}
