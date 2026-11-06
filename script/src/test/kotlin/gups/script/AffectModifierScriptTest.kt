package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.rules.RulesContext
import gups.core.world.spawnItem
import gups.core.world.toHitChain
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AffectModifierScriptTest {
  @Test
  fun `affect modifier on defend_hit reads ctx affect power`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _mod(ctx, base):
                return base - ctx.affect.power // 10

            def _buff(ctx):
                ctx.apply_affect(ctx.actor, "ward", 10, modifiers = {"defend_hit": _mod})

            room("start")
            mob("rat")
            item("wand", commands = {"buff": _buff})
            """,
        )
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))
    f.input(p, "buff")
    f.run()

    // Trigger defend_hit on p (attacker is rat, defender is p)
    val rulesCtx = RulesContext(f.w, actor = rat, target = p)
    val result = f.w.toHitChain(rulesCtx).value
    assertEquals(50, result) // 60 base - 10
  }

  @Test
  fun `room modifier at defend_hit sees ctx affect as None`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _room_mod(ctx, base):
                if ctx.affect != None:
                    return base - 999
                return base - 5

            room("start", modifiers = {"defend_hit": _room_mod})
            mob("rat")
            """,
        )
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val rulesCtx = RulesContext(f.w, actor = rat, target = p)
    val result = f.w.toHitChain(rulesCtx).value
    assertEquals(55, result) // 60 base - 5
  }

  @Test
  fun `item instance affect modifier sees its affect and prototype item affect modifier sees None`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _inst_mod(ctx, base):
                if ctx.affect != None and ctx.affect.name == "keen":
                    return base - 10
                return base

            def _proto_mod(ctx, base):
                if ctx.affect != None:
                    return base - 999
                return base - 5

            def _enchant(ctx):
                ctx.apply_affect(ctx.item, "keen", 10, modifiers = {"defend_hit": _inst_mod})

            room("start")
            mob("rat")
            item("ring", wear = "finger_l", affects = [item_affect("proto_guard", modifiers = {"defend_hit": _proto_mod})], commands = {"enchant": _enchant})
            """,
        )
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val ring = f.w.spawnItem(Label("//zones/z:ring"), ItemLocation.Carried(p.id))
    f.input(p, "wear ring")
    f.run()
    f.input(p, "enchant ring")
    f.run()

    val rulesCtx = RulesContext(f.w, actor = rat, target = p)
    val result = f.w.toHitChain(rulesCtx).value
    assertEquals(45, result) // 60 base - 10 (inst) - 5 (proto)
  }

  @Test
  fun `ctx summon spawns count mobs beside caster with summonedBy set`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _cast_summon(ctx):
                mobs = ctx.summon(ctx.actor, "//zones/z:dog", count = 2)
                ctx.send(ctx.actor, "summoned %d" % len(mobs))

            room("start")
            mob("dog")
            item("horn", commands = {"blow": _cast_summon})
            """,
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:horn"), ItemLocation.Carried(p.id))
    f.input(p, "blow")
    f.run()
    assertContains(f.out(p), "summoned 2")
    val dogs = f.w.actors.filter { it.room == p.room && it.name == "dog" }
    assertEquals(2, dogs.size)
    for (d in dogs) {
      assertEquals(p.id, (d as gups.core.model.Mob).summonedBy)
    }
  }

  @Test
  fun `ctx summon with duration fades when affect ends`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _cast_summon(ctx):
                ctx.summon(ctx.actor, "//zones/z:dog", count = 1, duration = 2)

            room("start")
            mob("dog")
            item("horn", commands = {"blow": _cast_summon})
            """,
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:horn"), ItemLocation.Carried(p.id))
    f.input(p, "blow")
    f.run()
    val dog = f.w.actors.single { it.room == p.room && it.name == "dog" }
    assertNotNull(dog)
    assertEquals(f.w.pulse + 2, dog.affects.single { it.name == "summoned" }.expiresAt)

    // Run 3 pulses; mob should expire and fade
    f.run(pulses = 3)
    val gone = f.w.actors.none { it.name == "dog" }
    assertEquals(true, gone)
  }

  @Test
  fun `ctx summon validates arguments`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _test_unknown(ctx):
                ctx.summon(ctx.actor, "//zones/z:unknown")

            def _test_count_0(ctx):
                ctx.summon(ctx.actor, "//zones/z:dog", count = 0)

            def _test_duration_0(ctx):
                ctx.summon(ctx.actor, "//zones/z:dog", duration = 0)

            room("start")
            mob("dog")
            item("horn", commands = {
                "t1": _test_unknown,
                "t2": _test_count_0,
                "t3": _test_duration_0,
            })
            """,
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:horn"), ItemLocation.Carried(p.id))

    f.input(p, "t1")
    f.run()
    assertContains(f.out(p), "Nothing happens.")
    assertContains(f.w.diagnostics.last().message, "there is no mob")

    f.input(p, "t2")
    f.run()
    assertContains(f.w.diagnostics.last().message, "must be at least 1")

    f.input(p, "t3")
    f.run()
    assertContains(f.w.diagnostics.last().message, "must be at least 1")
  }

  @Test
  fun `room before_summon veto stops ctx summon and returns empty list`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _veto_summon(ctx):
                return ctx.deny("The ground rejects your call.")

            def _cast_summon(ctx):
                mobs = ctx.summon(ctx.actor, "//zones/z:dog", count = 2)
                ctx.send(ctx.actor, "summoned %d" % len(mobs))

            room("start", before_summon = _veto_summon)
            mob("dog")
            item("horn", commands = {"blow": _cast_summon})
            """,
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:horn"), ItemLocation.Carried(p.id))
    f.input(p, "blow")
    f.run()

    val out = f.out(p)
    assertContains(out, "The ground rejects your call.")
    assertContains(out, "summoned 0")
    assertEquals(0, f.w.actors.count { it.name == "dog" })
  }

  @Test
  fun `before_summon sees label and count and on_summon sees targets in spawn order`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _observer_before(ctx):
                ctx.send(ctx.actor, "before:%s:%d" % (ctx.label, ctx.count))

            def _observer_on(ctx):
                names = [t.name for t in ctx.targets]
                ctx.send(ctx.actor, "on:%s" % ",".join(names))

            room("start", on_summon = _observer_on)
            mob("watcher", before_summon = _observer_before)
            mob("dog")
            def _cast_summon(ctx):
                ctx.summon(ctx.actor, "//zones/z:dog", count = 2)

            item("horn", commands = {"blow": _cast_summon})
            """,
        )
      )
    val p = f.player()
    f.w.spawnMob(Label("//zones/z:watcher"), p.room)
    f.w.spawnItem(Label("//zones/z:horn"), ItemLocation.Carried(p.id))
    f.input(p, "blow")
    f.run()

    val out = f.out(p)
    assertContains(out, "before://zones/z:dog:2")
    assertContains(out, "on:dog,dog")
  }
}
