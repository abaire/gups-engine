package gups.core.world

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.RegionProto
import gups.core.model.ZoneProto
import gups.core.rules.Regen
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModifierChainTest {
    private val cap = Label("//t:cap")
    private val blade = Label("//t:blade")

    private fun protos(hallModifiers: Map<ModifierPoint, gups.core.HookRef> = emptyMap()) = Prototypes(
        listOf(T.rooms[0].copy(regions = setOf("b_reg", "a_reg"), modifiers = hallModifiers)) + T.rooms.drop(1),
        T.mobs,
        T.items + listOf(
            ItemProto(cap, "a cap", listOf("cap"), wear = "head", modifiers = mapOf(ModifierPoint.TO_HIT to mod("cap"))),
            ItemProto(blade, "a blade", listOf("blade"), wear = "wield", modifiers = mapOf(ModifierPoint.TO_HIT to mod("blade"))),
        ),
        T.skills,
        zones = listOf(ZoneProto("t", modifiers = mapOf(ModifierPoint.TO_HIT to mod("zone")))),
        regions = listOf(
            RegionProto("b_reg", "t", mapOf(ModifierPoint.TO_HIT to mod("rb"), ModifierPoint.DAMAGE to mod("halve"))),
            RegionProto("a_reg", "t", mapOf(ModifierPoint.TO_HIT to mod("ra"))),
        ),
    )

    @Test
    fun `the chain runs zone, regions by name, room, affects by id, then worn items in slot order`() {
        val host = RecordingHost()
        for (n in listOf("zone", "ra", "rb", "room", "aff_a", "aff_b", "cap", "blade")) host.modifiers[n] = { _, _, base -> (base as Int) + 1 }
        val f = Fixture(rules = FixedRules(toHit = 50), scripts = host, protos = protos(mapOf(ModifierPoint.TO_HIT to mod("room"))))
        val p = f.player("Ann")
        val d = f.mob(T.DUMMY)
        f.w.applyAffect(p, "zeta", 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("aff_a")))
        f.w.applyAffect(p, "alpha", 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("aff_b")))
        f.w.moveItem(f.w.spawnItem(blade, ItemLocation.Carried(p.id)), ItemLocation.Equipped(p.id, "wield"))
        f.w.moveItem(f.w.spawnItem(cap, ItemLocation.Carried(p.id)), ItemLocation.Equipped(p.id, "head"))
        host.calls.clear()
        val r = f.w.toHitChain(RulesContext(f.w, p, d))
        assertEquals(58, r.value)
        assertEquals(
            listOf(
                "mod:zone:t:zone", "mod:region:a_reg:ra", "mod:region:b_reg:rb", "mod:room:hall:room",
                "mod:affect:zeta:aff_a", "mod:affect:alpha:aff_b", "mod:item:cap:cap", "mod:item:blade:blade",
            ),
            host.calls,
        )
        assertContains(r.text, "rules.to_hit base=50 → zone:t 51 → region:a_reg 52")
        assertContains(r.text, "→ item:blade 58")
    }

    @Test
    fun `modifiers change auto-attacks, and the rules trace shows the chain and the roll`() {
        val host = RecordingHost()
        host.modifiers["halve"] = { _, _, base -> (base as Int) / 2 }
        val f = Fixture(rules = FixedRules(damage = 10), scripts = host, protos = protos())
        val lines = mutableListOf<String>()
        f.w.onRules = { _, line -> lines += line }
        val p = f.player("Ann")
        val d = f.mob(T.DUMMY)
        f.w.startCombat(p, d)
        f.runTo(8)
        assertEquals(1000 - 5, d.hp)
        assertTrue(lines.any { it.startsWith("rules.to_hit base=100") && it.endsWith("HIT") && "roll" in it }, lines.toString())
        assertTrue(lines.any { "rules.damage base=10 → region:b_reg 5" in it }, lines.toString())
        assertTrue(lines.any { it.startsWith("initiative at pulse 8:") }, lines.toString())
    }

    @Test
    fun `cast_allowed stops at the first denial and the cast costs nothing`() {
        val host = RecordingHost()
        host.modifiers["fizzle"] = { _, _, _ -> "Your magic fizzles into nothing." }
        host.modifiers["never"] = { _, _, _ -> error("not reached") }
        val f = Fixture(
            scripts = host,
            protos = Prototypes(
                listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.CAST_ALLOWED to mod("fizzle")))) + T.rooms.drop(1),
                T.mobs, T.items, T.skills,
                zones = listOf(ZoneProto("t")),
            ),
        )
        val p = f.player("Ann")
        p.skills[T.BOLT] = 100
        f.w.applyAffect(p, "late", 100, modifiers = mapOf(ModifierPoint.CAST_ALLOWED to mod("never")))
        f.mob(T.DUMMY)
        f.input(p, "cast bolt dummy")
        f.run()
        assertContains(f.out(p), "Your magic fizzles into nothing.")
        assertEquals(50, p.mana)
        assertEquals(0, p.waitState)
        assertNull(p.casting)
    }

    @Test
    fun `regen and move cost go through the chain`() {
        val host = RecordingHost()
        host.modifiers["slow"] = { point, _, base ->
            when (point) {
                ModifierPoint.REGEN -> (base as Regen).let { Regen(it.hp / 2, 0, 0) }
                ModifierPoint.MOVE_COST -> (base as Int) + 4
                else -> base
            }
        }
        val f = Fixture(scripts = host)
        val p = f.player("Ann", hp = 50)
        f.w.applyAffect(p, "weary", 10_000, modifiers = mapOf(ModifierPoint.REGEN to mod("slow"), ModifierPoint.MOVE_COST to mod("slow")))
        p.hp = 10
        val regen = f.w.regenOf(RulesContext(f.w, p))
        assertEquals(Regen((1 + 10 / 4 + 1 / 2) / 2, 0, 0), regen)
        f.input(p, "north")
        f.run()
        assertEquals(100 - 5, p.move)
    }
}
