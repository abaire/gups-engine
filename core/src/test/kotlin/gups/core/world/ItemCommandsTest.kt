package gups.core.world

import gups.core.AttrValue
import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.AffectScope
import gups.core.model.ContainerProto
import gups.core.model.DoorState
import gups.core.model.EquipAffect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
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
class ItemCommandsTest(private val fs: FixtureSchema) {
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

  private val ring = Label("//t:ring")
  private val cap = Label("//t:cap")
  private val helm = Label("//t:helm")
  private val coin = Label("//t:coin")
  private val chest = Label("//t:chest")
  private val anvil = Label("//t:anvil")
  private val pebble = Label("//t:pebble")
  private val loaf = Label("//t:loaf")
  private val blade = Label("//t:blade")
  private val potion = Label("//t:potion")
  private val chainKey = Label("//t:chain_key")
  private val bareKey = Label("//t:bare_key")
  private val chainBox = Label("//t:strongbox")
  private val ironBox = Label("//t:coffer")

  private val extra =
    listOf(
      ItemProto(ring, "a ring", listOf("ring"), wear = "finger"),
      ItemProto(
        cap,
        "a cursed cap",
        listOf("cap"),
        wear = "head",
        equipAffects = listOf(EquipAffect("no_regen")),
        statDeltas = mapOf(FixtureSchemas.current.toughness to 2),
      ),
      ItemProto(
        helm,
        "a dazing helm",
        listOf("helm"),
        wear = "head",
        equipAffects = listOf(EquipAffect("stunned", pulses = 3), EquipAffect("no_regen")),
      ),
      ItemProto(
        coin,
        "a gold coin",
        listOf("coin", "gold"),
        stackable = true,
        plural = "gold coins",
      ),
      ItemProto(pebble, "a pebble", listOf("pebble"), stackable = true),
      ItemProto(loaf, "a loaf", listOf("loaf"), plural = "loaves of bread"),
      ItemProto(blade, "a blade", listOf("long-sword", "gold coin")),
      ItemProto(
        chest,
        "a chest",
        listOf("chest"),
        weight = 50,
        container =
          ContainerProto(capacity = 2, maxWeight = 5, state = DoorState.LOCKED, key = T.KEY),
      ),
      ItemProto(anvil, "an anvil", listOf("anvil"), weight = 95),
      ItemProto(potion, "a potion", listOf("potion")),
      ItemProto(chainKey, "a key on a chain", listOf("chain"), wear = "neck"),
      ItemProto(bareKey, "an iron key", listOf("iron", "key"), kind = "key"),
      ItemProto(
        chainBox,
        "a strongbox",
        listOf("strongbox"),
        container = ContainerProto(1, 5, state = DoorState.LOCKED, key = chainKey),
      ),
      ItemProto(
        ironBox,
        "a coffer",
        listOf("coffer"),
        container = ContainerProto(1, 5, state = DoorState.LOCKED, key = bareKey),
      ),
    )

  private fun fixture(
    host: ScriptHost = ScriptHost.NONE,
    rules: RulesEngine = FixedRules(fs),
    extraItems: List<ItemProto> = emptyList(),
  ) =
    Fixture(
      rules = rules,
      scripts = host,
      protos = Prototypes(T.rooms, T.mobs, T.items + extra + extraItems, T.skills),
    )

  private fun Fixture.carry(p: gups.core.model.Player, label: Label, count: Int = 1) =
    w.spawnItem(label, ItemLocation.Carried(p.id), count)

  private fun Fixture.doLine(p: gups.core.model.Player, line: String): String {
    input(p, line)
    run(3)
    return out(p)
  }

  @Test
  fun `remove and the template's unwield take off both wielded and worn items`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword = f.carry(p, T.SWORD)
    val c = f.carry(p, cap)
    f.doLine(p, "wield sword")
    f.doLine(p, "wear cap")
    assertContains(f.doLine(p, "unwield sword"), "You stop using a sword.")
    assertEquals(ItemLocation.Carried(p.id), sword.location)
    assertContains(f.doLine(p, "unwield cap"), "You stop using a cursed cap.")
    assertEquals(ItemLocation.Carried(p.id), c.location)
    f.doLine(p, "wield sword")
    f.doLine(p, "remove sword")
    assertEquals(ItemLocation.Carried(p.id), sword.location)
  }

  @Test
  fun `wear fills a slot family in order, then refuses when full, and worn items add flags and stats`() {
    val f = fixture()
    val p = f.player("Ann")
    val a = f.carry(p, ring)
    val b = f.carry(p, ring)
    val c = f.carry(p, ring)
    assertContains(f.doLine(p, "wear ring"), "You wear a ring.")
    f.doLine(p, "wear ring")
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), a.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), b.location)
    assertContains(f.doLine(p, "wear ring"), "There's no room for a ring. Remove something first.")
    assertEquals(ItemLocation.Carried(p.id), c.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), a.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), b.location)
    assertContains(f.doLine(p, "remove ring"), "You stop using a ring.")
    assertEquals(ItemLocation.Carried(p.id), a.location)
    assertContains(f.doLine(p, "wear ring"), "You wear a ring.")
    val equipped = p.equipped("finger_l")
    assertTrue(equipped === a || equipped === c)
    f.carry(p, cap)
    assertContains(f.doLine(p, "wield cap"), "You can't wield a cursed cap.")
    f.doLine(p, "wear cap")
    assertTrue(p.hasFlag("no_regen"))
    assertEquals(12, p.stat(fs.toughness))
    f.doLine(p, "remove cap")
    assertFalse(p.hasFlag("no_regen"))
  }

  @Test
  fun `a REMOVE restriction refusing a worn helmet makes wear of another helmet fail and leaves both items where they were`() {
    val f = fixture()
    val p = f.player("Ann")
    val h1 = f.carry(p, helm)
    val h2 = f.carry(p, helm)
    f.doLine(p, "wear helm")
    assertEquals(ItemLocation.Equipped(p.id, "head"), h1.location)
    assertEquals(ItemLocation.Carried(p.id), h2.location)
    f.w.restrictions =
      mapOf(
        RestrictionPoint.REMOVE to
          listOf(
            Restriction { _, r -> if (r.item === h1) "The helm is bound to your head!" else null }
          )
      )
    val out = f.doLine(p, "wear helm")
    assertContains(out, "The helm is bound to your head!")
    assertEquals(ItemLocation.Equipped(p.id, "head"), h1.location)
    assertEquals(ItemLocation.Carried(p.id), h2.location)
  }

  @Test
  fun `wield of a wear = hand item uses the wield messages`() {
    val handProto =
      ItemProto(Label("//t:hand_blade"), "a hand blade", listOf("blade"), wear = "hand")
    val handRules =
      object : FixtureRules(fs) {
        override fun slotFamilies(): Map<String, List<String>> =
          mapOf("finger" to listOf("finger_l", "finger_r"), "hand" to listOf("wield", "offhand"))
      }
    val f = fixture(rules = handRules, extraItems = listOf(handProto))
    val p = f.player("Ann")
    f.carry(p, handProto.label)
    val out = f.doLine(p, "wield blade")
    assertContains(out, "You wield a hand blade.")
  }

  @Test
  fun `equipment order follows actorSlots`() {
    val f = fixture()
    val p = f.player("Ann")
    f.carry(p, cap)
    f.carry(p, T.SWORD)
    f.carry(p, ring)
    f.doLine(p, "wear cap")
    f.doLine(p, "wield sword")
    f.doLine(p, "wear ring")
    val out = f.doLine(p, "equipment")
    val headIdx = out.indexOf("<head>")
    val fingerIdx = out.indexOf("<finger_l>")
    val wieldIdx = out.indexOf("<wield>")
    assertTrue(headIdx != -1 && fingerIdx != -1 && wieldIdx != -1)
    assertTrue(headIdx < fingerIdx && fingerIdx < wieldIdx)
  }

  @Test
  fun `a worn item's affects go through apply_affect, and its stun counts down while worn`() {
    val f = fixture()
    val p = f.player("Ann")
    val h = f.carry(p, helm)
    p.waitState = 5
    f.w.moveItem(h, ItemLocation.Equipped(p.id, "head"))
    assertTrue(p.isStunned)
    assertTrue(
      p.affects.any { it.name == "worn:${h.id.v}:no_regen" },
      "worn flags are real affects",
    )
    assertEquals(0, p.waitState, "an equipment stun holds recovery like a spell's")
    f.run(3)
    assertFalse(p.isStunned, "the stun runs out though the helm is still worn")
    assertEquals(ItemLocation.Equipped(p.id, "head"), h.location)
    assertEquals(4, p.waitState, "recovery comes back when the stun ends")
    assertTrue(p.hasFlag("no_regen"))
    f.w.moveItem(h, ItemLocation.Carried(p.id))
    assertFalse(p.hasFlag("no_regen"))
  }

  @Test
  fun `give hands an item over and fires on_give on the item, then the receiver`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_GIVE] = { _, _, _ -> null }
    val f = fixture(host)
    val p = f.player("Ann")
    val bob = f.player("Bob")
    val key = f.carry(p, T.KEY)
    assertContains(f.doLine(p, "give key to bob"), "You give a brass key to Bob.")
    assertEquals(ItemLocation.Carried(bob.id), key.location)
    assertEquals(
      listOf("on_give:a brass key", "on_give:The Hall", "on_give:Bob"),
      host.calls.filter { it.startsWith("on_give") }.take(3),
    )
    assertContains(f.doLine(p, "give key bob"), "You aren't carrying that.")
  }

  @Test
  fun `containers lock with their key, and put respects state, capacity and weight`() {
    val f = fixture()
    val p = f.player("Ann")
    val box = f.w.spawnItem(chest, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.carry(p, coin)
    assertContains(f.doLine(p, "open chest"), "A chest is locked.")
    assertContains(f.doLine(p, "unlock chest"), "You don't have the key.")
    f.carry(p, T.KEY)
    f.doLine(p, "unlock chest")
    assertContains(f.doLine(p, "open chest"), "You open a chest.")
    assertContains(f.doLine(p, "put coin in chest"), "You put a gold coin in a chest.")
    f.carry(p, anvil)
    assertContains(f.doLine(p, "put anvil in chest"), "An anvil won't fit in a chest.")
    f.carry(p, coin)
    f.doLine(p, "put coin in chest")
    assertEquals(1, box.contents.size, "the coins stacked")
    assertEquals(2, box.contents.single().count)
    f.doLine(p, "close chest")
    assertContains(f.doLine(p, "put key in chest"), "A chest is closed.")
  }

  @Test
  fun `a worn key locks and unlocks containers`() {
    val f = fixture()
    val p = f.player("Ann")
    f.w.spawnItem(chainBox, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.w.spawnItem(chainKey, ItemLocation.Equipped(p.id, "neck"))
    assertContains(f.doLine(p, "unlock strongbox"), "You unlock a strongbox.")
    assertContains(f.doLine(p, "lock strongbox"), "You lock a strongbox.")
  }

  @Test
  fun `key-only items go on the keyring and still unlock`() {
    val f = fixture()
    val p = f.player("Ann")
    f.w.spawnItem(ironBox, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.carry(p, bareKey)
    f.carry(p, coin)
    val inv = f.doLine(p, "inventory")
    assertContains(inv, "a gold coin")
    assertFalse("an iron key" in inv, inv)
    assertContains(f.doLine(p, "keyring"), "Your keyring holds:\n  an iron key")
    assertContains(f.doLine(p, "unlock coffer"), "You unlock a coffer.")
  }

  @Test
  fun `keys with another purpose stay in the inventory`() {
    val f = fixture()
    val p = f.player("Ann")
    f.carry(p, chainKey)
    assertContains(f.doLine(p, "inventory"), "a key on a chain")
    assertContains(f.doLine(p, "keyring"), "Your keyring is empty.")
  }

  @Test
  fun `stacks merge only with identical deltas`() {
    val f = fixture()
    val p = f.player("Ann")
    f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0), 5)
    val mine = f.carry(p, coin, 3)
    f.doLine(p, "drop coin")
    assertFalse(mine.id in f.w.entities)
    assertEquals(8, f.w.room(T.HALL).items.single().count)
    val marked = f.carry(p, coin, 2)
    marked.customAttributes["t:cursed"] = AttrValue.B(true)
    f.doLine(p, "drop coin")
    assertEquals(2, f.w.room(T.HALL).items.size)
  }

  @Test
  fun `use needs an on_use hook and honors before_use`() {
    val host = RecordingHost()
    var veto = true
    host.handlers[HookName.BEFORE_USE] = { _, _, _ ->
      if (veto) HookOutcome.Deny("Not yet.") else null
    }
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner.name == "a potion") w.send(ctx.actor!!, "Glug, at ${ctx.target?.name}.")
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    f.mob(T.GOBLIN)
    f.carry(p, potion)
    assertContains(f.doLine(p, "use potion goblin"), "Not yet.")
    veto = false
    assertContains(f.doLine(p, "use potion goblin"), "Glug, at a goblin.")
    host.handlers.remove(HookName.ON_USE)
    assertContains(f.doLine(p, "use potion"), "You can't use a potion.")
  }

  @Test
  fun `use reaches items on the ground, but not out of reach`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, _, ctx ->
      w.send(ctx.actor!!, "You pull ${ctx.item?.name}.")
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    val hall = f.w.room(T.HALL)
    val near = f.w.spawnItem(potion, ItemLocation.InRoom(hall.id, 0))
    assertContains(f.doLine(p, "use potion"), "You pull a potion.")
    assertEquals(ItemLocation.InRoom(hall.id, 0), near.location)
    f.w.moveItem(near, ItemLocation.InRoom(hall.id, 4))
    assertContains(f.doLine(p, "use potion"), "You can't reach a potion from here.")
    assertContains(f.doLine(p, "use lever"), "You don't see that here.")
  }

  @Test
  fun `carrying too much applies the rules' encumbrance until the load is lightened`() {
    val f = fixture()
    val p = f.player("Ann")
    f.carry(p, anvil)
    assertEquals(10, p.stat(fs.agility))
    f.carry(p, chest)
    assertEquals(mapOf(fs.agility to -2), p.encumbrance)
    assertEquals(8, p.stat(fs.agility))
    f.run()
    assertContains(f.out(p), "You are carrying more than you can manage.")
    f.doLine(p, "drop anvil")
    assertNull(p.encumbrance[fs.agility])
    assertEquals(10, p.stat(fs.agility))
  }

  @Test
  fun `a tiered encumbrance_level tells on, up, up when loading and down, down, off when dropping`() {
    val rules = FixedRules(fs)
    rules.encumbranceLevelOf = { c ->
      val pct = c.carried!! * 100 / c.limit!!
      when {
        pct >= 90 -> 3
        pct >= 85 -> 2
        pct >= 75 -> 1
        else -> 0
      }
    }
    val f = fixture(rules = rules)
    val p = f.player("Ann")
    f.carry(p, pebble, 75)
    f.run()
    assertEquals(1, p.encumbranceLevel)
    assertContains(f.out(p), "You are carrying more than you can manage.")
    f.carry(p, pebble, 10)
    f.run()
    assertEquals(2, p.encumbranceLevel)
    assertContains(f.out(p), "Your load grows heavier.")
    f.carry(p, pebble, 5)
    f.run()
    assertEquals(3, p.encumbranceLevel)
    assertContains(f.out(p), "Your load grows heavier.")
    assertContains(f.doLine(p, "drop 5 pebble"), "Your load grows lighter.")
    assertEquals(2, p.encumbranceLevel)
    assertContains(f.doLine(p, "drop 5 pebble"), "Your load grows lighter.")
    assertEquals(1, p.encumbranceLevel)
    assertContains(f.doLine(p, "drop 10 pebble"), "Your load feels manageable again.")
    assertEquals(0, p.encumbranceLevel)
  }

  @Test
  fun `the level reads 0 inside encumbrance_level and encumbrance is not called at level 0`() {
    var levelInside = -99
    var encCalls = 0
    val counting =
      object : FixedRules(fs) {
        override fun encumbrance(c: RulesContext): Map<String, Int> {
          encCalls++
          return super.encumbrance(c)
        }
      }
    counting.encumbranceLevelOf = { c ->
      levelInside = c.requireActor().encumbranceLevel
      if (c.carried!! > c.limit!!) 1 else 0
    }
    val f = fixture(rules = counting)
    val p = f.player("Ann")
    f.carry(p, pebble, 50)
    assertEquals(0, levelInside, "the level reads 0 inside encumbrance_level")
    assertEquals(0, encCalls, "encumbrance is not called at level 0")
    f.carry(p, pebble, 60)
    assertEquals(1, encCalls, "encumbrance is called while the level is above 0")
  }

  @Test
  fun `a failing encumbrance export leaves level 0 and no penalties`() {
    val rules = FixedRules(fs)
    rules.encumbranceOf = { error("boom") }
    val f =
      Fixture(
        rules = rules,
        protos = Prototypes(T.rooms, T.mobs, T.items + extra, T.skills),
        strict = false,
      )
    val p = f.player("Ann")
    f.carry(p, pebble, 110)
    assertEquals(0, p.encumbranceLevel)
    assertEquals(emptyMap(), p.encumbrance)
  }

  @Test
  fun `a strength debuff that lowers the limit raises the level at the next tick`() {
    val rules = FixedRules(fs)
    rules.encumbranceLevelOf = { c -> if (c.carried!! > c.limit!!) 1 else 0 }
    val f = fixture(rules = rules)
    val p = f.player("Ann")
    f.carry(p, pebble, 60)
    assertEquals(0, p.encumbranceLevel, "60 under the limit of 100 is unencumbered")
    f.w.applyAffect(p, "weak", duration = 200, statDeltas = mapOf(fs.strength to -5))
    assertEquals(0, p.encumbranceLevel, "not yet, until the world ticks")
    f.run(120)
    assertEquals(1, p.encumbranceLevel, "the lowered limit raises the level at the next tick")
  }

  @Test
  fun `the level is not saved, and a restore recomputes it`() {
    val rules = FixedRules(fs)
    rules.encumbranceLevelOf = { c -> if (c.carried!! > c.limit!!) 1 else 0 }
    val f = fixture(rules = rules)
    val p = f.player("Ann")
    f.carry(p, pebble, 110)
    assertEquals(1, p.encumbranceLevel)
    p.encumbranceLevel = 0
    f.w.settleRestoredHoldings(p)
    assertEquals(1, p.encumbranceLevel, "restore recomputes the level from the load")
  }

  @Test
  fun `overriding the encumbrance up message changes the text the actor is told`() {
    val rules = FixedRules(fs)
    rules.encumbranceLevelOf = { c ->
      val pct = c.carried!! * 100 / c.limit!!
      if (pct >= 85) 2 else if (pct >= 75) 1 else 0
    }
    val f =
      Fixture(
        rules = rules,
        protos = Prototypes(T.rooms, T.mobs, T.items + extra, T.skills),
        messages = mapOf("encumbrance.up" to "Your back aches."),
      )
    val p = f.player("Ann")
    f.carry(p, pebble, 75)
    f.carry(p, pebble, 10)
    f.run()
    val out = f.out(p)
    assertContains(out, "Your back aches.")
    assertFalse("Your load grows heavier." in out, out)
  }

  @Test
  fun `describe shows the plural for stacks of 2 and the name otherwise`() {
    val f = fixture()
    val p = f.player("Ann")
    assertEquals("a gold coin", f.carry(p, coin).describe())
    assertEquals("5 gold coins", f.carry(p, coin, 5).describe())
    assertEquals("a pebble (5)", f.carry(p, pebble, 5).describe())
    val marked = f.carry(p, coin, 5)
    marked.nameOverride = "a lucky coin"
    assertEquals("a lucky coin (5)", marked.describe())
    assertEquals("a lucky coin", marked.describe(1))
  }

  @Test
  fun `matches accepts phrases of keywords and plural words, dropping articles`() {
    val f = fixture()
    val p = f.player("Ann")
    val c = f.carry(p, coin)
    assertTrue(c.matches("coins"), "plural words match")
    assertTrue(c.matches("gold coin"))
    assertTrue(c.matches("the coin"))
    assertTrue(c.matches("go co"), "prefixes of separate words match")
    assertFalse(c.matches("silver coin"))
    assertFalse(c.matches("the"), "an article alone names only keywords starting with it")
    assertTrue(f.carry(p, anvil).matches("an"), "an article-only phrase still abbreviates")
    val bread = f.carry(p, loaf)
    assertTrue(bread.matches("loaves"), "plural-only words match")
    assertTrue(bread.matches("bread"))
    val sword = f.carry(p, blade)
    assertTrue(sword.matches("long-sword"), "a keyword with punctuation matches in full")
    assertTrue(sword.matches("sword"))
    assertTrue(sword.matches("gold coin"), "a keyword with a space matches in full")
  }

  @Test
  fun `inventory and look show stack quantities`() {
    val f = fixture()
    val p = f.player("Ann")
    f.carry(p, coin, 5)
    f.carry(p, pebble, 5)
    val inv = f.doLine(p, "inventory")
    assertContains(inv, "5 gold coins")
    assertContains(inv, "a pebble (5)")
    f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0), 5)
    assertContains(f.doLine(p, "look"), "5 gold coins")
    val box = f.w.spawnItem(chest, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.carry(p, T.KEY)
    f.doLine(p, "unlock chest")
    f.doLine(p, "open chest")
    f.w.moveItem(f.carry(p, pebble, 5), ItemLocation.Contained(box.id))
    assertContains(f.doLine(p, "look chest"), "It contains: a pebble (5).")
  }

  @Test
  fun `get, drop, give and put name the whole stack`() {
    val f = fixture()
    val p = f.player("Ann")
    f.player("Bob")
    f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0), 5)
    assertContains(f.doLine(p, "get gold coin"), "You get 5 gold coins.")
    assertContains(f.doLine(p, "drop coins"), "You drop 5 gold coins.")
    f.doLine(p, "get coin")
    assertContains(f.doLine(p, "give gold coin to bob"), "You give 5 gold coins to Bob.")
    val box = f.w.spawnItem(chest, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    box.container!!.state = DoorState.OPEN
    f.carry(p, pebble, 5)
    assertContains(f.doLine(p, "put pebble in chest"), "You put a pebble (5) in a chest.")
  }

  @Test
  fun `get picks the nth match with an ordinal prefix`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    f.w.spawnItem(potion, hall)
    f.w.spawnItem(pebble, hall)
    val second = f.w.spawnItem(potion, hall)
    assertContains(f.doLine(p, "get 2.the potion"), "You get a potion.")
    assertEquals(ItemLocation.Carried(p.id), second.location)
  }

  @Test
  fun `get finds items by a phrase of keywords`() {
    val f = fixture()
    val p = f.player("Ann")
    val ground = f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    assertContains(f.doLine(p, "get gold coin"), "You get a gold coin.")
    assertEquals(ItemLocation.Carried(p.id), ground.location)
  }

  @Test
  fun `the corpse prototype is tagged no_all`() {
    assertTrue("no_all" in Prototypes.CORPSE.tags)
  }

  @Test
  fun `moving part of a stack records a split event for the part, none for the source`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_SPAWN] = { _, _, _ -> null }
    val f = fixture(host)
    val p = f.player("Ann")
    val hall = f.w.room(T.HALL)
    val stack = f.w.spawnItem(coin, ItemLocation.InRoom(hall.id, 0), 5)
    stack.nameOverride = "a lucky coin"
    stack.decayAt = f.w.pulse + 100
    stack.customAttributes["t:cursed"] = AttrValue.B(true)
    host.calls.clear()
    val part = f.w.moveItem(stack, ItemLocation.Carried(p.id), 2)
    assertEquals(2, part.count)
    assertEquals(3, stack.count)
    assertEquals(ItemLocation.InRoom(hall.id, 0), stack.location)
    assertEquals(ItemLocation.Carried(p.id), part.location)
    assertEquals("a lucky coin", part.nameOverride)
    assertEquals(stack.decayAt, part.decayAt)
    assertEquals(stack.customAttributes, part.customAttributes)
    assertEquals(emptyList<String>(), host.calls)
    val events = f.w.drainItemEvents()
    assertEquals(listOf("split"), events.map { it.cause })
    assertEquals(part.id, events.single().item)
  }

  @Test
  fun `destroying part of a carried stack records a reduce event`() {
    val f = fixture()
    val p = f.player("Ann")
    val limit = f.w.rules.carryLimit(RulesContext(f.w, p))
    val stack = f.carry(p, coin, limit + 5)
    assertEquals(mapOf(fs.agility to -2), p.encumbrance)
    f.w.drainItemEvents()
    f.w.destroy(stack, 10)
    assertEquals(limit - 5, stack.count)
    assertNull(p.encumbrance[fs.agility])
    val events = f.w.drainItemEvents()
    assertEquals(listOf("reduce"), events.map { it.cause })
    assertEquals(stack.id, events.single().item)
  }

  @Test
  fun `moving part of a non-stackable or equipped stack throws`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = f.w.room(T.HALL)
    // A non-stackable item with a count above 1, built directly so a partial move is possible.
    val loose = Item(f.w.allocateId(), f.w.protos.item(loaf), ItemLocation.InRoom(hall.id, 0), 2)
    f.w.register(loose)
    assertFailsWith<IllegalArgumentException> { f.w.moveItem(loose, ItemLocation.Carried(p.id), 1) }
    // An equipped stackable: moving part of it is refused.
    val stack = f.w.spawnItem(coin, ItemLocation.InRoom(hall.id, 0), 5)
    f.w.moveItem(stack, ItemLocation.Equipped(p.id, "wield"))
    assertFailsWith<IllegalArgumentException> { f.w.moveItem(stack, ItemLocation.Carried(p.id), 2) }
  }

  @Test
  fun `a count splits the stack on get and drop`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    f.w.spawnItem(coin, hall, 10)
    assertContains(f.doLine(p, "get 5 coins"), "You get 5 gold coins.")
    assertEquals(5, f.w.room(T.HALL).items.single().count, "5 stay on the ground")
    assertEquals(5, p.inventory.single().count, "5 are carried")
    assertContains(f.doLine(p, "drop 3 coins"), "You drop 3 gold coins.")
    assertEquals(2, p.inventory.single().count, "2 are carried")
    assertEquals(8, f.w.room(T.HALL).items.single().count, "the drop merged into the ground stack")
  }

  @Test
  fun `drop across differing stacks drops each with its own message`() {
    val f = fixture()
    val p = f.player("Ann")
    f.carry(p, coin, 4)
    val second = f.carry(p, coin, 5)
    second.customAttributes["t:cursed"] = AttrValue.B(true)
    val out = f.doLine(p, "drop 6 coins")
    assertContains(out, "You drop 4 gold coins.")
    assertContains(out, "You drop 2 gold coins.")
    assertEquals(3, p.inventory.single { it.protoLabel == coin }.count, "3 of the second stay")
  }

  @Test
  fun `a count picks up that many non-stackable items`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    repeat(3) { f.w.spawnItem(ring, hall) }
    assertContains(f.doLine(p, "get 2 ring"), "You get a ring.")
    assertEquals(2, p.inventory.count { it.protoLabel == ring })
    assertEquals(1, f.w.room(T.HALL).items.count { it.protoLabel == ring })
  }

  @Test
  fun `an oversized count moves the whole stack without a capped message`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    f.w.spawnItem(coin, hall, 10)
    val got = f.doLine(p, "get 50000 coins")
    assertContains(got, "You get 10 gold coins.")
    assertFalse("can't carry" in got, "no capped message when everything fits")
    assertContains(f.doLine(p, "drop 50000 coins"), "You drop 10 gold coins.")
  }

  @Test
  fun `a count capped by the carry limit moves what fits and says so`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_GET] = { _, _, _ -> null }
    host.handlers[HookName.ON_GET] = { _, _, _ -> null }
    val f = fixture(host)
    val p = f.player("Ann")
    f.carry(p, pebble, 90) // weight 90, leaving 10 of the 100 limit free
    f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0), 100)
    host.calls.clear()
    val out = f.doLine(p, "get 50000 coins")
    assertContains(out, "You get 10 gold coins.")
    assertContains(out, "You can't carry that much.")
    assertTrue(
      out.indexOf("You get 10 gold coins.") < out.indexOf("You can't carry that much."),
      "the capped message comes once, at the end",
    )
    assertEquals(
      1,
      host.calls.count { it == "before_get:a gold coin" },
      "one before_get on the item",
    )
    assertTrue(host.calls.contains("on_get:a gold coin"), "on_get fires on the moved item")
  }

  @Test
  fun `bare all and all keyword collect every matching stack`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    f.w.spawnItem(coin, hall, 4)
    f.w.spawnItem(coin, hall, 3)
    f.w.spawnItem(pebble, hall, 2)
    val gotAll = f.doLine(p, "get all")
    assertContains(gotAll, "You get 4 gold coins.")
    assertContains(gotAll, "You get 3 gold coins.")
    assertContains(gotAll, "You get a pebble (2).")
    assertContains(f.doLine(p, "drop all coin"), "You drop 7 gold coins.")
    assertEquals(1, p.inventory.size, "only the pebbles remain")
    assertEquals("a pebble (2)", p.inventory.single().describe())
    assertContains(f.doLine(p, "get all coin"), "You get 7 gold coins.")
    assertTrue(f.w.room(T.HALL).items.none { it.protoLabel == coin }, "every coin was taken")
    val droppedAll = f.doLine(p, "drop all")
    assertContains(droppedAll, "You drop 7 gold coins.")
    assertContains(droppedAll, "You drop a pebble (2).")
    assertTrue(p.inventory.isEmpty())
    assertContains(f.doLine(p, "get all pebble"), "You get a pebble (2).")
    assertEquals(listOf(pebble), p.inventory.map { it.protoLabel }, "all pebble leaves the coins")
  }

  @Test
  fun `bare all skips no_all corpses and keyring keys, and explicit keywords move them`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    f.w.spawnItem(coin, hall, 3)
    f.w.spawnItem(Prototypes.CORPSE.label, hall)
    val key = f.carry(p, bareKey)
    assertContains(f.doLine(p, "get all"), "You get 3 gold coins.")
    assertTrue(
      f.w.room(T.HALL).items.any { it.protoLabel == Prototypes.CORPSE.label },
      "the corpse stays on the ground",
    )
    assertContains(f.doLine(p, "drop all"), "You drop 3 gold coins.")
    assertEquals(ItemLocation.Carried(p.id), key.location, "the bare key stays on the keyring")
    assertContains(f.doLine(p, "drop all iron"), "You drop an iron key.")
    assertEquals(ItemLocation.InRoom(hall.room, hall.trackIndex), key.location)
    assertContains(f.doLine(p, "get corpse"), "You get a corpse.")
  }

  @Test
  fun `get with a container takes from it, and the container-versus-ground rule holds`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    val box = f.w.spawnItem(chest, hall)
    box.container!!.state = DoorState.OPEN
    val inBox = ItemLocation.Contained(box.id)
    f.w.spawnItem(coin, inBox, 5)
    val groundCoins = f.w.spawnItem(coin, hall, 3)
    assertContains(f.doLine(p, "get coin chest"), "You get 5 gold coins from a chest.")
    assertTrue(box.contents.isEmpty(), "the coins came from the chest")
    f.w.spawnItem(coin, inBox, 8)
    assertContains(f.doLine(p, "get 5 coins chest"), "You get 5 gold coins from a chest.")
    assertEquals(3, box.contents.single().count, "3 stay in the chest")
    f.w.spawnItem(pebble, inBox, 2)
    val looted = f.doLine(p, "get all chest")
    assertContains(looted, "You get 3 gold coins from a chest.")
    assertContains(looted, "You get a pebble (2) from a chest.")
    assertTrue(box.contents.isEmpty(), "the chest is emptied")
    assertEquals(hall, groundCoins.location, "the ground coins were never touched")
    assertEquals(3, groundCoins.count)
    assertContains(f.doLine(p, "get gold coin"), "You get 3 gold coins.")
    assertEquals(ItemLocation.InRoom(hall.room, hall.trackIndex), box.location, "the chest stays")
    assertTrue(
      f.w.room(T.HALL).items.none { it.protoLabel == coin },
      "the coins came off the ground",
    )
  }

  @Test
  fun `get from a closed container says it is closed`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    val box = f.w.spawnItem(chest, hall) // the fixture chest starts locked
    f.w.moveItem(f.carry(p, coin, 5), ItemLocation.Contained(box.id))
    assertContains(f.doLine(p, "get coin chest"), "A chest is closed.")
  }

  @Test
  fun `get all corpse loots the corpse`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    val corpse = f.w.spawnItem(Prototypes.CORPSE.label, hall)
    f.w.moveItem(f.carry(p, coin, 3), ItemLocation.Contained(corpse.id))
    assertContains(f.doLine(p, "get all corpse"), "from a corpse.")
  }

  @Test
  fun `get from a carried container is not capped by the carry limit`() {
    val f = fixture()
    val p = f.player("Ann")
    val bag = f.carry(p, T.BAG)
    f.w.moveItem(f.carry(p, coin), ItemLocation.Contained(bag.id))
    f.carry(p, anvil) // weight 95
    f.carry(p, pebble, 4) // weight 4; with the bag and coin the load is over the limit
    assertContains(f.doLine(p, "get coin from bag"), "You get a gold coin from a bag.")
    assertEquals(ItemLocation.Carried(p.id), p.inventory.single { it.protoLabel == coin }.location)
  }

  @Test
  fun `a vetoing before_get is called once per stack, not twice`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_GET] = { _, _, _ -> HookOutcome.Deny("No.") }
    val f = fixture(host)
    val p = f.player("Ann")
    val hall = ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    f.w.spawnItem(coin, hall, 4)
    f.w.spawnItem(coin, hall, 3)
    host.calls.clear()
    f.doLine(p, "get all")
    assertEquals(
      2,
      host.calls.count { it == "before_get:a gold coin" },
      "once per source stack, not twice",
    )
    assertEquals(0, p.inventory.count { it.protoLabel == coin }, "nothing is picked up")
  }

  @Test
  fun `scavenge still makes a mob pick up an item`() {
    val magpie =
      MobProto(
        Label("//t:magpie"),
        "magpie",
        listOf("magpie"),
        vitals = mapOf(fs.health to 40),
        behaviors = listOf(Scavenge()),
      )
    val f =
      Fixture(protos = Prototypes(T.rooms, T.mobs + listOf(magpie), T.items + extra, T.skills))
    val m = f.mob(Label("//t:magpie"))
    val coin = f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.runTo(10)
    assertEquals(ItemLocation.Carried(m.id), coin.location)
  }

  @Test
  fun `a stack that before_get moves elsewhere is not picked up`() {
    val host = RecordingHost()
    val f = fixture(host)
    host.handlers[HookName.BEFORE_GET] = { w, owner, _ ->
      (owner as? Item)?.let { w.moveItem(it, ItemLocation.InRoom(w.room(T.YARD).id, 0)) }
      null
    }
    val p = f.player("Ann")
    val coins = f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0), 5)
    assertFalse("You get" in f.doLine(p, "get 2 coins"))
    assertEquals(ItemLocation.InRoom(f.w.room(T.YARD).id, 0), coins.location)
    assertEquals(5, coins.count, "nothing was split off")
    assertTrue(p.inventory.isEmpty())
  }

  @Test
  fun `a stack that before_drop moves elsewhere is not dropped`() {
    val host = RecordingHost()
    val f = fixture(host)
    val p = f.player("Ann")
    val bag = f.carry(p, T.BAG)
    host.handlers[HookName.BEFORE_DROP] = { w, owner, _ ->
      (owner as? Item)?.let { w.moveItem(it, ItemLocation.Contained(bag.id)) }
      null
    }
    val coins = f.carry(p, coin, 5)
    assertFalse("You drop" in f.doLine(p, "drop 2 coins"))
    assertEquals(ItemLocation.Contained(bag.id), coins.location)
    assertEquals(5, coins.count, "nothing was split off")
  }

  @Test
  fun `a count too large for an Int is capped to what is there`() {
    val f = fixture()
    val p = f.player("Ann")
    f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0), 10)
    assertContains(f.doLine(p, "get 99999999999 coins"), "You get 10 gold coins.")
    assertContains(f.doLine(p, "drop 99999999999 coins"), "You drop 10 gold coins.")
    assertEquals(
      ItemSpec(all = false, count = Int.MAX_VALUE, ordinal = 1, keyword = "coins"),
      parseItemSpec("99999999999 coins"),
    )
    assertNull(parseItemSpec("-1 coins"))
  }

  @Test
  fun `give with a count splits the stack and merges into the receiver's stack`() {
    val f = fixture()
    val p = f.player("Ann")
    val bob = f.player("Bob")
    f.carry(p, coin, 10)
    f.carry(bob, coin)
    assertContains(f.doLine(p, "give 5 coins to bob"), "You give 5 gold coins to Bob.")
    assertEquals(5, p.inventory.single().count, "Ann keeps 5")
    assertEquals(6, bob.inventory.single().count, "Bob's stack merged to 6")
    assertContains(f.doLine(p, "give 5 coins bob"), "You give 5 gold coins to Bob.")
    assertTrue(p.inventory.isEmpty(), "Ann is empty")
    assertEquals(11, bob.inventory.single().count, "the rest merged to 11")
  }

  @Test
  fun `give all hands over everything except the keyring key, and all coin only the coins`() {
    val f = fixture()
    val p = f.player("Ann")
    val bob = f.player("Bob")
    f.carry(p, coin)
    val key = f.carry(p, bareKey)
    assertContains(f.doLine(p, "give all bob"), "You give a gold coin to Bob.")
    assertEquals(ItemLocation.Carried(p.id), key.location, "the bare key stays on the keyring")
    assertEquals(1, bob.inventory.count { it.protoLabel == coin })
    f.carry(p, coin)
    assertContains(f.doLine(p, "give all coins bob"), "You give a gold coin to Bob.")
    assertEquals(ItemLocation.Carried(p.id), key.location, "the bare key still stays")
    assertEquals(
      2,
      bob.inventory.single { it.protoLabel == coin }.count,
      "both coins stacked in Bob's hand",
    )
  }

  @Test
  fun `give capped by the receiver's carry limit gives what fits and says so`() {
    val f = fixture()
    val p = f.player("Ann")
    val bob = f.player("Bob")
    f.carry(bob, pebble, 90) // weight 90, leaving 10 of the 100 limit free
    f.carry(p, coin, 100)
    val out = f.doLine(p, "give 50000 coins bob")
    assertContains(out, "You give 10 gold coins to Bob.")
    assertContains(out, "Bob can't carry that much.")
    assertEquals(90, p.inventory.single().count, "Ann keeps the rest")
    assertEquals(10, bob.inventory.single { it.protoLabel == coin }.count)
  }

  @Test
  fun `put with a count works with or without the preposition, and the coins stack`() {
    val f = fixture()
    val p = f.player("Ann")
    val bag = f.w.spawnItem(T.BAG, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.carry(p, coin, 9)
    assertContains(f.doLine(p, "put 3 coins in bag"), "You put 3 gold coins in a bag.")
    assertContains(f.doLine(p, "put 3 coins into bag"), "You put 3 gold coins in a bag.")
    assertContains(f.doLine(p, "put 3 coins bag"), "You put 3 gold coins in a bag.")
    assertEquals(9, bag.contents.single().count, "all nine stacked inside")
    assertTrue(p.inventory.isEmpty())
  }

  @Test
  fun `put all never moves the container itself and skips no_all items`() {
    val f = fixture()
    val p = f.player("Ann")
    val box = f.carry(p, chest)
    box.container!!.state = DoorState.OPEN
    f.carry(p, coin)
    val corpse = f.carry(p, Prototypes.CORPSE.label)
    assertContains(f.doLine(p, "put all chest"), "You put a gold coin in a chest.")
    assertEquals(ItemLocation.Carried(p.id), box.location, "the chest is never moved")
    assertEquals(ItemLocation.Carried(p.id), corpse.location, "the no_all corpse stays")
    f.carry(p, coin)
    assertContains(f.doLine(p, "put all in chest"), "You put a gold coin in a chest.")
    assertEquals(2, box.contents.single().count, "both coins stacked inside")
  }

  @Test
  fun `put capped by the container's max_weight puts what fits and says so`() {
    val f = fixture()
    val p = f.player("Ann")
    val box = f.w.spawnItem(chest, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    box.container!!.state = DoorState.OPEN
    f.carry(p, coin, 10)
    val out = f.doLine(p, "put 50000 coins chest")
    assertContains(out, "You put 5 gold coins in a chest.")
    assertContains(out, "A gold coin won't fit in a chest.")
    assertEquals(5, box.contents.single().count)
    assertEquals(5, p.inventory.single().count)
  }

  @Test
  fun `put all capped by the container's capacity puts what fits and says so`() {
    val f = fixture()
    val p = f.player("Ann")
    val box = f.w.spawnItem(chest, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    box.container!!.state = DoorState.OPEN
    f.carry(p, coin)
    f.carry(p, pebble)
    f.carry(p, loaf)
    val out = f.doLine(p, "put all chest")
    assertContains(out, "You put a gold coin in a chest.")
    assertContains(out, "You put a pebble in a chest.")
    assertContains(out, "A chest is full.")
    assertEquals(2, box.contents.size, "two items, no stacking across kinds")
    assertEquals(ItemLocation.Carried(p.id), p.inventory.single { it.protoLabel == loaf }.location)
  }

  @Test
  fun `putting a container inside itself is refused`() {
    val f = fixture()
    val p = f.player("Ann")
    f.carry(p, chest)
    assertContains(f.doLine(p, "put chest in chest"), "You can't put something inside itself.")
  }

  @Test
  fun `an ordinal counts the target container among the matches`() {
    val f = fixture()
    val p = f.player("Ann")
    val first = f.carry(p, ironBox)
    val second = f.carry(p, ironBox)
    first.container!!.state = DoorState.OPEN
    second.container!!.state = DoorState.OPEN
    assertContains(f.doLine(p, "put 2.coffer in coffer"), "You put a coffer in a coffer.")
    assertEquals(ItemLocation.Contained(first.id), second.location)
    assertContains(f.doLine(p, "put coffer in coffer"), "You can't put something inside itself.")
  }

  @Test
  fun `give skips a stack an earlier on_give hook destroyed`() {
    val host = RecordingHost()
    val f = fixture(host)
    val p = f.player("Ann")
    val bob = f.player("Bob")
    val ring1 = f.carry(p, ring)
    val ring2 = f.carry(p, ring)
    host.handlers[HookName.ON_GIVE] = { w, owner, _ ->
      if (owner === ring1 && ring2.id in w.entities) w.destroy(ring2)
      null
    }
    val out = f.doLine(p, "give all ring bob")
    assertEquals(1, out.lines().count { "You give a ring to Bob." in it })
    assertEquals(ItemLocation.Carried(bob.id), ring1.location)
    assertFalse(ring2.id in f.w.entities)
    assertTrue(p.inventory.isEmpty())
  }

  @Test
  fun `queued get, drop and put each resolve on the state the previous left`() {
    val f = fixture()
    val p = f.player("Ann")
    val box = f.w.spawnItem(chest, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    box.container!!.state = DoorState.OPEN
    f.w.spawnItem(coin, ItemLocation.Contained(box.id), 2)
    f.carry(p, coin, 2)
    f.input(p, "get all chest")
    f.input(p, "drop 2 coins")
    f.input(p, "put all chest")
    f.run(3)
    val out = f.out(p)
    assertContains(out, "You get 2 gold coins from a chest.")
    assertContains(out, "You drop 2 gold coins.")
    assertContains(out, "You put 2 gold coins in a chest.")
    assertTrue(p.inventory.isEmpty(), "nothing is carried")
    assertEquals(2, box.contents.single().count, "the put saw only the two left after the drop")
    assertEquals(
      2,
      f.w.room(T.HALL).items.single { it.protoLabel == coin }.count,
      "the dropped coins stay on the ground",
    )
  }

  @Test
  fun `a key that gains a tag leaves the keyring until the tag is removed`() {
    val f = fixture()
    val p = f.player("Ann")
    val key = f.carry(p, bareKey)
    assertTrue(f.w.addItemTag(key, "cursed"))
    val inv = f.doLine(p, "inventory")
    assertContains(inv, "an iron key")
    val kr = f.doLine(p, "keyring")
    assertContains(kr, "Your keyring is empty.")
    assertContains(f.doLine(p, "drop all"), "You drop an iron key.")
    assertTrue(key.location is ItemLocation.InRoom)
    assertContains(f.doLine(p, "get key"), "You get an iron key.")
    assertTrue(f.w.removeItemTag(key, "cursed"))
    val kr2 = f.doLine(p, "keyring")
    assertContains(kr2, "Your keyring holds:\n  an iron key")
    val inv2 = f.doLine(p, "inventory")
    assertFalse("an iron key" in inv2, inv2)
  }

  @Test
  fun `a key that gains an affect leaves the keyring until it expires`() {
    val f = fixture()
    val p = f.player("Ann")
    val key = f.carry(p, bareKey)
    f.w.applyItemAffect(key, "glow", duration = 1, applies = AffectScope.CARRIED)
    assertContains(f.doLine(p, "keyring"), "Your keyring is empty.")
    f.run(2)
    assertContains(f.doLine(p, "keyring"), "Your keyring holds:\n  an iron key")
  }
}
