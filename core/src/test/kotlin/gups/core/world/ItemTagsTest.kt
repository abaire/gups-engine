package gups.core.world

import gups.core.AttrValue
import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.AffectScope
import gups.core.model.ContainerProto
import gups.core.model.DoorState
import gups.core.model.EquipAffect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Player
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class ItemTagsTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  private val sword = Label("//t:war_sword")
  private val dagger = Label("//t:dagger")
  private val ring = Label("//t:iron_ring")
  private val coin = Label("//t:coin")
  private val potion = Label("//t:potion")
  private val bag = Label("//t:leather_bag")
  private val backpack = Label("//t:backpack")
  private val goldRing = Label("//t:gold_ring")
  private val silverBand = Label("//t:silver_band")

  private val extra =
    listOf(
      ItemProto(
        sword,
        "a war sword",
        listOf("war", "sword"),
        kind = "melee",
        wear = "wield",
        statDeltas = mapOf(fs.agility to 3),
        tags = setOf("sword"),
      ),
      ItemProto(
        dagger,
        "a dagger",
        listOf("dagger"),
        kind = "melee",
        wear = "wield",
        statDeltas = mapOf(fs.agility to 1),
        tags = setOf("light_weapon"),
      ),
      ItemProto(ring, "an iron ring", listOf("iron", "ring"), wear = "finger_l"),
      ItemProto(coin, "a coin", listOf("coin"), stackable = true),
      ItemProto(potion, "a potion", listOf("potion"), kind = "consumable"),
      ItemProto(bag, "a leather bag", listOf("leather", "bag"), container = ContainerProto()),
      ItemProto(
        backpack,
        "a leather backpack",
        listOf("backpack"),
        wear = "held",
        container = ContainerProto(),
      ),
      ItemProto(
        goldRing,
        "a gold ring",
        listOf("gold", "ring"),
        wear = "finger_l",
        equipAffects = listOf(EquipAffect("glow")),
      ),
      ItemProto(
        silverBand,
        "a silver band",
        listOf("silver", "band"),
        wear = "finger_l",
        equipAffects = listOf(EquipAffect("shine")),
      ),
    )

  private fun fixture(): Fixture =
    Fixture(protos = Prototypes(T.rooms, T.mobs(fs), T.items + extra, T.skills(fs)))

  private fun Fixture.assertFresh(p: Player) {
    assertEquals(w.computeHeld(p), p.heldEffects)
  }

  /** Builds a new Prototypes from [items], dropping the engine CORPSE the constructor adds. */
  private fun Prototypes.replace(items: Map<Label, ItemProto>): Prototypes =
    Prototypes(
      rooms = rooms.values.toList(),
      mobs = mobs.values.toList(),
      items = items.values.filter { it.label.v != "//engine:corpse" }.toList(),
      skills = skills.values.toList(),
    )

  // --- effective tags ---

  @Test
  fun `added and removed tags change effective tags`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword_ = f.w.spawnItem(sword, ItemLocation.Carried(p.id))
    assertEquals(setOf("sword"), sword_.tags)
    f.assertFresh(p)
    assertTrue(f.w.addItemTag(sword_, "cursed"))
    assertEquals(setOf("cursed", "sword"), sword_.tags)
    f.assertFresh(p)
    assertTrue(f.w.removeItemTag(sword_, "sword"))
    assertEquals(setOf("cursed"), sword_.tags)
    f.assertFresh(p)
    f.w.removeItemTag(sword_, "cursed")
    assertEquals(emptySet<String>(), sword_.tags)
    f.assertFresh(p)
  }

  @Test
  fun `adding a tag the prototype already has returns false`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword_ = f.w.spawnItem(sword, ItemLocation.Carried(p.id))
    assertFalse(f.w.addItemTag(sword_, "sword"))
    assertEquals(setOf("sword"), sword_.tags)
    assertTrue(sword_.addedTags.isEmpty())
    f.assertFresh(p)
  }

  @Test
  fun `removing a tag the item does not have returns false`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword_ = f.w.spawnItem(sword, ItemLocation.Carried(p.id))
    assertFalse(f.w.removeItemTag(sword_, "nope"))
    f.assertFresh(p)
  }

  @Test
  fun `an invalid tag throws`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword_ = f.w.spawnItem(sword, ItemLocation.Carried(p.id))
    assertFailsWith<IllegalArgumentException> { f.w.addItemTag(sword_, "Bad Tag") }
    assertFailsWith<IllegalArgumentException> { f.w.removeItemTag(sword_, "1bad") }
  }

  @Test
  fun `a removed prototype tag stays removed across a reload`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword_ = f.w.spawnItem(sword, ItemLocation.Carried(p.id))
    assertTrue(f.w.removeItemTag(sword_, "sword"))
    assertEquals(emptySet<String>(), sword_.tags)
    val changed = f.w.protos.items.getValue(sword).copy(tags = setOf("sword", "sharp"))
    val newItems = f.w.protos.items.toMutableMap()
    newItems[sword] = changed
    f.w.applyPrototypes(f.w.protos.replace(newItems))
    assertEquals(setOf("sharp"), sword_.tags)
    f.assertFresh(p)
  }

  // --- transform ---

  @Test
  fun `transform keeps id location attributes affects and tag changes`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword_ = f.w.spawnItem(sword, ItemLocation.Carried(p.id))
    f.w.addItemTag(sword_, "cursed")
    sword_.customAttributes["owner"] = AttrValue.S("Bob")
    val luck =
      f.w.applyItemAffect(
        sword_,
        "luck",
        null,
        AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 2),
      )!!
    val base = p.stat(fs.agility)
    val oldId = sword_.id
    val oldLoc = sword_.location
    f.w.transformItem(sword_, dagger)
    assertEquals(oldId, sword_.id)
    assertEquals(oldLoc, sword_.location)
    assertEquals("Bob", (sword_.customAttributes["owner"] as AttrValue.S).v)
    assertTrue("cursed" in sword_.tags)
    assertFalse("sword" in sword_.tags)
    assertTrue("light_weapon" in sword_.tags)
    assertEquals("a dagger", sword_.name)
    assertEquals(listOf(luck), sword_.affects)
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
  }

  @Test
  fun `transform of a worn item to a slot-less prototype moves it to the inventory`() {
    val f = fixture()
    val p = f.player("Ann")
    val ring_ = f.w.spawnItem(goldRing, ItemLocation.Equipped(p.id, "finger_l"))
    assertTrue(p.hasFlag("glow"))
    f.w.transformItem(ring_, potion)
    assertEquals(ItemLocation.Carried(p.id), ring_.location)
    assertFalse(p.hasFlag("glow"))
    f.assertFresh(p)
  }

  @Test
  fun `transform of a worn item to the same slot keeps it worn and swaps equip_affects`() {
    val f = fixture()
    val p = f.player("Ann")
    val ring_ = f.w.spawnItem(goldRing, ItemLocation.Equipped(p.id, "finger_l"))
    assertTrue(p.hasFlag("glow"))
    f.w.transformItem(ring_, silverBand)
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), ring_.location)
    assertFalse(p.hasFlag("glow"))
    assertTrue(p.hasFlag("shine"))
    f.assertFresh(p)
  }

  @Test
  fun `transform of a worn item to a different slot moves it to the inventory`() {
    val f = fixture()
    val p = f.player("Ann")
    val neckRing =
      ItemProto(Label("//t:silver_ring"), "a silver ring", listOf("silver", "ring"), wear = "neck")
    val newItems = f.w.protos.items.toMutableMap()
    newItems[neckRing.label] = neckRing
    f.w.applyPrototypes(f.w.protos.replace(newItems))
    val ring_ = f.w.spawnItem(ring, ItemLocation.Equipped(p.id, "finger_l"))
    f.w.transformItem(ring_, neckRing.label)
    assertTrue(ring_.location is ItemLocation.Carried)
    f.assertFresh(p)
  }

  @Test
  fun `transform updates the holder's stat at once`() {
    val f = fixture()
    val p = f.player("Ann")
    val base = p.stat(fs.agility)
    val sword_ = f.w.spawnItem(sword, ItemLocation.Equipped(p.id, "wield"))
    assertEquals(base + 3, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.transformItem(sword_, dagger)
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
  }

  @Test
  fun `a stack transformed into a non-stackable item keeps one`() {
    val f = fixture()
    val p = f.player("Ann")
    val coins = f.w.spawnItem(coin, ItemLocation.Carried(p.id), count = 500)
    f.w.addItemTag(coins, "marked")
    f.w.transformItem(coins, sword)
    assertEquals(1, coins.count)
    assertEquals(sword, coins.protoLabel)
    assertEquals(setOf("marked", "sword"), coins.tags)
    assertEquals(listOf(coins), p.inventory)
    f.assertFresh(p)
  }

  @Test
  fun `a transformed item keeps its new prototype across a reload`() {
    val f = fixture()
    val p = f.player("Ann")
    val sword_ = f.w.spawnItem(sword, ItemLocation.Carried(p.id))
    f.w.transformItem(sword_, dagger)
    assertEquals(dagger, sword_.protoLabel)
    f.w.applyPrototypes(f.w.protos.replace(f.w.protos.items))
    assertEquals(dagger, sword_.protoLabel)
    assertEquals("a dagger", sword_.name)
    f.assertFresh(p)
  }

  @Test
  fun `a stack transformed into a stackable item keeps its count`() {
    val f = fixture()
    val p = f.player("Ann")
    val nugget = ItemProto(Label("//t:nugget"), "a nugget", listOf("nugget"), stackable = true)
    f.addProtos(nugget)
    val coins = f.w.spawnItem(coin, ItemLocation.Carried(p.id), count = 500)
    f.w.transformItem(coins, nugget.label)
    assertEquals(500, coins.count)
    f.assertFresh(p)
  }

  private fun assertUnchanged(item: Item, label: Label, location: ItemLocation, tags: Set<String>) {
    assertEquals(label, item.protoLabel)
    assertEquals(location, item.location)
    assertEquals(tags, item.tags)
  }

  private fun Fixture.addProtos(vararg added: ItemProto) {
    val items = w.protos.items.toMutableMap()
    for (proto in added) items[proto.label] = proto
    w.applyPrototypes(w.protos.replace(items))
  }

  @Test
  fun `a carried container transformed into a non-container drops its contents where the holder stands`() {
    val f = fixture()
    val p = f.player("Ann")
    val backpack_ = f.w.spawnItem(backpack, ItemLocation.Carried(p.id))
    val bag_ = f.w.spawnItem(bag, ItemLocation.Contained(backpack_.id))
    val coins = f.w.spawnItem(coin, ItemLocation.Contained(bag_.id), count = 3)
    val inner = f.w.spawnItem(potion, ItemLocation.Contained(bag_.id))
    f.w.transformItem(bag_, potion)
    val ground = ItemLocation.InRoom(p.room.id, p.trackIndex)
    assertEquals(ground, coins.location)
    assertEquals(ground, inner.location)
    assertEquals(3, coins.count)
    assertEquals(ItemLocation.Contained(backpack_.id), bag_.location)
    assertEquals(null, bag_.container)
    assertTrue(bag_.contents.isEmpty())
    f.assertFresh(p)
  }

  @Test
  fun `a container on the ground transformed into a non-container drops its contents beside it`() {
    val f = fixture()
    val p = f.player("Ann")
    val ground = ItemLocation.InRoom(p.room.id, p.trackIndex)
    val bag_ = f.w.spawnItem(bag, ground)
    val inner = f.w.spawnItem(potion, ItemLocation.Contained(bag_.id))
    f.w.transformItem(bag_, potion)
    assertEquals(ground, inner.location)
    assertEquals(ground, bag_.location)
  }

  @Test
  fun `transform refuses a container target too small for the contents and says why`() {
    val f = fixture()
    val p = f.player("Ann")
    val backpack_ = f.w.spawnItem(backpack, ItemLocation.Carried(p.id))
    repeat(6) { f.w.spawnItem(coin, ItemLocation.Contained(backpack_.id)) }
    val smallBox =
      ItemProto(
        Label("//t:small_box"),
        "a small box",
        listOf("small", "box"),
        container = ContainerProto(capacity = 5),
      )
    f.addProtos(smallBox)
    val state = backpack_.container
    val e =
      assertFailsWith<IllegalArgumentException> { f.w.transformItem(backpack_, smallBox.label) }
    assertContains(e.message.orEmpty(), "can't become //t:small_box")
    assertContains(e.message.orEmpty(), "holds 6 items weighing")
    assertContains(e.message.orEmpty(), "at most 5 items weighing 100")
    assertUnchanged(backpack_, backpack, ItemLocation.Carried(p.id), emptySet())
    assertTrue(state === backpack_.container)
    assertEquals(6, backpack_.contents.size)
    f.assertFresh(p)
  }

  @Test
  fun `a lock carries over only to a container with a key`() {
    val f = fixture()
    val p = f.player("Ann")
    val chest =
      ItemProto(
        Label("//t:iron_chest"),
        "an iron chest",
        listOf("iron", "chest"),
        container = ContainerProto(state = DoorState.LOCKED, key = dagger),
      )
    val cleanChest =
      ItemProto(
        Label("//t:clean_chest"),
        "a clean chest",
        listOf("clean", "chest"),
        container = ContainerProto(state = DoorState.OPEN, key = ring),
      )
    f.addProtos(chest, cleanChest)
    val keyed = f.w.spawnItem(chest.label, ItemLocation.Carried(p.id))
    f.w.transformItem(keyed, cleanChest.label)
    assertEquals(DoorState.LOCKED, keyed.container?.state)
    assertEquals(ring, keyed.container?.key)
    val keyless = f.w.spawnItem(chest.label, ItemLocation.Carried(p.id))
    f.w.transformItem(keyless, bag)
    assertEquals(DoorState.CLOSED, keyless.container?.state)
    assertEquals(null, keyless.container?.key)
    f.assertFresh(p)
  }

  @Test
  fun `transform keeps a closed or open state`() {
    val f = fixture()
    val p = f.player("Ann")
    val bag_ = f.w.spawnItem(bag, ItemLocation.Carried(p.id))
    f.w.setContainerState(bag_, DoorState.CLOSED)
    f.w.transformItem(bag_, backpack)
    assertEquals(DoorState.CLOSED, bag_.container?.state)
    f.w.setContainerState(bag_, DoorState.OPEN)
    f.w.transformItem(bag_, bag)
    assertEquals(DoorState.OPEN, bag_.container?.state)
  }

  @Test
  fun `setContainerState locks only a container with a key`() {
    val f = fixture()
    val p = f.player("Ann")
    val bag_ = f.w.spawnItem(bag, ItemLocation.Carried(p.id))
    assertFailsWith<IllegalArgumentException> { f.w.setContainerState(bag_, DoorState.LOCKED) }
    assertEquals(DoorState.OPEN, bag_.container?.state)
    val potion_ = f.w.spawnItem(potion, ItemLocation.Carried(p.id))
    assertFailsWith<IllegalArgumentException> { f.w.setContainerState(potion_, DoorState.CLOSED) }
  }

  // --- stacking ---

  @Test
  fun `stack does not merge an item with tag changes`() {
    val f = fixture()
    val p = f.player("Ann")
    val c1 = f.w.spawnItem(coin, ItemLocation.Carried(p.id))
    val c2 = f.w.spawnItem(coin, ItemLocation.Carried(p.id))
    f.w.addItemTag(c2, "marked")
    f.w.stack(c2)
    assertEquals(1, c1.count)
    assertEquals(1, c2.count)
  }

  @Test
  fun `a part moved off a stack with tag changes has the same changes`() {
    val f = fixture()
    val p = f.player("Ann")
    val stack = f.w.spawnItem(coin, ItemLocation.Carried(p.id), count = 10)
    f.w.addItemTag(stack, "marked")
    val part = f.w.moveItem(stack, ItemLocation.Carried(p.id), 3)
    assertTrue("marked" in part.tags)
    assertTrue("marked" in stack.tags)
    f.assertFresh(p)
  }
}
