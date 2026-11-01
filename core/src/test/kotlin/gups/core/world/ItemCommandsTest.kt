package gups.core.world

import gups.core.AttrValue
import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.ContainerProto
import gups.core.model.DoorState
import gups.core.model.EquipAffect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
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
      ItemProto(bareKey, "an iron key", listOf("iron"), kind = "key"),
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

  private fun fixture(host: ScriptHost = ScriptHost.NONE) =
    Fixture(scripts = host, protos = Prototypes(T.rooms, T.mobs, T.items + extra, T.skills))

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
  fun `wear fills a slot family in order, then swaps, and worn items add flags and stats`() {
    val f = fixture()
    val p = f.player("Ann")
    val a = f.carry(p, ring)
    val b = f.carry(p, ring)
    val c = f.carry(p, ring)
    assertContains(f.doLine(p, "wear ring"), "You wear a ring.")
    f.doLine(p, "wear ring")
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), a.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), b.location)
    assertContains(f.doLine(p, "wear ring"), "You stop using a ring.")
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), c.location)
    assertEquals(ItemLocation.Carried(p.id), a.location)
    f.carry(p, cap)
    assertContains(f.doLine(p, "wield cap"), "You can't wield a cursed cap.")
    f.doLine(p, "wear cap")
    assertTrue(p.hasFlag("no_regen"))
    assertEquals(12, p.stat(fs.toughness))
    f.doLine(p, "remove cap")
    assertFalse(p.hasFlag("no_regen"))
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
}
