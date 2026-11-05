package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.changeVital
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ItemPowerResolutionTest {
  private val rulesPkg = "rules/package.star" to TestWorlds.RULES_PACKAGE

  private fun zonePkg() =
    """
    package(title = "Z", api_version = 1, kind = "zone")
    """
      .trimIndent()

  private fun world(combat: String, rooms: String, vararg more: Pair<String, String>) =
    ScriptFixture(
      mapOf(
        rulesPkg,
        "rules/combat.star" to TestWorlds.rules(combat),
        "zones/z/package.star" to zonePkg(),
        "zones/z/rooms.star" to rooms,
      ) + more,
      load = listOf("zones/z", "rules"),
    )

  @Test
  fun `item damage proc at an item_power of 200 doubles fixed-dice damage`() {
    val f =
      world(
        """
        def item_power(ctx):
            return 200
        """
          .trimIndent(),
        """
        room("start")
        item("sword", wear = "wield", dice = "1d1", procs = [
            proc(on = "hit", effect = damage(dice = "10d1"))
        ])
        mob("target", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    val p = f.player()
    val target = f.mob("//zones/z:target")
    f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Equipped(p.id, "wield"))

    f.input(p, "kill target")
    f.run(16)

    // 1 base damage + (10 * 200%) = 21 damage -> hp should drop to 79
    assertEquals(79, target.current("hp"))
  }

  @Test
  fun `item_power modifier on permanent affect reading ctx actor skills raises it`() {
    val f =
      world(
        """
        def item_power(ctx):
            return 100
        """
          .trimIndent(),
        """
        def _artificer_boost(ctx, base):
            return base + ctx.actor.skills.get("//zones/z:artificer", 0)

        room("start")
        item("focus", wear = "held", affects = [
            item_affect("artificer_lore", applies = "worn", modifiers = {"item_power": _artificer_boost})
        ])
        item("rod", wear = "wield", dice = "1d1", procs = [
            proc(on = "hit", effect = damage(dice = "10d1"))
        ])
        skill("artificer", kind = "passive")
        mob("target", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    val p = f.player()
    p.skills[Label("//zones/z:artificer")] = 50
    val target = f.mob("//zones/z:target")
    f.w.spawnItem(Label("//zones/z:focus"), ItemLocation.Equipped(p.id, "held"))
    f.w.spawnItem(Label("//zones/z:rod"), ItemLocation.Equipped(p.id, "wield"))

    f.input(p, "kill target")
    f.run(16)

    // Base 100 + 50 skill bonus = 150 item_power -> 10 * 150% = 15 proc damage + 1 base = 16 damage
    // -> hp 84
    assertEquals(84, target.current("hp"))
  }

  @Test
  fun `ctx event is hit for a hit proc and use for use`() {
    val f =
      world(
        """
        def item_power(ctx):
            if ctx.event == "hit":
                return 200
            elif ctx.event == "use":
                return 300
            return 100
        """
          .trimIndent(),
        """
        room("start")
        item("dagger", wear = "wield", dice = "1d1", procs = [
            proc(on = "hit", effect = damage(dice = "10d1"))
        ])
        item("potion", use_effect = heal(dice = "10d1", vital = "hp"))
        mob("target", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    val p = f.player(hp = 100)
    f.w.changeVital(p, "hp", -50)
    val target = f.mob("//zones/z:target")
    f.w.spawnItem(Label("//zones/z:dagger"), ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(Label("//zones/z:potion"), ItemLocation.Carried(p.id))

    // Hit proc: item_power 200 -> 10 * 200% = 20 damage + 1 base = 21 damage -> hp 79
    f.input(p, "kill target")
    f.run(16)
    assertEquals(79, target.current("hp"))

    // Reset current hp to 50 (counteracting combat regen)
    f.w.changeVital(p, "hp", 50 - p.current("hp"))

    // Use potion: item_power 300 -> 10 * 300% = 30 heal -> hp 50 + 30 = 80
    f.input(p, "use potion")
    f.run()
    assertEquals(80, p.current("hp"))
  }

  @Test
  fun `item instance affect hit proc scales by affect power and item_power and sees ctx affect`() {
    val f =
      world(
        """
        def skill_check(ctx):
            return 100
        def skill_power(ctx):
            return 150
        def item_power(ctx):
            if ctx.affect != None and ctx.affect.name == "brand":
                return 200
            return 100
        """
          .trimIndent(),
        """
        room("start")
        item("axe", wear = "wield", dice = "1d1", procs = [
            proc(on = "hit", effect = damage(dice = "1d1"))
        ])
        skill("flame_brand", target = "item", effect = apply_affect(
            name = "brand",
            duration = 100,
            applies = "worn",
            procs = [proc(on = "hit", effect = damage(dice = "10d1"))]
        ))
        mob("target", vitals = vitals(hp = 200))
        """
          .trimIndent(),
      )
    val p = f.player()
    p.skills[Label("//zones/z:flame_brand")] = 100
    val target = f.mob("//zones/z:target")
    f.w.spawnItem(Label("//zones/z:axe"), ItemLocation.Equipped(p.id, "wield"))

    // Cast flame_brand on axe: skill_power is 150, so affect.power is 150
    f.input(p, "cast flame_brand axe")
    f.run()

    // Now attack:
    // Axe has 2 procs on hit:
    // 1) axe's own proc: ctx.affect == None -> item_power = 100 -> 1 * 100% = 1 damage
    // 2) brand instance affect proc: ctx.affect.name == "brand" -> item_power = 200 ->
    //    power = affect.power (150) * item_power (200) / 100 = 300 -> 10 * 300% = 30 damage
    // Total damage = 1 base + 1 (axe proc) + 30 (brand proc) = 32 damage -> hp 200 - 32 = 168
    f.input(p, "kill target")
    f.run(16)
    assertEquals(168, target.current("hp"))
  }

  @Test
  fun `actor-affect proc runs at affect stored power without calling item_power`() {
    val traces = mutableListOf<String>()
    val f =
      world(
        """
        def skill_check(ctx):
            return 100
        def skill_power(ctx):
            return 150
        def item_power(ctx):
            return 999
        """
          .trimIndent(),
        """
        room("start")
        item("dagger", wear = "wield", dice = "1d1")
        skill("thorns", target = "self", effect = apply_affect(
            name = "spikes",
            duration = 100,
            procs = [proc(on = "hit", effect = damage(dice = "10d1"))]
        ))
        mob("target", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    f.w.onRules = { _, line -> traces += line }
    val p = f.player()
    p.skills[Label("//zones/z:thorns")] = 100
    val target = f.mob("//zones/z:target")
    f.w.spawnItem(Label("//zones/z:dagger"), ItemLocation.Equipped(p.id, "wield"))

    // Cast thorns on self: skill_power 150 -> spikes affect has power 150
    f.input(p, "cast thorns")
    f.run()
    traces.clear()

    // Attack target unarmed (1 base damage) + spikes proc (10d1 at 150% = 15 damage) = 16 damage
    f.input(p, "kill target")
    f.run(16)

    assertFalse(
      traces.any { "rules.item_power" in it },
      "item_power must not be called for actor-affect proc: $traces",
    )
    assertEquals(84, target.current("hp"))
  }

  @Test
  fun `skill proc resolve sees ctx power and never calls skill_check`() {
    val traces = mutableListOf<String>()
    val f =
      world(
        """
        def skill_check(ctx):
            return 0
        def item_power(ctx):
            return 175
        """
          .trimIndent(),
        """
        def _burst_resolve(ctx):
            ctx.send(ctx.actor, "PROC_RESOLVE_POWER:" + str(ctx.power))

        room("start")
        skill("burst", target = "enemy", resolve = _burst_resolve)
        item("wand", wear = "wield", dice = "1d1", procs = [
            proc(on = "hit", skill = ":burst")
        ])
        mob("target", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    f.w.onRules = { _, line -> traces += line }
    val p = f.player()
    val target = f.mob("//zones/z:target")
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Equipped(p.id, "wield"))

    f.input(p, "kill target")
    f.run(16)

    assertFalse(
      traces.any { "rules.skill_check" in it },
      "skill proc must not call skill_check: $traces",
    )
    assertContains(f.out(p), "PROC_RESOLVE_POWER:175")
  }

  @Test
  fun `use_effect heal at item_power 150 heals 1_5x`() {
    val f =
      world(
        """
        def item_power(ctx):
            return 150
        """
          .trimIndent(),
        """
        room("start")
        item("potion", use_effect = heal(dice = "10d1", vital = "hp"))
        """
          .trimIndent(),
      )
    val p = f.player(hp = 100)
    f.w.changeVital(p, "hp", -50)
    f.w.spawnItem(Label("//zones/z:potion"), ItemLocation.Carried(p.id))

    f.input(p, "use potion")
    f.run()

    // 10 * 150% = 15 heal -> 50 + 15 = 65
    assertEquals(65, p.current("hp"))
  }

  @Test
  fun `on_use only item does not call item_power`() {
    val traces = mutableListOf<String>()
    val f =
      world(
        """
        def item_power(ctx):
            return 100
        """
          .trimIndent(),
        """
        def _on_use(ctx):
            ctx.send(ctx.actor, "ON_USE_RAN")

        room("start")
        item("bell", on_use = _on_use)
        """
          .trimIndent(),
      )
    f.w.onRules = { _, line -> traces += line }
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:bell"), ItemLocation.Carried(p.id))

    f.input(p, "use bell")
    f.run()

    assertContains(f.out(p), "ON_USE_RAN")
    assertFalse(
      traces.any { "rules.item_power" in it },
      "item_power must not be called when item has no use_effect: $traces",
    )
  }
}
