package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.Aggressive
import gups.core.world.Flee
import gups.core.world.Guard
import gups.core.world.spawnItem
import gups.core.world.teleport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M5ScriptTest {
    private fun zone(rooms: String, vararg more: Pair<String, String>) =
        mapOf("zones/z/package.star" to zonePkg(), "zones/z/rooms.star" to rooms) + more

    private fun load(files: Map<String, String>, vararg pkgs: String = arrayOf("zones/z")) =
        ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

    @Test
    fun `modifier, flag and behavior declarations are checked`() {
        val point = load(zone("def f(ctx, base):\n    return base\nroom(\"start\", modifiers = {\"to_hti\": f})"))
        assertContains(point.report(), "'to_hti' is not a modifier point (did you mean 'to_hit'?)")
        val unworn = load(zone("item(\"ring\", equip_affects = [\"no_regen\"])"))
        assertContains(unworn.report(), "needs a wear slot")
        val pattern = load(zone("mob(\"rat\", behaviors = [aggressive(toward = \"Bad Pattern!\")])"))
        assertContains(pattern.report(), "is not players, mobs, all, or a label pattern")
        val ok = load(
            zone(
                """
                room("start")
                mob("rat", behaviors = [aggressive(toward = [":goblin_*", "players"]), flee(at_hp_pct = 30), guard("north")])
                """,
            ),
        )
        assertTrue(ok.ok, ok.report())
        val behaviors = ok.prototypes().mob(Label("//zones/z:rat")).behaviors
        assertEquals(listOf("//zones/z:goblin_*", "players"), (behaviors[0] as Aggressive).toward.patterns)
        assertEquals(30, (behaviors[1] as Flee).atHpPct)
        assertEquals("north", (behaviors[2] as Guard).exit.name)
    }

    @Test
    fun `a region name belongs to one package`() {
        val r = load(
            zone("room(\"start\")\nregion(\"swamp\")") + mapOf(
                "zones/y/package.star" to zonePkg(),
                "zones/y/rooms.star" to "room(\"start\")\nregion(\"swamp\")",
            ),
            "zones/z", "zones/y",
        )
        assertEquals(setOf("zones/y", "zones/z"), r.failed)
        assertContains(r.report(), "region 'swamp' is declared by more than one package")
    }

    @Test
    fun `on_nearby sees the event, its room and the mob's own exit toward it`() {
        val f = ScriptFixture(
            zone(
                """
                def _heard(ctx):
                    if ctx.actor:
                        ctx.send(ctx.actor, "%s: %s from %s via %s: %s" % (ctx.self.name, ctx.event, ctx.source_room.name, ctx.via_exit, ctx.text))

                room("start", exits = [exit("north", ":den")])
                room("den", exits = [exit("south", ":start")])
                mob("wolf", senses = {"hearing": 1}, on_nearby = _heard)
                """,
            ),
        )
        val p = f.player()
        f.mob("//zones/z:wolf", at = "//zones/z:den")
        f.input(p, "say who's there?")
        f.run()
        assertContains(f.out(p), "wolf: say from start via south: who's there?")
    }

    @Test
    fun `on_wake gets the dormant time, and an anchored item keeps its room awake`() {
        val f = ScriptFixture(
            zone(
                """
                def _woke(ctx, elapsed_pulses):
                    ctx.set_attr(ctx.self, "slept", elapsed_pulses)

                def _plant(ctx):
                    ctx.set_anchor(ctx.self, "beacon")

                room("start")
                room("far", on_wake = _woke)
                item("beacon", commands = {"plant": _plant})
                """,
            ),
            bubble = true,
        )
        val p = f.player()
        val far = f.w.room(Label("//zones/z:far"))
        f.run(40)
        assertTrue(far.isDormant)
        f.w.teleport(p, far)
        f.run()
        assertEquals(AttrValue.I(41), far.customAttributes["z:slept"])
        f.w.spawnItem(Label("//zones/z:beacon"), ItemLocation.Carried(p.id))
        f.input(p, "plant")
        f.run()
        f.input(p, "drop beacon")
        f.run()
        f.w.teleport(p, f.w.room(Label("//zones/z:start")))
        f.run(5)
        assertFalse(far.isDormant, "the beacon anchors the far room")
    }

    @Test
    fun `use, give and wear from scripts`() {
        val f = ScriptFixture(
            zone(
                """
                def _drink(ctx):
                    ctx.heal(ctx.actor, 5)
                    ctx.destroy(ctx.self)

                def _no_fight(ctx):
                    if ctx.actor.fighting():
                        return ctx.deny("Not now!")

                def _thanks(ctx):
                    if ctx.target == ctx.self:
                        ctx.say(ctx.self, "Thank you for %s." % ctx.item.name)

                room("start")
                item("tonic", on_use = _drink, before_use = _no_fight)
                item("hat", wear = "head", equip_affects = ["no_regen"], stat_deltas = {"wis": 1})
                mob("beggar", on_give = _thanks)
                """,
            ),
        )
        val p = f.player(hp = 50)
        p.hp = 40
        f.mob("//zones/z:beggar")
        f.w.spawnItem(Label("//zones/z:tonic"), ItemLocation.Carried(p.id))
        f.w.spawnItem(Label("//zones/z:hat"), ItemLocation.Carried(p.id))
        f.input(p, "use tonic")
        f.run()
        assertEquals(45, p.hp)
        assertTrue(p.inventory.none { it.protoLabel.name == "tonic" })
        f.input(p, "wear hat")
        f.run()
        assertTrue(p.hasFlag("no_regen"))
        f.input(p, "remove hat")
        f.run()
        f.input(p, "give hat beggar")
        f.run()
        assertContains(f.out(p), "Thank you for hat.")
        assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
    }

    @Test
    fun `behaviors declared in Starlark drive mobs`() {
        val f = ScriptFixture(
            zone(
                """
                room("start")
                mob("brute", behaviors = [aggressive(toward = "players")])
                """,
            ),
        )
        val brute = f.mob("//zones/z:brute")
        val p = f.player()
        f.run(3)
        assertTrue(p.id in brute.fighting)
    }
}
