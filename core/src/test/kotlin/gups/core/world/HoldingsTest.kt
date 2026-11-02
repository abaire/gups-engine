package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.Channel
import gups.core.model.ContainerProto
import gups.core.model.DamageType
import gups.core.model.ItemAffect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
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

  @Test
  fun `wearing an item with maxVitalDeltas raises max, and removing it clamps current`() {
    val proto =
      ItemProto(
        ring,
        "a ring",
        listOf("ring"),
        wear = "finger_l",
        maxVitalDeltas = mapOf("hp" to 10),
      )
    val f = fixture(extra = listOf(proto))
    val p = f.player("Ann")
    f.assertFresh(p)
    val baseMax = p.max("hp")
    val item = f.w.spawnItem(ring, ItemLocation.Equipped(p.id, "finger_l"))
    assertEquals(baseMax + 10, p.max("hp"))
    f.assertFresh(p)
    // Raising max doesn't raise current, but we can heal/fill up to the new max.
    f.w.changeVital(p, "hp", 10)
    assertEquals(baseMax + 10, p.current("hp"))
    // Removing the item lowers max and clamps current.
    f.w.moveItem(item, ItemLocation.Carried(p.id))
    assertEquals(baseMax, p.max("hp"))
    assertEquals(baseMax, p.current("hp"))
    f.assertFresh(p)
  }

  @Test
  fun `a carried item affect with resistDeltas counts from inventory, and a worn one only while equipped`() {
    val fire = DamageType("fire")
    val protoCarried =
      ItemProto(
        charm,
        "a charm",
        listOf("charm"),
        affects =
          listOf(
            ItemAffect("fire_ward", applies = AffectScope.CARRIED, resistDeltas = mapOf(fire to 5))
          ),
      )
    val protoWorn =
      ItemProto(
        ring,
        "a ring",
        listOf("ring"),
        wear = "finger_l",
        affects =
          listOf(
            ItemAffect("flame_shield", applies = AffectScope.WORN, resistDeltas = mapOf(fire to 3))
          ),
      )
    val f = fixture(extra = listOf(protoCarried, protoWorn))
    val p = f.player("Ann")
    val baseResist = p.effectiveResist(fire)

    // Charm carried in inventory: carried affect reaches holder
    val charmItem = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    assertEquals(baseResist + 5, p.effectiveResist(fire))
    f.assertFresh(p)

    // Ring in inventory: worn affect does not reach holder
    val ringItem = f.w.spawnItem(ring, ItemLocation.Carried(p.id))
    assertEquals(baseResist + 5, p.effectiveResist(fire))
    f.assertFresh(p)

    // Ring equipped: worn affect now reaches holder
    f.w.moveItem(ringItem, ItemLocation.Equipped(p.id, "finger_l"))
    assertEquals(baseResist + 8, p.effectiveResist(fire))
    f.assertFresh(p)

    // Unequip ring: drops back to charm's contribution
    f.w.moveItem(ringItem, ItemLocation.Carried(p.id))
    assertEquals(baseResist + 5, p.effectiveResist(fire))
    f.assertFresh(p)

    // Drop charm: drops back to base
    f.w.moveItem(charmItem, ItemLocation.InRoom(p.room.id, p.trackIndex))
    assertEquals(baseResist, p.effectiveResist(fire))
    f.assertFresh(p)
  }

  private fun hpAmulet(hp: Int) =
    ItemProto(
      amulet,
      "an amulet",
      listOf("amulet"),
      wear = "neck",
      maxVitalDeltas = mapOf("hp" to hp),
    )

  @Test
  fun `a full mob stays full when a reload raises its gear's maximum`() {
    val f = fixture(extra = listOf(hpAmulet(5)))
    val full = f.mob()
    val hurt = f.mob()
    for (m in listOf(full, hurt)) f.w.spawnItem(amulet, ItemLocation.Equipped(m.id, "neck"))
    f.w.changeVital(full, "hp", 5)
    assertEquals(full.max("hp"), full.current("hp"))
    val hurtHp = hurt.current("hp")

    f.w.applyPrototypes(Prototypes(T.rooms, T.mobs(fs), T.items + hpAmulet(10), T.skills(fs)))

    assertEquals(full.baseMax("hp") + 10, full.max("hp"))
    assertEquals(full.max("hp"), full.current("hp"))
    assertEquals(hurtHp, hurt.current("hp"))
  }

  @Test
  fun `a mob geared up by on_spawn starts with full vitals`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_SPAWN] = { w, owner, _ ->
      if (owner is Mob) w.spawnItem(amulet, ItemLocation.Equipped(owner.id, "neck"))
      null
    }
    val f = fixture(host, extra = listOf(hpAmulet(5)))
    val m = f.mob()
    assertEquals(m.baseMax("hp") + 5, m.max("hp"))
    assertEquals(m.max("hp"), m.current("hp"))
    assertEquals(emptyList(), f.w.diagnostics)
  }

  @Test
  fun `damage dealt by on_spawn is not topped up`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_SPAWN] = { w, owner, _ ->
      if (owner is Mob) {
        w.spawnItem(amulet, ItemLocation.Equipped(owner.id, "neck"))
        w.changeVital(owner, "hp", -3)
      }
      null
    }
    val f = fixture(host, extra = listOf(hpAmulet(5)))
    val m = f.mob()
    assertEquals(m.baseMax("hp") + 5, m.max("hp"))
    assertEquals(m.baseMax("hp") - 3, m.current("hp"))
    assertEquals(emptyList(), f.w.diagnostics)
  }
}
