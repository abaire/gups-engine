package gups.script

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.world.HookContext
import gups.core.world.HookName
import gups.core.world.HookOutcome
import gups.core.world.ScriptHost
import gups.core.world.World
import gups.core.world.awardXp
import gups.core.world.spawnItem
import gups.core.world.teleport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hook catalog's `ctx_fields` against what the engine really sets: every hook fires here, the
 * fields it gets must be ones the catalog lists, and each listed field must turn up.
 */
class HookCtxFieldsTest {
  /** Records every hook call the world makes, local and global. */
  private class Spy(private val inner: ScriptHost) : ScriptHost by inner {
    val calls = mutableListOf<Pair<HookName, HookContext>>()

    override fun invokeHook(
      world: World,
      owner: Entity,
      hook: HookName,
      ctx: HookContext,
    ): HookOutcome {
      calls += hook to ctx
      return inner.invokeHook(world, owner, hook, ctx)
    }

    override fun invokeGlobal(world: World, hook: HookName, ctx: HookContext) {
      calls += hook to ctx
      inner.invokeGlobal(world, hook, ctx)
    }
  }

  /**
   * The `ctx` fields a call sets: those of [HookContext] it fills, plus the ones every hook has.
   */
  private fun fieldsSet(hook: HookName, hc: HookContext): Set<String> {
    val out = sortedSetOf("self", "room", "pulse", "rng")
    for (f in HookContext::class.java.declaredFields) {
      f.isAccessible = true
      val v = f.get(hc) ?: continue
      if (v is Map<*, *> && v.isEmpty()) continue
      // ctx.target is the target actor or, failing that, the target item (Ctx.target).
      out +=
        if (f.name == "targetItem") "target"
        else f.name.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() }
    }
    if (hook == HookName.ON_COMBAT_TICK) out += "targets"
    return out
  }

  private fun hooks(kind: OwnerKind): String =
    HookCatalog.specs
      .filter { kind in it.attachTo }
      .joinToString(", ") { "${it.name} = ${if (it.hook == HookName.ON_WAKE) "_wake" else "_h"}" }

  @Test
  fun `every hook gets only the ctx fields the catalog lists`() {
    lateinit var spy: Spy
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(", reset_world_ticks = 1"),
          "zones/z/a.star" to
            """
                    def _h(ctx):
                        pass

                    def _wake(ctx, elapsed_pulses):
                        pass

                    room("start", exits = [exit("north", ":hall"), exit("east", ":vault", door = door(state = "locked", key = ":key"))], spawns = [spawn(":wolf")], ${hooks(OwnerKind.ROOM)})
                    room("hall", exits = [exit("south", ":start")], ${hooks(OwnerKind.ROOM)})
                    room("vault", exits = [exit("west", ":start", door = door(state = "locked", key = ":key"))], ${hooks(OwnerKind.ROOM)})
                    room("far", ${hooks(OwnerKind.ROOM)})
                    mob("wolf", vitals = vitals(hp = 8), senses = {"sound": 1, "sight": 1}, ${hooks(OwnerKind.MOB)})
                    item("rock", kind = "melee", wear = "wield", dice = "1d2", ${hooks(OwnerKind.ITEM)})
                    item("cup", ${hooks(OwnerKind.ITEM)})
                    item("key", kind = "key")
                    item("chest", container = container(state = "locked", key = ":key"), ${hooks(OwnerKind.ITEM)})
                    item("lead", weight = 80)
                    skill("zap", range = "far", warmup = 1, effect = damage(dice = "1d2"))
                """,
        ),
        bubble = true,
        wrap = { host -> Spy(host).also { spy = it } },
      )
    val p = f.player(hp = 500)
    p.skills[Label("//zones/z:zap")] = 100
    f.w.spawnItem(Label("//zones/z:rock"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:cup"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:key"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:chest"), ItemLocation.Carried(p.id))
    // A heavy load, so the encumbrance level changes and the global hook fires.
    f.w.spawnItem(Label("//zones/z:lead"), ItemLocation.Carried(p.id))
    f.run()
    for (line in
      listOf(
        "say hello",
        "tell wolf hi",
        "drop cup",
        "get cup",
        "use cup wolf",
        "give cup wolf",
        "wield rock",
        "unlock east",
        "open east",
        "close east",
        "lock east",
        "unlock chest",
        "open chest",
        "close chest",
        "lock chest",
        "north",
        "south",
        "cast zap wolf",
      )) {
      f.input(p, line)
      f.run(3)
    }
    f.input(p, "kill wolf")
    f.run(60)
    f.w.awardXp(p, 1000)
    // on_tick and zone resets come with the world tick.
    f.run(WORLD_TICK_PULSES)
    val far = f.w.room(Label("//zones/z:far"))
    assertTrue(far.isDormant)
    f.w.teleport(p, far)
    f.run()

    val seen =
      spy.calls.groupBy({ it.first }, { fieldsSet(it.first, it.second) }).mapValues { (_, sets) ->
        sets.flatten().toSortedSet()
      }
    val expected = HookName.entries.toSet()
    assertEquals(expected, seen.keys, "hooks that never fired: ${expected - seen.keys}")
    val problems =
      HookCatalog.specs.flatMap { spec ->
        val listed = spec.ctxFields.toSet()
        val got = seen.getValue(spec.hook)
        listOfNotNull(
          (got - listed)
            .takeIf { it.isNotEmpty() }
            ?.let { "${spec.name} sets ${it.joinToString()}, which its ctx_fields don't list" },
          (listed - got)
            .takeIf { it.isNotEmpty() }
            ?.let { "${spec.name} lists ${it.joinToString()}, which it never sets here" },
        )
      }
    assertTrue(problems.isEmpty(), problems.joinToString("\n"))
  }

  @Test
  fun `old_level and new_level are None outside on_level_change`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def _check(ctx):
                if ctx.old_level != None or ctx.new_level != None:
                    fail("expected None")
                ctx.send(ctx.actor, "ok")

            room("start", commands = {"check": _check})
            """,
        )
      )
    val p = f.player()
    f.input(p, "check")
    f.run(1)
    assertContains(f.out(p), "ok")
  }
}
