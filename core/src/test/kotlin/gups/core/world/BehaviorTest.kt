package gups.core.world

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.ItemLocation
import gups.core.model.MobProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorTest {
    private fun mob(name: String, vararg behaviors: Behavior, hp: Int = 40) =
        MobProto(Label("//t:$name"), name, listOf(name), hp = hp, behaviors = behaviors.toList())

    private fun fixture(vararg mobs: MobProto, rules: FixedRules = FixedRules(flee = 100)) =
        Fixture(rules = rules, protos = T.protos(extraMobs = mobs.toList()))

    @Test
    fun `aggressive mobs attack matching actors on sight, unless they outlevel the limit`() {
        val f = fixture(mob("brute", Aggressive(ActorFilter("players"), unlessLevelAbove = 5)))
        val brute = f.mob(Label("//t:brute"))
        val p = f.player("Ann")
        f.runTo(3)
        assertTrue(p.id in brute.fighting)
        assertEquals(p.id, brute.primaryTarget)

        val g = fixture(mob("brute", Aggressive(ActorFilter("players"), unlessLevelAbove = 5)))
        val b2 = g.mob(Label("//t:brute"))
        val hero = g.player("Bea")
        hero.level = 9
        g.runTo(20)
        assertFalse(b2.isEngaged)
    }

    @Test
    fun `assist joins an ally's fight against its opponent`() {
        val f = fixture(mob("helper", Assist(ActorFilter("goblin"))))
        val p = f.player("Ann")
        val goblin = f.mob(T.GOBLIN)
        val helper = f.mob(Label("//t:helper"))
        f.input(p, "kill goblin")
        f.runTo(6)
        assertTrue(goblin.id in p.fighting)
        assertTrue(p.id in helper.fighting)
    }

    @Test
    fun `flee runs at the threshold and beats every other proposal`() {
        val f = fixture(mob("coward", Aggressive(ActorFilter("players")), Flee(20)))
        val coward = f.mob(Label("//t:coward"))
        f.player("Ann")
        coward.hp = 8
        f.runTo(3)
        assertTrue(coward.room.label != T.HALL, "fled to ${coward.room.label}")
        assertFalse(coward.isEngaged)
    }

    @Test
    fun `a guard bars its exit to non-allies while it can act`() {
        val f = fixture(mob("warden", Guard(Direction.NORTH)))
        val warden = f.mob(Label("//t:warden"))
        val p = f.player("Ann")
        f.input(p, "north")
        f.run()
        assertContains(f.out(p), "Warden blocks your way.")
        assertEquals(T.HALL, p.room.label)
        val g = f.mob(T.GOBLIN)
        f.w.actMove(g, Direction.NORTH)
        f.runTo(3)
        assertEquals(T.YARD, g.room.label, "mobs are the guard's allies by default")
        f.w.applyAffect(warden, "stun", 50, flags = setOf("stunned"))
        f.input(p, "north")
        f.run()
        assertEquals(T.YARD, p.room.label)
    }

    @Test
    fun `wander stays within its radius of home`() {
        val protos = C.protos(n = 12)
        val walker = MobProto(Label("//c:walker"), "walker", listOf("walker"), behaviors = listOf(Wander(radius = 2, chance = 1.0)))
        val f = Fixture(protos = Prototypes(protos.rooms.values.toList(), listOf(walker), zones = protos.zones.values.toList()))
        val m = f.w.spawnMob(walker.label, f.w.room(C.r(5)))
        val seen = mutableSetOf<String>()
        repeat(600) {
            f.run()
            seen += m.room.label.name
        }
        assertEquals(setOf("r3", "r4", "r5", "r6", "r7"), seen)
    }

    @Test
    fun `sentinel keeps a mob in its room whatever else it wants`() {
        val f = fixture(mob("statue", Wander(3, 1.0), Flee(90), Sentinel))
        val statue = f.mob(Label("//t:statue"))
        statue.hp = 1
        f.runTo(100)
        assertEquals(T.HALL, statue.room.label)
    }

    @Test
    fun `follow trails its leader and pursue paths across the world`() {
        val f = fixture(mob("pup", Follow(ActorFilter("players"))))
        val pup = f.mob(Label("//t:pup"))
        val p = f.player("Ann")
        f.run(4) // the pup notices Ann
        f.input(p, "north")
        f.runTo(12)
        assertEquals(T.YARD, pup.room.label)

        val protos = C.protos(n = 8)
        val hunter = MobProto(Label("//c:hunter"), "hunter", listOf("hunter"), behaviors = listOf(Pursue(ActorFilter("players"))))
        val g = Fixture(protos = Prototypes(protos.rooms.values.toList(), listOf(hunter), zones = protos.zones.values.toList()))
        val h = g.w.spawnMob(hunter.label, g.w.room(C.r(0)))
        g.player("Bea", at = C.r(6))
        g.runTo(60)
        assertEquals(C.r(6), h.room.label)
    }

    @Test
    fun `scavengers pick up what they can reach, but not corpses`() {
        val f = fixture(mob("magpie", Scavenge))
        val magpie = f.mob(Label("//t:magpie"))
        val hall = f.w.room(T.HALL)
        val corpse = f.w.spawnItem(gups.core.model.Prototypes.CORPSE.label, ItemLocation.InRoom(hall.id, 0))
        val far = f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 4))
        val key = f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 0))
        f.runTo(10)
        assertEquals(ItemLocation.Carried(magpie.id), key.location)
        assertTrue(corpse.location is ItemLocation.InRoom)
        assertTrue(far.location is ItemLocation.InRoom, "out of reach at FAR")
    }

    @Test
    fun `think sees proposals sorted by priority and can override them`() {
        val seen = mutableListOf<List<String>>()
        val proto = mob("thinker", Wander(1, 1.0), Aggressive(ActorFilter("players"))).copy(
            think = ThinkFn { _, _, proposals ->
                seen += proposals.map { it.source }
                ThinkResult.Idle
            },
        )
        val f = fixture(proto)
        val m = f.mob(proto.label)
        f.player("Ann")
        f.run()
        assertEquals(listOf("aggressive", "wander"), seen.first())
        assertFalse(m.isEngaged)
        assertEquals(T.HALL, m.room.label)
    }
}
