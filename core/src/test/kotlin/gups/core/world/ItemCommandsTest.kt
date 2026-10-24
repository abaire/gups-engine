package gups.core.world

import gups.core.Label
import gups.core.model.ContainerProto
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemCommandsTest {
    private val ring = Label("//t:ring")
    private val cap = Label("//t:cap")
    private val coin = Label("//t:coin")
    private val chest = Label("//t:chest")
    private val anvil = Label("//t:anvil")
    private val potion = Label("//t:potion")

    private val extra = listOf(
        ItemProto(ring, "a ring", listOf("ring"), wear = "finger"),
        ItemProto(cap, "a cursed cap", listOf("cap"), wear = "head", equipAffects = setOf("no_regen"), statDeltas = mapOf("con" to 2)),
        ItemProto(coin, "a gold coin", listOf("coin", "gold"), stackable = true),
        ItemProto(chest, "a chest", listOf("chest"), weight = 50, container = ContainerProto(capacity = 2, maxWeight = 5, state = DoorState.LOCKED, key = T.KEY)),
        ItemProto(anvil, "an anvil", listOf("anvil"), weight = 95),
        ItemProto(potion, "a potion", listOf("potion")),
    )

    private fun fixture(host: ScriptHost = ScriptHost.NONE) =
        Fixture(scripts = host, protos = Prototypes(T.rooms, T.mobs, T.items + extra, T.skills))

    private fun Fixture.carry(p: gups.core.model.Player, label: Label, count: Int = 1) = w.spawnItem(label, ItemLocation.Carried(p.id), count)

    private fun Fixture.doLine(p: gups.core.model.Player, line: String): String {
        input(p, line)
        run(3)
        return out(p)
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
        assertEquals(12, p.stat("con"))
        f.doLine(p, "remove cap")
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
        assertEquals(listOf("on_give:a brass key", "on_give:The Hall", "on_give:Bob"), host.calls.filter { it.startsWith("on_give") }.take(3))
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
    fun `stacks merge only with identical deltas`() {
        val f = fixture()
        val p = f.player("Ann")
        f.w.spawnItem(coin, ItemLocation.InRoom(f.w.room(T.HALL).id, 0), 5)
        val mine = f.carry(p, coin, 3)
        f.doLine(p, "drop coin")
        assertFalse(mine.id in f.w.entities)
        assertEquals(8, f.w.room(T.HALL).items.single().count)
        val marked = f.carry(p, coin, 2)
        marked.customAttributes["t:cursed"] = gups.core.AttrValue.B(true)
        f.doLine(p, "drop coin")
        assertEquals(2, f.w.room(T.HALL).items.size)
    }

    @Test
    fun `use needs an on_use hook and honors before_use`() {
        val host = RecordingHost()
        var veto = true
        host.handlers[HookName.BEFORE_USE] = { _, _, _ -> if (veto) HookOutcome.Deny("Not yet.") else null }
        host.handlers[HookName.ON_USE] = { w, owner, ctx -> if (owner.name == "a potion") w.send(ctx.actor!!, "Glug, at ${ctx.target?.name}."); null }
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
        assertEquals(10, p.stat("dex"))
        f.carry(p, chest)
        assertEquals(mapOf("dex" to -2), p.encumbrance)
        assertEquals(8, p.stat("dex"))
        f.run()
        assertContains(f.out(p), "You are carrying more than you can manage.")
        f.doLine(p, "drop anvil")
        assertNull(p.encumbrance["dex"])
        assertEquals(10, p.stat("dex"))
    }
}
