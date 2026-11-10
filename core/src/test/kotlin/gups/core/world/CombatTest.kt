package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.LootRule
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.ZoneProto
import gups.core.rules.LevelUpResult
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
  fun `damage dealt to a victim while its death is processed doesn't run the death again`() {
    val host = RecordingHost()
    var deaths = 0
    host.handlers[HookName.ON_DEATH] = { w, owner, ctx ->
      if (owner === ctx.target) {
        deaths++
        w.damage(ctx.target, mapOf(fs.health to 1), DamageType("fire"))
      }
      null
    }
    val f = Fixture(scripts = host)
    val g = f.mob()
    f.w.damage(g, mapOf(fs.health to g.current(fs.health) + 1), DamageType("fire"))
    assertFalse(g.inWorld)
    assertEquals(1, deaths)
    assertEquals(1, f.w.room(T.HALL).items.count { it.name == "the corpse of a goblin" })
  }

  @Test
  fun `a player killed again right after respawning dies again`() {
    val f = Fixture()
    val p = f.player("Ann")
    repeat(2) {
      f.w.damage(p, mapOf(fs.health to p.current(fs.health) + 1), DamageType("fire"))
      assertEquals(Condition.OK, p.condition)
      assertTrue(p.current(fs.health) > 0)
    }
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
    assertEquals(120L, p.xp)
    assertNotNull(corpse.decayAt)

    f.runTo(120) // first world tick after the respawn deadline
    val fresh = f.w.room(den).actors.filter { it.name == "a goblin" }
    assertEquals(1, fresh.size)

    f.input(p, "get key from corpse")
    f.run()
    assertEquals(listOf("a brass key"), p.inventory.map { it.name })
  }

  @Test
  fun `loot chance is a percent, drawn once, 100 and 0 draw nothing`() {
    val rat =
      MobProto(
        Label("//t:rat"),
        "a rat",
        listOf("rat"),
        vitals = mapOf(fs.health to 10),
        loot = listOf(LootRule(T.KEY, chance = 50)),
      )
    val protos = T.protos(extraMobs = listOf(rat))

    // A forced int of 50 lands the roll (50 <= 50); a forced int of 51 misses.
    val hit = Fixture(rules = FixedRules(damage = 20), protos = protos)
    val ratIn = hit.mob(rat.label)
    hit.w.rng.force(ints = listOf(50))
    hit.hurt(ratIn, 999)
    assertFalse(ratIn.inWorld)
    val corpse = hit.w.room(T.HALL).items.single { it.name == "the corpse of a rat" }
    assertEquals(listOf("a brass key"), corpse.contents.map { it.name })

    val miss = Fixture(rules = FixedRules(damage = 20), protos = protos)
    val ratOut = miss.mob(rat.label)
    miss.w.rng.force(ints = listOf(51))
    miss.hurt(ratOut, 999)
    val corpse2 = miss.w.room(T.HALL).items.single { it.name == "the corpse of a rat" }
    assertTrue(corpse2.contents.isEmpty(), "51 misses a 50 percent roll")
    assertEquals(0, miss.w.rng.forcedRemaining)
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
  fun `level_loss lowers the level after the respawn, in the respawn room, and the respawn vitals are capped at the lowered maxima`() {
    val rules = FixedRules(fs)
    rules.levelUpOf = {
      LevelUpResult(max = mapOf(fs.health to 20), current = mapOf(fs.health to 20))
    }
    val f = Fixture(rules = rules)
    val p = f.player("Ann", hp = 50)
    p.recallRoom = T.YARD
    p.xp = 1000L
    assertTrue(f.w.levelUp(p))
    assertEquals(2, p.level)
    assertEquals(70, p.baseMax(fs.health))
    assertEquals(70, p.current(fs.health))

    rules.deathOf = {
      PlayerDeathOutcome(
        respawn = p.recallRoom,
        vitals = mapOf(fs.health to 70),
        xpLoss = 100L,
        levelLoss = 1,
      )
    }

    f.w.damage(p, mapOf(fs.health to 100), DamageType(fs.unarmed))
    f.run()

    assertEquals(1, p.level)
    assertEquals(T.YARD, p.room.label)
    assertEquals(50, p.baseMax(fs.health))
    assertEquals(50, p.current(fs.health))
    val out = f.out(p)
    assertContains(out, "You have fallen to level 1.")
    val respawnIndex = out.indexOf("The Yard")
    val levelDownIndex = out.indexOf("You have fallen to level 1.")
    assertNotEquals(respawnIndex, -1)
    assertNotEquals(levelDownIndex, -1)
    assertTrue(respawnIndex < levelDownIndex)
  }

  @Test
  fun `xp_loss larger than xp leaves 0`() {
    val rules = FixedRules(fs)
    rules.deathOf = {
      PlayerDeathOutcome(
        respawn = (it.requireActor() as Player).recallRoom,
        vitals = mapOf(fs.health to 1),
        xpLoss = 1000L,
      )
    }
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    p.xp = 350L

    f.w.damage(p, mapOf(fs.health to 100), DamageType(fs.unarmed))
    f.run()

    assertEquals(0L, p.xp)
  }

  @Test
  fun `level ups follow xp_to_level`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.awardXp(p, 3000)
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
    f.w.applyAffect(p, AffectSpec("ward", 5, onExpire = done))
    f.w.applyAffect(p, AffectSpec("hex", 50, onExpire = done))
    f.w.applyAffect(p, AffectSpec("glow", 50, onExpire = done))
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
    f.w.applyAffect(p, AffectSpec("first", 5, onExpire = done))
    f.w.applyAffect(p, AffectSpec("second", 5, onExpire = done))
    f.runTo(10)
    assertEquals(listOf("first EXPIRED", "second EXPIRED"), reasons)
  }

  @Test
  fun `dispel matches by flag, never removes intrinsic affects, and stops at its count`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.applyAffect(p, AffectSpec("hex", 50, flags = setOf("curse")))
    f.w.applyAffect(p, AffectSpec("doom", 50, flags = setOf("curse")))
    f.w.applyAffect(p, AffectSpec("venom", 50, flags = setOf("poison")))
    f.w.applyAffect(p, AffectSpec("blessing", 50))
    f.w.applyAffect(p, AffectSpec("birthmark", 50, flags = setOf("curse", "intrinsic")))
    f.w.applyEffect(Effect.Dispel(flags = setOf("curse"), count = 1), p, p)
    assertEquals(listOf("doom", "venom", "blessing", "birthmark"), p.affects.map { it.name })
    f.w.applyEffect(Effect.Dispel(all = true), p, p)
    assertEquals(listOf("birthmark"), p.affects.map { it.name })
  }

  @Test
  fun `a dispelled affect sends its expire message`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.applyAffect(p, AffectSpec("haste", 50, expireMessage = "You slow down."))
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

  @Test
  fun `spell damage goes through damage modifier chain with room and caster affect modifiers and traces`() {
    val host = RecordingHost()
    host.modifiers["room_boost"] = { _, _, base -> (base as Int) + 5 }
    host.modifiers["affect_boost"] = { _, _, base -> (base as Int) + 3 }
    val f =
      Fixture(
        fs = fs,
        scripts = host,
        protos =
          Prototypes(
            listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.DAMAGE to mod("room_boost")))) +
              T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
            zones = listOf(ZoneProto("t")),
          ),
      )
    val lines = mutableListOf<String>()
    f.w.onRules = { _, line -> lines += line }
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.applyAffect(
      p,
      AffectSpec(
        "empower",
        duration = 100,
        modifiers = mapOf(ModifierPoint.DAMAGE to mod("affect_boost")),
      ),
    )
    f.w.applyEffect(
      Effect.Damage(Dice(1, 1, 9), DamageType(fs.magic)),
      p,
      d,
      EffectContext(skill = T.skills[0]),
    )
    assertEquals(1000 - 18, d.current(fs.health))
    assertTrue(
      lines.any { "rules.damage base=10 → room:hall 15 → affect:empower 18" in it },
      "Expected trace line in: $lines",
    )
  }

  @Test
  fun `a sword in wield and a dagger in offhand give two swings per round, with ctx slot wield then offhand`() {
    val DAGGER = Label("//t:dagger")
    val daggerProto =
      ItemProto(
        DAGGER,
        "a dagger",
        listOf("dagger"),
        kind = "melee",
        wear = "offhand",
        dice = Dice(1, 1, 3),
      )
    val protos = Prototypes(T.rooms, T.mobs(fs), T.items + daggerProto, T.skills(fs))
    val slotsSeen = mutableListOf<String?>()
    val weaponsSeen = mutableListOf<Item?>()
    val rules =
      object : FixedRules(damage = 3) {
        override fun toHit(c: RulesContext): Int {
          if (c.actor?.name == "Ann") {
            slotsSeen += c.slot
            weaponsSeen += c.weapon
          }
          return super.toHit(c)
        }
      }
    val f = Fixture(rules = rules, protos = protos)
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    val dagger = f.w.spawnItem(DAGGER, ItemLocation.Equipped(p.id, "offhand"))
    f.w.startCombat(p, g)
    f.runTo(8)
    assertEquals(listOf<String?>("wield", "offhand"), slotsSeen)
    assertEquals(listOf<Item?>(sword, dagger), weaponsSeen)
    assertEquals(1000 - 6, g.current(fs.health))
  }

  @Test
  fun `a shield in offhand without dice gives one swing`() {
    val SHIELD = Label("//t:shield")
    val shieldProto =
      ItemProto(SHIELD, "a wooden shield", listOf("shield"), kind = "armor", wear = "offhand")
    val protos = Prototypes(T.rooms, T.mobs(fs), T.items + shieldProto, T.skills(fs))
    val slotsSeen = mutableListOf<String?>()
    val rules =
      object : FixedRules(damage = 3) {
        override fun toHit(c: RulesContext): Int {
          if (c.actor?.name == "Ann") slotsSeen += c.slot
          return super.toHit(c)
        }
      }
    val f = Fixture(rules = rules, protos = protos)
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(SHIELD, ItemLocation.Equipped(p.id, "offhand"))
    f.w.startCombat(p, g)
    f.runTo(8)
    assertEquals(listOf<String?>("wield"), slotsSeen)
    assertEquals(1000 - 3, g.current(fs.health))
  }

  @Test
  fun `an empty wield gives one unarmed swing with null weapon, and amulet with dice at neck adds swing`() {
    val AMULET = Label("//t:amulet")
    val amuletProto =
      ItemProto(AMULET, "an amulet", listOf("amulet"), wear = "neck", dice = Dice(1, 1, 2))
    val protos = Prototypes(T.rooms, T.mobs(fs), T.items + amuletProto, T.skills(fs))
    val swingsSeen = mutableListOf<Pair<Item?, String?>>()
    val rules =
      object : FixedRules(damage = 3) {
        override fun toHit(c: RulesContext): Int {
          if (c.actor?.name == "Ann") swingsSeen += c.weapon to c.slot
          return super.toHit(c)
        }
      }
    val f = Fixture(rules = rules, protos = protos)
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    val amulet = f.w.spawnItem(AMULET, ItemLocation.Equipped(p.id, "neck"))
    f.w.startCombat(p, g)
    f.runTo(8)
    assertEquals(listOf<Pair<Item?, String?>>(amulet to "neck", null to "wield"), swingsSeen)
    assertEquals(1000 - 6, g.current(fs.health))
  }

  @Test
  fun `an inactive weapon does not swing and suppresses the unarmed swing`() {
    val DAGGER = Label("//t:dagger")
    val daggerProto =
      ItemProto(
        DAGGER,
        "a dagger",
        listOf("dagger"),
        kind = "melee",
        wear = "offhand",
        dice = Dice(1, 1, 3),
      )
    val protos = Prototypes(T.rooms, T.mobs(fs), T.items + daggerProto, T.skills(fs))
    val slotsSeen = mutableListOf<String?>()
    val rules =
      object : FixedRules(damage = 3) {
        override fun toHit(c: RulesContext): Int {
          if (c.actor?.name == "Ann") slotsSeen += c.slot
          return super.toHit(c)
        }

        override fun slotFamilies(): Map<String, List<String>> =
          super.slotFamilies() + ("hand" to listOf("wield", "offhand"))

        // The sword costs more than the hand family holds, so the fit check marks it inactive.
        override fun slotCost(c: RulesContext): Int =
          if (c.item?.proto?.label == T.SWORD) 3 else super.slotCost(c)
      }
    val f = Fixture(rules = rules, protos = protos)
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(DAGGER, ItemLocation.Equipped(p.id, "offhand"))
    assertEquals(setOf("wield"), p.inactiveSlots)
    f.w.startCombat(p, g)
    f.runTo(8)
    assertEquals(listOf<String?>("offhand"), slotsSeen)
    assertEquals(1000 - 3, g.current(fs.health))
  }

  @Test
  fun `if the first swing kills the target, no further swing happens`() {
    val DAGGER = Label("//t:dagger")
    val daggerProto =
      ItemProto(
        DAGGER,
        "a dagger",
        listOf("dagger"),
        kind = "melee",
        wear = "offhand",
        dice = Dice(1, 1, 3),
      )
    val protos = Prototypes(T.rooms, T.mobs(fs), T.items + daggerProto, T.skills(fs))
    val slotsSeen = mutableListOf<String?>()
    val rules =
      object : FixedRules(damage = 50) {
        override fun toHit(c: RulesContext): Int {
          if (c.actor?.name == "Ann") slotsSeen += c.slot
          return super.toHit(c)
        }
      }
    val f = Fixture(rules = rules, protos = protos)
    val p = f.player("Ann")
    val g = f.mob(T.GOBLIN)
    f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(DAGGER, ItemLocation.Equipped(p.id, "offhand"))
    f.w.startCombat(p, g)
    f.runTo(8)
    assertEquals(listOf<String?>("wield"), slotsSeen)
    assertEquals(Condition.DEAD, g.condition)
  }

  @Test
  fun `a before_attack veto on one swing does not stop the next`() {
    val DAGGER = Label("//t:dagger")
    val daggerProto =
      ItemProto(
        DAGGER,
        "a dagger",
        listOf("dagger"),
        kind = "melee",
        wear = "offhand",
        dice = Dice(1, 1, 3),
      )
    val protos = Prototypes(T.rooms, T.mobs(fs), T.items + daggerProto, T.skills(fs))
    val host = RecordingHost()
    val f = Fixture(rules = FixedRules(damage = 3), scripts = host, protos = protos)
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(DAGGER, ItemLocation.Equipped(p.id, "offhand"))
    host.handlers[HookName.BEFORE_ATTACK] = { _, owner, ctx ->
      if (owner === sword && ctx.weapon === sword) HookOutcome.Deny("Your sword refuses to strike.")
      else null
    }
    f.w.startCombat(p, g)
    f.runTo(8)
    val out = f.out(p)
    assertContains(out, "Your sword refuses to strike.")
    assertContains(out, "You hit a training dummy.")
    assertEquals(1000 - 3, g.current(fs.health))
  }
}
