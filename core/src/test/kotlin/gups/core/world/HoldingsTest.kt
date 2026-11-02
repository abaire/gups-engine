package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Channel
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals

class HoldingsTest {
  private val fs = FixtureSchemas.FANTASY
  private val ring = Label("//t:ring")
  private val amulet = Label("//t:amulet")
  private val helm = Label("//t:helm")
  private val vest = Label("//t:vest")

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
}
