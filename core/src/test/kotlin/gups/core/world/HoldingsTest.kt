package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.Channel
import gups.core.model.ContainerProto
import gups.core.model.ItemAffect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoldingsTest {
  private val fs = FixtureSchemas.FANTASY
  private val ring = Label("//t:ring")
  private val amulet = Label("//t:amulet")
  private val helm = Label("//t:helm")
  private val vest = Label("//t:vest")
  private val charm = Label("//t:charm")
  private val lead = Label("//t:lead")

  private fun fixture(host: ScriptHost = ScriptHost.NONE, extra: List<ItemProto> = emptyList()) =
    Fixture(
      fs = fs,
      scripts = host,
      protos = Prototypes(T.rooms, T.mobs(fs), T.items + extra, T.skills(fs)),
    )

  /** The cache must equal the world's derived value after every step. */
  private fun Fixture.assertFresh(p: Player) {
    assertEquals(w.computeHeld(p), p.heldEffects)
  }

  @Test
  fun `wearing an item with statDeltas raises the stat, and removing it restores it`() {
    val proto = ItemProto(ring, "a ring", listOf("ring"), statDeltas = mapOf(fs.toughness to 3))
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    val base = p.stat(fs.toughness)
    f.w.spawnItem(ring, ItemLocation.Equipped(p.id, "finger_l"))
    assertEquals(base + 3, p.stat(fs.toughness))
    f.assertFresh(p)
    f.w.moveItem(p.equipped("finger_l")!!, ItemLocation.Carried(p.id))
    assertEquals(base, p.stat(fs.toughness))
    f.assertFresh(p)
  }

  @Test
  fun `sense follows a worn item's senseDeltas`() {
    val proto =
      ItemProto(
        helm,
        "a helm",
        listOf("helm"),
        wear = "head",
        senseDeltas = mapOf(Channel.SIGHT to 2),
      )
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    assertEquals(1, p.sense(Channel.SIGHT))
    f.w.spawnItem(helm, ItemLocation.Equipped(p.id, "head"))
    assertEquals(3, p.sense(Channel.SIGHT))
    f.assertFresh(p)
    f.w.moveItem(p.equipped("head")!!, ItemLocation.Carried(p.id))
    assertEquals(1, p.sense(Channel.SIGHT))
    f.assertFresh(p)
  }

  @Test
  fun `a worn item's modifiers appear in the chain in slot order`() {
    val host = RecordingHost()
    host.modifiers["amulet"] = { _, _, base -> (base as Int) + 1 }
    host.modifiers["vest"] = { _, _, base -> (base as Int) + 1 }
    val f =
      fixture(
        host,
        listOf(
          ItemProto(
            amulet,
            "an amulet",
            listOf("amulet"),
            wear = "neck",
            modifiers = mapOf(ModifierPoint.TO_HIT to mod("amulet")),
          ),
          ItemProto(
            vest,
            "a vest",
            listOf("vest"),
            wear = "body",
            modifiers = mapOf(ModifierPoint.TO_HIT to mod("vest")),
          ),
        ),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.assertFresh(p)
    // Slot order is neck then body; worn the other way round and sorted by name, it's body then
    // neck. Only slot order puts the amulet first.
    f.w.spawnItem(vest, ItemLocation.Equipped(p.id, "body"))
    f.assertFresh(p)
    f.w.spawnItem(amulet, ItemLocation.Equipped(p.id, "neck"))
    f.assertFresh(p)
    host.calls.clear()
    val r = f.w.toHitChain(RulesContext(f.w, p, d))
    assertEquals(102, r.value)
    assertEquals(listOf("mod:item:amulet:amulet", "mod:item:vest:vest"), host.calls)
  }

  @Test
  fun `destroying a worn item clears its contribution`() {
    val proto = ItemProto(ring, "a ring", listOf("ring"), statDeltas = mapOf(fs.toughness to 4))
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    val base = p.stat(fs.toughness)
    val item = f.w.spawnItem(ring, ItemLocation.Equipped(p.id, "finger_l"))
    assertEquals(base + 4, p.stat(fs.toughness))
    f.assertFresh(p)
    f.w.destroy(item)
    assertEquals(base, p.stat(fs.toughness))
    f.assertFresh(p)
  }

  @Test
  fun `a reload that changes a worn item's prototype statDeltas updates the stat`() {
    val proto = ItemProto(ring, "a ring", listOf("ring"), statDeltas = mapOf(fs.toughness to 2))
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    val base = p.stat(fs.toughness)
    f.w.spawnItem(ring, ItemLocation.Equipped(p.id, "finger_l"))
    assertEquals(base + 2, p.stat(fs.toughness))
    f.assertFresh(p)
    val next =
      Prototypes(
        T.rooms,
        T.mobs(fs),
        T.items + proto.copy(statDeltas = mapOf(fs.toughness to 5)),
        T.skills(fs),
      )
    f.w.applyPrototypes(next)
    assertEquals(base + 5, p.stat(fs.toughness))
    f.assertFresh(p)
  }

  @Test
  fun `a worn-scope prototype affect counts while worn and not while only carried`() {
    val proto =
      ItemProto(
        charm,
        "a charm",
        listOf("charm"),
        wear = "neck",
        affects = listOf(ItemAffect("glow", AffectScope.WORN, statDeltas = mapOf(fs.agility to 1))),
      )
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    val base = p.stat(fs.agility)
    f.w.spawnItem(charm, ItemLocation.Equipped(p.id, "neck"))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(p.equipped("neck")!!, ItemLocation.Carried(p.id))
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
  }

  @Test
  fun `a carried-scope affect counts in inventory, worn, and nested containers`() {
    val proto =
      ItemProto(
        charm,
        "a charm",
        listOf("charm"),
        wear = "neck",
        affects =
          listOf(ItemAffect("luck", AffectScope.CARRIED, statDeltas = mapOf(fs.agility to 1))),
      )
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    val base = p.stat(fs.agility)
    val charm = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(charm, ItemLocation.Equipped(p.id, "neck"))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    val bag = f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    f.w.moveItem(charm, ItemLocation.Contained(bag.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    val pack = f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    f.w.moveItem(bag, ItemLocation.Contained(pack.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(pack, ItemLocation.Equipped(p.id, "body"))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(pack, ItemLocation.Carried(p.id))
    f.w.moveItem(bag, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(bag, ItemLocation.Contained(pack.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.destroy(pack)
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
  }

  @Test
  fun `an item affect's flag shows in hasFlag and flags`() {
    val proto =
      ItemProto(
        charm,
        "a charm",
        listOf("charm"),
        wear = "neck",
        affects = listOf(ItemAffect("shades", flags = setOf("infravision"))),
      )
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    assertFalse(p.hasFlag("infravision"))
    f.w.spawnItem(charm, ItemLocation.Equipped(p.id, "neck"))
    assertTrue(p.hasFlag("infravision"))
    assertTrue("infravision" in p.flags)
    f.assertFresh(p)
    f.w.moveItem(p.equipped("neck")!!, ItemLocation.Carried(p.id))
    assertFalse(p.hasFlag("infravision"))
    f.assertFresh(p)
  }

  @Test
  fun `an item affect's stunned flag is ignored`() {
    val proto =
      ItemProto(
        charm,
        "a charm",
        listOf("charm"),
        wear = "neck",
        affects = listOf(ItemAffect("daze", flags = setOf("stunned"))),
      )
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    f.w.spawnItem(charm, ItemLocation.Equipped(p.id, "neck"))
    assertFalse(p.isStunned)
    f.assertFresh(p)
  }

  @Test
  fun `a tombstone contributes nothing`() {
    val f = fixture()
    val p = f.player("Ann")
    val charm = f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))
    charm.affects +=
      Affect(
        f.w.allocateHandle(),
        "lucky",
        null,
        0,
        Long.MAX_VALUE,
        flags = setOf("infravision"),
        applies = AffectScope.CARRIED,
      )
    f.w.refreshHoldings(p)
    assertTrue(p.hasFlag("infravision"))
    f.assertFresh(p)
    charm.proto = tombstone(charm.proto)
    f.w.refreshHoldings(p)
    assertFalse(p.hasFlag("infravision"))
    f.assertFresh(p)
  }

  @Test
  fun `a containment filter blocks only affects behind the container it names`() {
    val f =
      fixture(
        extra =
          listOf(
            ItemProto(
              charm,
              "a charm",
              listOf("charm"),
              wear = "neck",
              affects =
                listOf(ItemAffect("luck", AffectScope.CARRIED, statDeltas = mapOf(fs.agility to 1))),
            ),
            ItemProto(
              lead,
              "a lead box",
              listOf("lead", "box"),
              tags = setOf("lead"),
              container = ContainerProto(),
            ),
          )
      )
    val p = f.player("Ann")
    f.assertFresh(p)
    val base = p.stat(fs.agility)
    f.w.containmentFilters =
      listOf(ContainmentFilter { _, _, container, _, _ -> "lead" in container.proto.tags })
    val charm = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    val box = f.w.spawnItem(lead, ItemLocation.Carried(p.id))
    f.w.moveItem(charm, ItemLocation.Contained(box.id))
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
    val bag = f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    f.w.moveItem(charm, ItemLocation.Contained(bag.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(bag, ItemLocation.Contained(box.id))
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(bag, ItemLocation.Carried(p.id))
    f.w.moveItem(box, ItemLocation.Contained(bag.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    f.w.moveItem(charm, ItemLocation.Equipped(p.id, "neck"))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
  }

  @Test
  fun `an instance affect without a scope reaches the holder only while the item is worn`() {
    val proto = ItemProto(charm, "a charm", listOf("charm"), wear = "neck")
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    val charm = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    charm.affects +=
      Affect(f.w.allocateHandle(), "glint", null, 0, Long.MAX_VALUE, flags = setOf("infravision"))
    f.w.refreshHoldings(p)
    assertFalse(p.hasFlag("infravision"))
    f.assertFresh(p)
    f.w.moveItem(charm, ItemLocation.Equipped(p.id, "neck"))
    assertTrue(p.hasFlag("infravision"))
    f.assertFresh(p)
  }

  @Test
  fun `a worn-scope affect inside a worn container doesn't reach the holder`() {
    val proto =
      ItemProto(
        charm,
        "a charm",
        listOf("charm"),
        wear = "neck",
        affects = listOf(ItemAffect("glow", AffectScope.WORN, statDeltas = mapOf(fs.agility to 1))),
      )
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    val base = p.stat(fs.agility)
    val pack = f.w.spawnItem(T.BAG, ItemLocation.Equipped(p.id, "body"))
    f.w.spawnItem(charm, ItemLocation.Contained(pack.id))
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
  }

  @Test
  fun `a containment filter that throws does not block, and records a plugin diagnostic`() {
    val f =
      fixture(
        extra =
          listOf(
            ItemProto(
              charm,
              "a charm",
              listOf("charm"),
              affects =
                listOf(ItemAffect("luck", AffectScope.CARRIED, statDeltas = mapOf(fs.agility to 1))),
            )
          )
      )
    val p = f.player("Ann")
    f.assertFresh(p)
    val base = p.stat(fs.agility)
    f.w.containmentFilters =
      listOf(ContainmentFilter { _, _, _, _, _ -> throw IllegalStateException("boom") })
    val box = f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    f.w.spawnItem(charm, ItemLocation.Contained(box.id))
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    assertTrue(
      f.w.diagnostics.any { it.kind == "plugin" && "containment filter failed" in it.message }
    )
  }

  @Test
  fun `item affect modifiers follow the worn item and carried affect order`() {
    val host = RecordingHost()
    host.modifiers["ring"] = { _, _, base -> (base as Int) + 1 }
    host.modifiers["ring_glow"] = { _, _, base -> (base as Int) + 1 }
    host.modifiers["charm_luck"] = { _, _, base -> (base as Int) + 1 }
    val f =
      fixture(
        host,
        listOf(
          ItemProto(
            ring,
            "a ring",
            listOf("ring"),
            wear = "finger_l",
            modifiers = mapOf(ModifierPoint.TO_HIT to mod("ring")),
            affects =
              listOf(
                ItemAffect(
                  "glow",
                  AffectScope.WORN,
                  modifiers = mapOf(ModifierPoint.TO_HIT to mod("ring_glow")),
                )
              ),
          ),
          ItemProto(
            charm,
            "a charm",
            listOf("charm"),
            affects =
              listOf(
                ItemAffect(
                  "luck",
                  AffectScope.CARRIED,
                  modifiers = mapOf(ModifierPoint.TO_HIT to mod("charm_luck")),
                )
              ),
          ),
        ),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.assertFresh(p)
    f.w.spawnItem(ring, ItemLocation.Equipped(p.id, "finger_l"))
    f.assertFresh(p)
    f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    f.assertFresh(p)
    host.calls.clear()
    val r = f.w.toHitChain(RulesContext(f.w, p, d))
    assertEquals(103, r.value)
    assertEquals(
      listOf(
        "mod:item:ring:ring",
        "mod:item:ring:glow:ring_glow",
        "mod:item:charm:luck:charm_luck",
      ),
      host.calls,
    )
  }
}
