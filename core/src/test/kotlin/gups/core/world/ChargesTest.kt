package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.EmptyAction
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class ChargesTest(private val fs: FixtureSchema) {
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

  private val wand = Label("//t:wand")
  private val otherWand = Label("//t:other_wand")
  private val potion = Label("//t:potion")
  private val flask = Label("//t:flask")
  private val emptyFlask = Label("//t:empty_flask")
  private val lamp = Label("//t:lamp")
  private val stackableFlask = Label("//t:stackable_flask")

  private val extra =
    listOf(
      ItemProto(wand, "a wand", listOf("wand"), charges = 3, empty = EmptyAction.Keep),
      ItemProto(otherWand, "a big wand", listOf("wand"), charges = 5, empty = EmptyAction.Keep),
      ItemProto(potion, "a potion", listOf("potion"), charges = 1, stackable = true),
      ItemProto(emptyFlask, "an empty flask", listOf("flask")),
      ItemProto(
        flask,
        "a flask",
        listOf("flask"),
        charges = 2,
        empty = EmptyAction.Variant("empty"),
        variants = mapOf("empty" to emptyFlask),
      ),
      ItemProto(lamp, "a lamp", listOf("lamp")),
      ItemProto(
        stackableFlask,
        "a stackable flask",
        listOf("flask"),
        charges = 1,
        stackable = true,
        empty = EmptyAction.Variant("empty"),
        variants = mapOf("empty" to emptyFlask),
      ),
    )

  private fun fixture(
    host: ScriptHost = ScriptHost.NONE,
    extraItems: List<ItemProto> = emptyList(),
  ) =
    Fixture(
      rules = FixedRules(fs),
      scripts = host,
      protos = Prototypes(T.rooms, T.mobs, T.items + extra + extraItems, T.skills),
    )

  private fun Fixture.doLine(p: gups.core.model.Player, line: String): String {
    input(p, line)
    run(3)
    return out(p)
  }

  private fun Prototypes.replace(items: Map<Label, ItemProto>): Prototypes =
    Prototypes(
      rooms = rooms.values.toList(),
      mobs = mobs.values.toList(),
      items = items.values.filter { it.label.v != "//engine:corpse" }.toList(),
      skills = skills.values.toList(),
      zones = zones.values.toList(),
    )

  @Test
  fun `each use spends a charge and an empty kept item is refused`() {
    val host = RecordingHost()
    var onUseCalls = 0
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner === ctx.item) {
        onUseCalls++
        w.send(ctx.actor!!, "Zap!")
      }
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    val item = f.w.spawnItem(wand, ItemLocation.Carried(p.id))
    assertEquals(3, item.charges)

    assertContains(f.doLine(p, "use wand"), "Zap!")
    assertEquals(2, item.charges)
    assertEquals(1, onUseCalls)

    assertContains(f.doLine(p, "use wand"), "Zap!")
    assertEquals(1, item.charges)
    assertEquals(2, onUseCalls)

    assertContains(f.doLine(p, "use wand"), "Zap!")
    assertEquals(0, item.charges)
    assertEquals(3, onUseCalls)

    val out = f.doLine(p, "use wand")
    assertContains(out, "There are no charges left in a wand.")
    assertEquals(3, onUseCalls)
    assertEquals(0, item.charges)
  }

  @Test
  fun `using one of a stack spends one item`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner === ctx.item) {
        w.send(ctx.actor!!, "Glug.")
      }
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    val stack = f.w.spawnItem(potion, ItemLocation.Carried(p.id), count = 3)
    assertEquals(3, stack.count)

    assertContains(f.doLine(p, "use potion"), "Glug.")
    assertEquals(2, stack.count)
  }

  @Test
  fun `the last charge transforms into the empty variant`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner === ctx.item) {
        w.send(ctx.actor!!, "Sip.")
      }
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    val item = f.w.spawnItem(flask, ItemLocation.Carried(p.id))
    assertEquals(2, item.charges)

    assertContains(f.doLine(p, "use flask"), "Sip.")
    assertEquals(1, item.charges)
    assertEquals(flask, item.protoLabel)

    assertContains(f.doLine(p, "use flask"), "Sip.")
    assertEquals(emptyFlask, item.protoLabel)
    assertNull(item.charges)
  }

  @Test
  fun `a variant split off a stack keeps the rest`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner === ctx.item) {
        w.send(ctx.actor!!, "Drink.")
      }
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    val stack = f.w.spawnItem(stackableFlask, ItemLocation.Carried(p.id), count = 2)

    assertContains(f.doLine(p, "use flask"), "Drink.")
    assertEquals(1, stack.count)
    assertEquals(stackableFlask, stack.protoLabel)
    val variant = p.inventory.first { it !== stack }
    assertEquals(emptyFlask, variant.protoLabel)
    assertEquals(1, variant.count)
  }

  @Test
  fun `a hook that destroys the item spends nothing`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner === ctx.item) {
        w.destroy(owner)
        w.send(ctx.actor!!, "Consumed completely.")
      }
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    f.w.spawnItem(wand, ItemLocation.Carried(p.id))

    val out = f.doLine(p, "use wand")
    assertContains(out, "Consumed completely.")
    assertEquals(0, p.inventory.size)
    assertEquals(emptyList(), f.w.diagnostics.toList())
  }

  @Test
  fun `a vetoed use spends nothing`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_USE] = { _, owner, ctx ->
      if (owner === ctx.item) HookOutcome.Deny("Blocked.") else null
    }
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner === ctx.item) {
        w.send(ctx.actor!!, "Should not run.")
      }
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    val item = f.w.spawnItem(wand, ItemLocation.Carried(p.id))

    assertContains(f.doLine(p, "use wand"), "Blocked.")
    assertEquals(3, item.charges)
  }

  @Test
  fun `items without charges are never used up`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner === ctx.item) {
        w.send(ctx.actor!!, "Light.")
      }
      null
    }
    val f = fixture(host)
    val p = f.player("Ann")
    val item = f.w.spawnItem(lamp, ItemLocation.Carried(p.id))

    repeat(5) { assertContains(f.doLine(p, "use lamp"), "Light.") }
    assertNull(item.charges)
    assertEquals(1, p.inventory.size)
  }

  @Test
  fun `set_charges refuses unlimited, stackable and out-of-range values`() {
    val f = fixture()
    val p = f.player("Ann")
    val wItem = f.w.spawnItem(wand, ItemLocation.Carried(p.id))
    val lItem = f.w.spawnItem(lamp, ItemLocation.Carried(p.id))
    val sItem = f.w.spawnItem(potion, ItemLocation.Carried(p.id))

    assertFailsWith<IllegalArgumentException> { f.w.setCharges(lItem, 1) }
    assertFailsWith<IllegalArgumentException> { f.w.setCharges(sItem, 1) }
    assertFailsWith<IllegalArgumentException> { f.w.setCharges(wItem, -1) }
    assertFailsWith<IllegalArgumentException> { f.w.setCharges(wItem, 4) }

    f.w.setCharges(wItem, 1)
    assertEquals(1, wItem.charges)
    f.w.setCharges(wItem, 0)
    assertEquals(0, wItem.charges)
  }

  @Test
  fun `split copies charges and different charges don't merge`() {
    val f = fixture()
    val p = f.player("Ann")
    val hall = f.w.room(T.HALL)
    val stack = f.w.spawnItem(potion, ItemLocation.Carried(p.id), count = 3)
    assertEquals(1, stack.charges)

    val moved = f.w.moveItem(stack, ItemLocation.InRoom(hall.id, 0), count = 1)
    assertEquals(1, moved.charges)
    assertEquals(2, stack.count)

    moved.charges = 0
    val returned = f.w.moveItem(moved, ItemLocation.Carried(p.id))
    f.w.stack(returned)
    assertEquals(2, p.inventory.size)
    val invCharges = p.inventory.map { it.charges }.toSet()
    assertEquals(setOf(1, 0), invCharges)
  }

  @Test
  fun `a variant split off an equipped stack is carried`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { _, _, _ -> null }
    val f = fixture(host)
    val p = f.player("Ann")
    val stack = f.w.spawnItem(stackableFlask, ItemLocation.Equipped(p.id, "finger_l"), count = 2)

    f.w.spendCharge(stack)
    assertEquals(1, stack.count)
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), stack.location)
    val variant = p.inventory.single { it.protoLabel == emptyFlask }
    assertEquals(ItemLocation.Carried(p.id), variant.location)
  }

  @Test
  fun `a variant split off a debug stack is a debug item`() {
    val f = fixture()
    val p = f.player("Ann")
    val stack = f.w.spawnItem(stackableFlask, ItemLocation.Carried(p.id), count = 2, debug = true)

    f.w.spendCharge(stack)
    assertEquals(true, p.inventory.single { it.protoLabel == emptyFlask }.debug)
  }

  @Test
  fun `a reload that adds charges fills instances and stackable items get the new value`() {
    val f = fixture()
    val p = f.player("Ann")
    val lampItem = f.w.spawnItem(lamp, ItemLocation.Carried(p.id))
    val potionItem = f.w.spawnItem(potion, ItemLocation.Carried(p.id), count = 2)
    assertNull(lampItem.charges)

    val newItems = f.w.protos.items.toMutableMap()
    newItems[lamp] = newItems.getValue(lamp).copy(charges = 4)
    potionItem.charges = 0
    f.w.applyPrototypes(f.w.protos.replace(newItems))
    assertEquals(4, lampItem.charges)
    assertEquals(1, potionItem.charges)
  }

  @Test
  fun `transform and reload reset or cap charges`() {
    val f = fixture()
    val p = f.player("Ann")
    val item = f.w.spawnItem(wand, ItemLocation.Carried(p.id))
    assertEquals(3, item.charges)

    f.w.transformItem(item, otherWand)
    assertEquals(5, item.charges)

    // Reload capping charges: change otherWand to charges = 2
    val changedProto = f.w.protos.items.getValue(otherWand).copy(charges = 2)
    val newItems = f.w.protos.items.toMutableMap()
    newItems[otherWand] = changedProto
    f.w.applyPrototypes(f.w.protos.replace(newItems))
    assertEquals(2, item.charges)

    // Reload removing charges: change otherWand to charges = null
    val unlimitedProto = f.w.protos.items.getValue(otherWand).copy(charges = null)
    newItems[otherWand] = unlimitedProto
    f.w.applyPrototypes(f.w.protos.replace(newItems))
    assertNull(item.charges)
  }
}
