package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.AffectScope
import gups.core.model.ContainerProto
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Player
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ItemAffectsTest {
  private val fs = FixtureSchemas.FANTASY
  private val charm = Label("//t:charm")
  private val arrow = Label("//t:arrow")
  private val pouch = Label("//t:pouch")
  private val hooks = Label("//t:hooks.star")

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

  private fun Fixture.doLine(p: Player, line: String): String {
    input(p, line)
    run(3)
    return out(p)
  }

  @Test
  fun `a timed carried affect expires, dropping the holder's stat and firing on_expire`() {
    val host = RecordingHost()
    var owner: Entity? = null
    var reason: AffectEndReason? = null
    host.refHandlers["gone"] = { _, o, ctx ->
      owner = o
      reason = ctx.affectEndReason
    }
    val f = fixture(host, listOf(ItemProto(charm, "a charm", listOf("charm"), wear = "neck")))
    val p = f.player("Ann")
    val item = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    val base = p.stat(fs.agility)
    f.w.applyItemAffect(
      item,
      "luck",
      5,
      applies = AffectScope.CARRIED,
      statDeltas = mapOf(fs.agility to 2),
      onExpire = HookRef(hooks, "gone"),
    )
    assertEquals(base + 2, p.stat(fs.agility))
    f.assertFresh(p)
    f.runTo(f.w.pulse + 4)
    assertEquals(base + 2, p.stat(fs.agility), "still running one pulse before its expiry")
    f.run()
    assertEquals(base, p.stat(fs.agility))
    f.assertFresh(p)
    assertEquals(emptyList(), item.affects)
    assertEquals(item, owner)
    assertEquals(AffectEndReason.EXPIRED, reason)
    assertEquals(listOf("ref:gone@${f.w.pulse}"), host.calls.filter { it.startsWith("ref:") })
  }

  @Test
  fun `a permanent affect never expires`() {
    val f = fixture(extra = listOf(ItemProto(charm, "a charm", listOf("charm"), wear = "neck")))
    val p = f.player("Ann")
    val item = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    val base = p.stat(fs.agility)
    val a =
      f.w.applyItemAffect(
        item,
        "luck",
        null,
        applies = AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 2),
      )
    assertEquals(Long.MAX_VALUE, a!!.expiresAt)
    assertEquals(base + 2, p.stat(fs.agility))
    f.assertFresh(p)
    f.run(1000)
    assertEquals(base + 2, p.stat(fs.agility))
    assertEquals(1, item.affects.size)
    f.assertFresh(p)
  }

  @Test
  fun `a same-name item affect is replaced only by one that expires later`() {
    val f = fixture(extra = listOf(ItemProto(charm, "a charm", listOf("charm"))))
    val p = f.player("Ann")
    val item = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    val short =
      f.w.applyItemAffect(
        item,
        "luck",
        10,
        applies = AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 1),
      )
    val longer =
      f.w.applyItemAffect(
        item,
        "luck",
        20,
        applies = AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 1),
      )
    assertNotSame(short, longer, "a longer affect replaces a shorter one")
    assertEquals(1, item.affects.size)
    val shorter =
      f.w.applyItemAffect(
        item,
        "luck",
        5,
        applies = AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 1),
      )
    assertSame(longer, shorter, "a shorter affect doesn't replace a longer one")
    val permanent =
      f.w.applyItemAffect(
        item,
        "luck",
        null,
        applies = AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 1),
      )
    assertNotSame(longer, permanent, "a permanent affect replaces a timed one")
    val timed =
      f.w.applyItemAffect(
        item,
        "luck",
        10,
        applies = AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 1),
      )
    assertSame(permanent, timed, "a timed affect doesn't replace a permanent one")
    assertEquals(1, item.affects.size)
  }

  @Test
  fun `on_tick fires every tickEvery pulses, and never on the expiry pulse`() {
    val host = RecordingHost()
    val f = fixture(host, listOf(ItemProto(charm, "a charm", listOf("charm"), wear = "neck")))
    val p = f.player("Ann")
    val item = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    f.w.applyItemAffect(item, "burn", 10, tickEvery = 3, onTick = HookRef(hooks, "tick"))
    f.runTo(9)
    assertEquals(
      listOf("ref:tick@3", "ref:tick@6", "ref:tick@9"),
      host.calls.filter { it.startsWith("ref:") },
    )
    f.run()
    assertEquals(
      listOf("ref:tick@3", "ref:tick@6", "ref:tick@9"),
      host.calls.filter { it.startsWith("ref:") },
      "no tick on the expiry pulse",
    )
    assertEquals(emptyList(), item.affects)
  }

  @Test
  fun `an item destroyed by one affect's hook takes its other affects with it`() {
    val host = RecordingHost()
    val f = fixture(host, listOf(ItemProto(charm, "a charm", listOf("charm"))))
    val p = f.player("Ann")
    val item = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    host.refHandlers["crumble"] = { _, _, _ -> f.w.destroy(item) }
    f.w.applyItemAffect(item, "dust", 5, onExpire = HookRef(hooks, "crumble"))
    f.w.applyItemAffect(item, "glow", 5, onExpire = HookRef(hooks, "gone"))
    f.w.applyItemAffect(item, "hum", 10, tickEvery = 5, onTick = HookRef(hooks, "tick"))
    f.run(6)
    assertEquals(listOf("ref:crumble@5"), host.calls.filter { it.startsWith("ref:") })
    f.assertFresh(p)
  }

  @Test
  fun `an item affect in a dormant room catches up on wake`() {
    val host = RecordingHost()
    var owner: Entity? = null
    var reason: AffectEndReason? = null
    host.refHandlers["gone"] = { _, o, ctx ->
      owner = o
      reason = ctx.affectEndReason
    }
    val f = Fixture(protos = C.protos(), scripts = host, bubble = true)
    val room = f.w.room(C.r(9))
    val crumb = f.w.spawnItem(C.CRUMB, ItemLocation.InRoom(room.id, 0))
    f.w.applyItemAffect(
      crumb,
      "rot",
      300,
      applies = AffectScope.CARRIED,
      onExpire = HookRef(Label("//c:t.star"), "gone"),
    )
    f.runTo(400)
    assertEquals(1, crumb.affects.size, "no expiry while the room is dormant")
    host.calls.clear()
    f.player("Ann", at = C.r(9))
    f.run()
    assertEquals(emptyList(), crumb.affects)
    assertEquals(listOf("ref:gone@401"), host.calls.filter { it.startsWith("ref:") })
    assertEquals(crumb, owner)
    assertEquals(AffectEndReason.EXPIRED, reason)
  }

  @Test
  fun `an item affect with the stunned flag is refused`() {
    val f = fixture(extra = listOf(ItemProto(charm, "a charm", listOf("charm"))))
    val p = f.player("Ann")
    val item = f.w.spawnItem(charm, ItemLocation.Carried(p.id))
    assertFailsWith<IllegalArgumentException> {
      f.w.applyItemAffect(item, "daze", 5, flags = setOf("stunned"))
    }
    assertEquals(emptyList(), item.affects)
  }

  @Test
  fun `an item with an affect doesn't stack, and a full container can't merge it`() {
    val arrowProto = ItemProto(arrow, "an arrow", listOf("arrow"), stackable = true)
    val pouchProto =
      ItemProto(pouch, "a pouch", listOf("pouch"), container = ContainerProto(capacity = 1))
    val f = fixture(extra = listOf(arrowProto, pouchProto))
    val p = f.player("Ann")
    // stack() leaves the affected item out of the merge.
    val wet = f.w.spawnItem(arrow, ItemLocation.Carried(p.id), 2)
    f.w.applyItemAffect(wet, "wet", 5)
    val dry = f.w.spawnItem(arrow, ItemLocation.Carried(p.id), 3)
    assertSame(dry, f.w.stack(dry))
    assertEquals(3, dry.count)
    assertSame(wet, f.w.stack(wet))
    assertEquals(2, wet.count)
    f.w.destroy(wet)
    f.w.destroy(dry)
    // put into a full container refuses the merge while the item has an affect.
    val pouch = f.w.spawnItem(pouch, ItemLocation.Carried(p.id))
    val inPouch = f.w.spawnItem(arrow, ItemLocation.Contained(pouch.id))
    val heavy = f.w.spawnItem(arrow, ItemLocation.Carried(p.id), 4)
    f.w.applyItemAffect(heavy, "wet", 5)
    assertTrue("is full" in f.doLine(p, "put arrow in pouch"))
    assertEquals(4, heavy.count)
    // Without the affect, the same put merges.
    assertTrue(f.w.removeItemAffect(heavy, "wet"))
    f.doLine(p, "put arrow in pouch")
    assertEquals(5, inPouch.count)
  }

  @Test
  fun `a moved part copies the stack's instance affects with a new id and the same expiry`() {
    val f = fixture(extra = listOf(ItemProto(arrow, "an arrow", listOf("arrow"), stackable = true)))
    val p = f.player("Ann")
    val stack = f.w.spawnItem(arrow, ItemLocation.Carried(p.id), 10)
    val base = p.stat(fs.agility)
    val a =
      f.w.applyItemAffect(
        stack,
        "wet",
        5,
        applies = AffectScope.CARRIED,
        statDeltas = mapOf(fs.agility to 1),
      )
    assertEquals(base + 1, p.stat(fs.agility))
    f.assertFresh(p)
    val part = f.w.moveItem(stack, ItemLocation.Carried(p.id), 3)
    val copied = part.affects.single()
    assertNotEquals(a!!.id, copied.id)
    assertEquals(a.expiresAt, copied.expiresAt)
    assertEquals(a.name, copied.name)
    assertEquals(a.statDeltas, copied.statDeltas)
    assertEquals(stack.affects.single().id, a.id)
    assertEquals(7, stack.count)
    assertEquals(3, part.count)
    // Both copies are carried, so the holder sees both.
    assertEquals(base + 2, p.stat(fs.agility))
    f.assertFresh(p)
  }
}
