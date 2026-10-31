package gups.script

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.world.HookContext
import gups.core.world.HookName
import gups.core.world.HookOutcome
import gups.core.world.ScriptHost
import gups.core.world.World
import gups.core.world.awardXp
import gups.core.world.changeVital
import gups.core.world.levelUp
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LevelChangeHookTest {
  /** The `tag:old->new` lines in [out], in the order they were sent. */
  private fun changes(out: String, tag: String): List<String> =
    Regex("\\b$tag:\\d+->\\d+").findAll(out).map { it.value }.toList()

  private val linearCurve =
    """def xp_to_level(ctx, level):
    return 1000 * level
"""

  private val combatRules =
    """def xp_to_level(ctx, level):
    return 1000 * level

def xp_for_kill(ctx):
    return 2500

def to_hit(ctx):
    return 100

def damage(ctx):
    return 10
"""

  private val deathRules =
    """def xp_to_level(ctx, level):
    return 1000 * level

def on_player_death(ctx):
    return {"respawn": ctx.actor.recall_room, "vitals": vitals(hp = 10), "level_loss": 2}
"""

  private fun fixture(
    vararg files: Pair<String, String>,
    wrap: (ScriptHost) -> ScriptHost = { it },
  ): ScriptFixture =
    ScriptFixture(
      mapOf("zones/z/package.star" to zonePkg(), "rules/package.star" to TestWorlds.RULES_PACKAGE) +
        files,
      wrap = wrap,
    )

  @Test
  fun `it fires once per step, in order, for award_xp, and never for take_xp or a plain XP gain`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _h(ctx):
              ctx.send(ctx.actor, "change:%s->%s" % (ctx.old_level, ctx.new_level))

          def _small(ctx):
              ctx.award_xp(ctx.actor, 100)

          def _big(ctx):
              ctx.award_xp(ctx.actor, 2500)

          def _take(ctx):
              ctx.take_xp(ctx.actor, 50)

          room("start", on_level_change = _h, commands = {
              "small": _small,
              "big": _big,
              "take": _take,
          })
          """,
        "rules/combat.star" to TestWorlds.rules(linearCurve),
      )
    val p = f.player()
    f.out(p)

    f.input(p, "small")
    f.run()
    assertEquals(1, p.level)
    assertEquals(100L, p.xp)
    assertEquals(emptyList(), changes(f.out(p), "change"), "a plain XP gain must not fire it")

    f.input(p, "take")
    f.run()
    assertEquals(1, p.level)
    assertEquals(50L, p.xp)
    assertEquals(emptyList(), changes(f.out(p), "change"), "take_xp must not fire it")

    // 50 + 2500 crosses the thresholds for levels 2 (1000) and 3 (2000).
    f.input(p, "big")
    f.run()
    assertEquals(3, p.level)
    assertEquals(listOf("change:1->2", "change:2->3"), changes(f.out(p), "change"))
  }

  @Test
  fun `it fires once per step, in order, for a kill, and never reaches the victim or its belongings`() {
    val owners = mutableListOf<String>()
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _h(ctx):
              ctx.send(ctx.actor, "kill_change:%s->%s" % (ctx.old_level, ctx.new_level))

          room("start", on_level_change = _h)
          mob("target", vitals = vitals(hp = 1), on_level_change = _h)
          item("loot", on_level_change = _h)
          """,
        "rules/combat.star" to TestWorlds.rules(combatRules),
        wrap = { inner ->
          object : ScriptHost by inner {
            override fun invokeHook(
              world: World,
              owner: Entity,
              hook: HookName,
              ctx: HookContext,
            ): HookOutcome {
              if (hook == HookName.ON_LEVEL_CHANGE) owners += owner.name
              return inner.invokeHook(world, owner, hook, ctx)
            }
          }
        },
      )
    val p = f.player()
    f.out(p)

    val dummy = f.mob("//zones/z:target")
    val loot = f.w.spawnItem(Label("//zones/z:loot"), ItemLocation.Carried(dummy.id))
    f.w.startCombat(p, dummy)
    f.run(60)

    assertFalse(dummy.inWorld)
    // A kill worth 2500 crosses the thresholds for levels 2 (1000) and 3 (2000).
    assertEquals(3, p.level)
    assertEquals(listOf("kill_change:1->2", "kill_change:2->3"), changes(f.out(p), "kill_change"))
    assertFalse(dummy.name in owners, "the dead victim must not hear the killer's level change")
    assertFalse(loot.name in owners, "the victim's belongings must not hear it either")
  }

  @Test
  fun `it fires once per step, in order, for the level_up and level_down verbs`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _h(ctx):
              ctx.send(ctx.actor, "step:%s->%s" % (ctx.old_level, ctx.new_level))

          def _up(ctx):
              ctx.level_up(ctx.actor)

          def _down(ctx):
              ctx.level_down(ctx.actor, 2)

          room("start", on_level_change = _h, commands = {"train": _up, "drain_me": _down})
          """,
        "MODULE.gups" to TestWorlds.module("level_mode = \"deferred\""),
        "rules/combat.star" to TestWorlds.rules(linearCurve),
      )
    val p = f.player()
    p.xp = 2500L // Owed levels 2 (1000) and 3 (2000).
    f.out(p)

    f.input(p, "train")
    f.run()
    assertEquals(2, p.level)
    assertEquals(listOf("step:1->2"), changes(f.out(p), "step"))

    f.input(p, "train")
    f.run()
    assertEquals(3, p.level)
    assertEquals(listOf("step:2->3"), changes(f.out(p), "step"))

    f.input(p, "drain_me")
    f.run()
    assertEquals(1, p.level)
    assertEquals(listOf("step:3->2", "step:2->1"), changes(f.out(p), "step"))
  }

  @Test
  fun `it fires once per step, in order, for level_loss at death, in the respawn room`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _died_here(ctx):
              ctx.send(ctx.actor, "died_here:%s->%s" % (ctx.old_level, ctx.new_level))

          def _respawned(ctx):
              ctx.send(ctx.actor, "respawned:%s->%s" % (ctx.old_level, ctx.new_level))

          room("start", on_level_change = _died_here)
          room("temple", on_level_change = _respawned)
          """,
        "rules/combat.star" to TestWorlds.rules(deathRules),
      )
    val p = f.player(hp = 10)
    p.recallRoom = Label("//zones/z:temple")
    p.level = 3
    p.xp = 3500L
    f.out(p)

    f.w.changeVital(p, "hp", -1000)
    f.run()

    assertEquals(1, p.level)
    assertSame(f.w.room(Label("//zones/z:temple")), p.room)
    val out = f.out(p)
    assertEquals(listOf("respawned:3->2", "respawned:2->1"), changes(out, "respawned"))
    assertEquals(emptyList(), changes(out, "died_here"))
  }

  @Test
  fun `it reaches the actor's own mob hook, the room, observers and carried and equipped items in order`() {
    val order = mutableListOf<String>()
    val host =
      object : ScriptHost {
        override fun invokeHook(
          world: World,
          owner: Entity,
          hook: HookName,
          ctx: HookContext,
        ): HookOutcome {
          if (hook == HookName.ON_LEVEL_CHANGE) {
            order += owner.name
          }
          return HookOutcome.Allow
        }

        override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext) = Unit
      }

    val f =
      fixture(
        "zones/z/a.star" to
          """
          room("start")
          mob("target")
          mob("observer")
          item("ground")
          item("tc")
          item("te", wear = "wield")
          item("oc")
          item("oe", wear = "wield")
          item("box", container = container())
          item("nested")
          """,
        wrap = { host },
      )
    val startRoom = f.w.room(Label("//zones/z:start"))

    val targetMob = f.w.spawnMob(Label("//zones/z:target"), startRoom)
    targetMob.level = 1
    targetMob.xp = 1000L

    val observerMob = f.w.spawnMob(Label("//zones/z:observer"), startRoom)

    val groundItem = f.w.spawnItem(Label("//zones/z:ground"), ItemLocation.InRoom(startRoom.id, 0))

    val targetCarried = f.w.spawnItem(Label("//zones/z:tc"), ItemLocation.Carried(targetMob.id))
    val targetEquipped =
      f.w.spawnItem(Label("//zones/z:te"), ItemLocation.Equipped(targetMob.id, "wield"))

    val observerCarried = f.w.spawnItem(Label("//zones/z:oc"), ItemLocation.Carried(observerMob.id))
    val observerEquipped =
      f.w.spawnItem(Label("//zones/z:oe"), ItemLocation.Equipped(observerMob.id, "wield"))

    // Also place an item inside a container carried by targetMob: it must not receive
    // on_level_change.
    val container = f.w.spawnItem(Label("//zones/z:box"), ItemLocation.Carried(targetMob.id))
    val nestedItem = f.w.spawnItem(Label("//zones/z:nested"), ItemLocation.Contained(container.id))

    assertTrue(f.w.levelUp(targetMob))
    assertEquals(2, targetMob.level)

    // Expected order:
    // 1. targetMob (actor's own mob hook)
    // 2. startRoom (the room)
    // 3. observerMob (other actors in the room)
    // 4. groundItem (items in the room)
    // 5. targetMob's carried & equipped items (targetCarried, targetEquipped, container) in ID
    // order
    // 6. observerMob's carried & equipped items (observerCarried, observerEquipped) in ID order
    // Nested item inside container is NOT included.

    assertFalse(
      nestedItem.name in order,
      "items inside carried containers must not receive on_level_change",
    )

    val expectedTargetItems =
      listOf(targetCarried, targetEquipped, container).sortedBy { it.id }.map { it.name }
    val expectedObserverItems =
      listOf(observerCarried, observerEquipped).sortedBy { it.id }.map { it.name }

    val expectedOrder =
      listOf(targetMob.name, startRoom.name, observerMob.name, groundItem.name) +
        expectedTargetItems +
        expectedObserverItems

    assertEquals(expectedOrder, order)
  }

  @Test
  fun `a hook that calls award_xp is cut off at depth 8 without a hang`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _cascade(ctx):
              ctx.award_xp(ctx.actor, 1000)

          room("start", on_level_change = _cascade)
          """,
        "rules/combat.star" to TestWorlds.rules(linearCurve),
      )
    val p = f.player()
    p.level = 1
    p.xp = 0L

    // Each level-up's hook awards the next level from inside the previous dispatch.
    f.w.awardXp(p, 1000)

    val cascadeDiagnostics = f.diagnostics("cascade")
    assertTrue(cascadeDiagnostics.isNotEmpty(), "expected a cascade diagnostic")
    assertTrue(cascadeDiagnostics.any { "on_level_change" in it.message })
    // The award reaches level 2. The hooks at depths 0 to 7 each add one more level, reaching 10;
    // the dispatch that level 10 would trigger at depth 8 is dropped.
    assertEquals(10, p.level)
  }
}
