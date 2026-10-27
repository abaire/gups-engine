package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class CombatTest(private val fs: FixtureSchema) {
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
  fun `kill engages, and auto-attacks swing once per round independent of lag`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.input(p, "kill dummy")
    f.run()
    assertTrue(p.isEngaged && g.isEngaged)
    p.waitState = 20
    f.runTo(16)
    assertEquals(1000 - 6, g.current(fs.health))
    assertEquals(50 - 6, p.current(fs.health))
    assertContains(f.out(p), "You hit a training dummy.")
  }

  @Test
  fun `out-of-reach targets are not swung at, but a ranged weapon reaches far`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY, index = 4)
    f.w.startCombat(p, g)
    f.runTo(8)
    assertContains(f.out(p), "A training dummy is out of reach.")
    assertEquals(1000, g.current(fs.health))
    f.w.moveItem(
      f.w.spawnItem(T.BOW, ItemLocation.Carried(p.id)),
      ItemLocation.Equipped(p.id, "wield"),
    )
    f.runTo(16)
    assertEquals(1000 - 3, g.current(fs.health))
  }

  @Test
  fun `before_attack fires on the defender, then the attacker, then each one's items`() {
    val host = RecordingHost()
    val seen = mutableListOf<String>()
    val f = Fixture(rules = FixedRules(damage = 3), scripts = host)
    val p = f.player("Ann")
    host.handlers[HookName.BEFORE_ATTACK] = { _, owner, ctx ->
      if (ctx.actor === p) seen += owner.name
      null
    }
    val g = f.mob(T.DUMMY)
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    val bag = f.w.spawnItem(T.BAG, ItemLocation.Carried(g.id))
    f.w.startCombat(p, g)
    f.runTo(8)
    assertEquals(listOf(g.name, p.name, "The Hall", bag.name, sword.name), seen)
  }

  @Test
  fun `the attacker's wielded weapon can veto an attack`() {
    val host = RecordingHost()
    val f = Fixture(rules = FixedRules(damage = 3), scripts = host)
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    host.handlers[HookName.BEFORE_ATTACK] = { _, owner, _ ->
      if (owner === sword) HookOutcome.Deny("Your sword refuses to strike.") else null
    }
    f.w.startCombat(p, g)
    f.runTo(8)
    assertContains(f.out(p), "Your sword refuses to strike.")
    assertEquals(1000, g.current(fs.health))
  }

  @Test
  fun `a casting actor does not auto-attack`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob(T.DUMMY)
    f.w.startCombat(p, g)
    f.runTo(5)
    f.input(p, "cast bolt dummy") // warms up through pulse 8
    f.runTo(8)
    assertTrue(p.isCasting)
    assertEquals(1000, g.current(fs.health))
  }

  @Test
  fun `a mob death leaves a corpse with inventory and loot, awards xp, and respawns`() {
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
    val f = Fixture(rules = FixedRules(damage = 20), protos = protos)
    val g = f.w.room(den).actors.single()
    f.w.spawnItem(T.SWORD, ItemLocation.Carried(g.id))
    val p = f.player("Ann", at = den)
    f.w.startCombat(p, g)
    f.runTo(8)
    assertFalse(g.inWorld)
    val corpse = f.w.room(den).items.single()
    assertEquals("the corpse of a goblin", corpse.name)
    assertEquals(listOf("a brass key", "a sword"), corpse.contents.map { it.name }.sorted())
    assertEquals(100L, p.xp)
    assertNotNull(corpse.decayAt)

    f.runTo(120) // first world tick after the respawn deadline
    val fresh = f.w.room(den).actors.filter { it.name == "a goblin" }
    assertEquals(1, fresh.size)

    f.input(p, "get key from corpse")
    f.run()
    assertEquals(listOf("a brass key"), p.inventory.map { it.name })
  }

  @Test
  fun `corpses decay and drop what is left`() {
    val f = Fixture(rules = FixedRules(damage = 20))
    val p = f.player("Ann")
    val g = f.mob()
    f.w.startCombat(p, g)
    f.runTo(8)
    val corpse = f.w.room(T.HALL).items.single()
    f.runTo(corpse.decayAt!! + 120)
    assertEquals(listOf("a brass key"), f.w.room(T.HALL).items.map { it.name })
  }

  @Test
  fun `a player death respawns at recall and leaves carried items in a corpse`() {
    val f = Fixture(rules = FixedRules(damage = 60, initiative = mapOf("a goblin" to 20)))
    val p = f.player("Ann")
    p.recallRoom = T.YARD
    p.xp = 500
    f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))
    val g = f.mob()
    f.w.startCombat(g, p)
    f.runTo(8)
    assertTrue(p.inWorld)
    assertEquals(T.YARD, p.room.label)
    assertEquals(1, p.current(fs.health))
    assertEquals(450L, p.xp)
    assertTrue(p.inventory.isEmpty())
    assertEquals("the corpse of Ann", f.w.room(T.HALL).items.single().name)
    assertFalse(g.isEngaged)
  }

  @Test
  fun `level ups follow xp_to_level`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.awardXp(p, 2500)
    assertEquals(3, p.level)
  }

  @Test
  fun `on_expire fires with the reason the affect ended`() {
    val host = RecordingHost()
    val reasons = mutableListOf<String>()
    host.refHandlers["done"] = { _, _, ctx ->
      reasons += "${ctx.affect!!.name} ${ctx.affectEndReason}"
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    val done = HookRef(Label("//t:t.star"), "done")
    f.w.applyAffect(p, "ward", 5, onExpire = done)
    f.w.applyAffect(p, "hex", 50, onExpire = done)
    f.w.applyAffect(p, "glow", 50, onExpire = done)
    f.w.removeAffect(p, "hex")
    f.w.applyEffect(Effect.Dispel(name = "glow"), p, p)
    f.runTo(10)
    assertEquals(listOf("hex REMOVED", "glow REMOVED", "ward EXPIRED"), reasons)
  }

  @Test
  fun `an affect removed on its expiry pulse counts as expired`() {
    val host = RecordingHost()
    val reasons = mutableListOf<String>()
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    val done = HookRef(Label("//t:t.star"), "done")
    // Both run out on the same pulse; the first one's on_expire removes the second.
    host.refHandlers["done"] = { w, _, ctx ->
      reasons += "${ctx.affect!!.name} ${ctx.affectEndReason}"
      if (ctx.affect.name == "first") w.removeAffect(p, "second")
    }
    f.w.applyAffect(p, "first", 5, onExpire = done)
    f.w.applyAffect(p, "second", 5, onExpire = done)
    f.runTo(10)
    assertEquals(listOf("first EXPIRED", "second EXPIRED"), reasons)
  }

  @Test
  fun `dispel matches by flag, never removes intrinsic affects, and stops at its count`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.applyAffect(p, "hex", 50, flags = setOf("curse"))
    f.w.applyAffect(p, "doom", 50, flags = setOf("curse"))
    f.w.applyAffect(p, "venom", 50, flags = setOf("poison"))
    f.w.applyAffect(p, "blessing", 50)
    f.w.applyAffect(p, "birthmark", 50, flags = setOf("curse", "intrinsic"))
    f.w.applyEffect(Effect.Dispel(flags = setOf("curse"), count = 1), p, p)
    assertEquals(listOf("doom", "venom", "blessing", "birthmark"), p.affects.map { it.name })
    f.w.applyEffect(Effect.Dispel(all = true), p, p)
    assertEquals(listOf("birthmark"), p.affects.map { it.name })
  }

  @Test
  fun `a dispelled affect sends its expire message`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.applyAffect(p, "haste", 50, expireMessage = "You slow down.")
    f.out(p)
    f.w.applyEffect(Effect.Dispel(name = "haste"), p, p)
    f.run()
    assertContains(f.out(p), "You slow down.")
  }

  @Test
  fun `a timed summon fades away when it expires or is dispelled`() {
    val f = Fixture()
    val p = f.player("Ann", at = T.YARD)
    f.w.applyEffect(Effect.Summon(T.GOBLIN, count = 2, duration = 50), p, null)
    val (a, b) = f.w.room(T.YARD).actors.filterIsInstance<Mob>()
    f.w.startCombat(a, p)
    f.w.applyEffect(Effect.Dispel(flags = setOf("summoned")), p, a)
    assertFalse(a.inWorld, "dispelling banishes it")
    assertTrue(p.fighting.isEmpty())
    assertTrue(b.inWorld)
    f.runTo(60)
    assertFalse(b.inWorld, "expiry banishes it")
    assertTrue(f.w.room(T.YARD).items.isEmpty(), "a banished summon leaves no corpse")
  }

  @Test
  fun `summons record their summoner, and killing one gives no xp or loot`() {
    val f = Fixture(rules = FixedRules(damage = 20))
    val p = f.player("Ann", at = T.YARD)
    f.w.applyEffect(Effect.Summon(T.GOBLIN), p, null)
    val g = f.w.room(T.YARD).actors.filterIsInstance<Mob>().single()
    assertEquals(p.id, g.summonedBy)
    assertNull(f.mob().summonedBy)
    f.w.startCombat(p, g)
    f.runTo(8)
    assertFalse(g.inWorld)
    assertEquals(0L, p.xp)
    assertTrue(f.w.room(T.YARD).items.single().contents.isEmpty(), "no loot rolled")
  }

  @Test
  fun `on_combat_tick fires on the fighter, then its equipped items in id order`() {
    val host = RecordingHost()
    val seen = mutableListOf<String>()
    val f = Fixture(rules = FixedRules(toHit = 0), scripts = host)
    val p = f.player("Ann")
    val bow = f.w.spawnItem(T.BOW, ItemLocation.Carried(p.id))
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    f.w.moveItem(bow, ItemLocation.Equipped(p.id, "hold"))
    // Carried, not equipped: stays out of the round.
    f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))
    host.handlers[HookName.ON_COMBAT_TICK] = { _, owner, ctx ->
      if (ctx.actor === p) seen += owner.name
      null
    }
    f.w.startCombat(p, f.mob(T.DUMMY))
    f.runTo(8)
    assertEquals(listOf(p.name) + listOf(bow, sword).sortedBy { it.id }.map { it.name }, seen)
  }
}
