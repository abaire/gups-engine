package gups.script

import gups.core.model.LevelGain
import gups.core.model.Player
import gups.core.world.awardXp
import gups.core.world.pendingLevels
import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class LevelingVerbsTest {
  private fun fixture(vararg files: Pair<String, String>): ScriptFixture =
    ScriptFixture(
      mapOf("zones/z/package.star" to zonePkg(), "rules/package.star" to TestWorlds.RULES_PACKAGE) +
        files
    )

  /**
   * Runs [command] and asserts it raised one new script error naming [message] and changed nothing.
   */
  private fun assertRejected(f: ScriptFixture, p: Player, command: String, message: String) {
    val before = f.diagnostics("script_error").count { message in it.message }
    val xp = p.xp
    val level = p.level
    f.input(p, command)
    f.run(1)
    assertEquals(before + 1, f.diagnostics("script_error").count { message in it.message }, command)
    assertEquals(xp, p.xp, command)
    assertEquals(level, p.level, command)
  }

  private fun rejectionFixture(verb: String): Pair<ScriptFixture, Player> {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _zero(ctx):
              $verb(ctx.actor, 0)

          def _neg(ctx):
              $verb(ctx.actor, -5)

          room("start", commands = {"zero": _zero, "neg": _neg})
          """
      )
    val p = f.player()
    p.level = 3
    p.xp = 3500L
    return f to p
  }

  @Test
  fun `award_xp rejects 0 and negatives`() {
    val (f, p) = rejectionFixture("ctx.award_xp")
    assertRejected(f, p, "zero", "award_xp(amount): must be at least 1")
    assertRejected(f, p, "neg", "award_xp(amount): must be at least 1")
  }

  @Test
  fun `take_xp rejects 0 and negatives`() {
    val (f, p) = rejectionFixture("ctx.take_xp")
    assertRejected(f, p, "zero", "take_xp(amount): must be at least 1")
    assertRejected(f, p, "neg", "take_xp(amount): must be at least 1")
  }

  @Test
  fun `level_down rejects a count of 0 and negatives`() {
    val (f, p) = rejectionFixture("ctx.level_down")
    assertRejected(f, p, "zero", "level_down(count): must be at least 1")
    assertRejected(f, p, "neg", "level_down(count): must be at least 1")
  }

  @Test
  fun `take_xp returns the amount actually removed`() {
    val zoneFiles =
      "zones/z/a.star" to
        """
        def _take_some(ctx):
            removed = ctx.take_xp(ctx.actor, 200)
            ctx.send(ctx.actor, "removed: %d, xp: %d" % (removed, ctx.actor.xp))

        def _take_more(ctx):
            removed = ctx.take_xp(ctx.actor, 500)
            ctx.send(ctx.actor, "removed: %d, xp: %d" % (removed, ctx.actor.xp))

        room("start", commands = {
            "take_some": _take_some,
            "take_more": _take_more,
        })
        """

    val f = fixture(zoneFiles)
    val p = f.player()
    p.xp = 500L

    f.input(p, "take_some")
    f.run(1)
    assertContains(f.out(p), "removed: 200, xp: 300")
    assertEquals(300L, p.xp)
    assertEquals(1, p.level)

    f.input(p, "take_more")
    f.run(1)
    assertContains(f.out(p), "removed: 300, xp: 0")
    assertEquals(0L, p.xp)
    assertEquals(1, p.level)
  }

  @Test
  fun `level_down returns the levels actually lost`() {
    val zoneFiles =
      "zones/z/a.star" to
        """
        def _down_2(ctx):
            lost = ctx.level_down(ctx.actor, 2)
            ctx.send(ctx.actor, "lost: %d, level: %d" % (lost, ctx.actor.level))

        def _down_5(ctx):
            lost = ctx.level_down(ctx.actor, 5)
            ctx.send(ctx.actor, "lost: %d, level: %d" % (lost, ctx.actor.level))

        def _down_default(ctx):
            lost = ctx.level_down(ctx.actor)
            ctx.send(ctx.actor, "lost: %d, level: %d" % (lost, ctx.actor.level))

        room("start", commands = {
            "down_2": _down_2,
            "down_5": _down_5,
            "down_default": _down_default,
        })
        """

    val f = fixture(zoneFiles)
    val p = f.player()
    p.level = 4
    p.xp = 7000L

    f.input(p, "down_2")
    f.run(1)
    assertContains(f.out(p), "lost: 2, level: 2")
    assertEquals(2, p.level)

    f.input(p, "down_5")
    f.run(1)
    assertContains(f.out(p), "lost: 1, level: 1")
    assertEquals(1, p.level)

    f.input(p, "down_default")
    f.run(1)
    assertContains(f.out(p), "lost: 0, level: 1")
    assertEquals(1, p.level)
  }

  @Test
  fun `in deferred mode, ctx level_up returns True, applies one level, and sends READY only if another is pending`() {
    val zoneFiles =
      "zones/z/a.star" to
        """
        def _do_level_up(ctx):
            res = ctx.level_up(ctx.actor)
            ctx.send(ctx.actor, "result: %s, level: %d, pending: %d" % (res, ctx.actor.level, ctx.actor.pending_levels))

        def _award(ctx):
            ctx.award_xp(ctx.actor, 3000)

        room("start", commands = {
            "do_level_up": _do_level_up,
            "award": _award,
        })
        """

    val f = fixture(zoneFiles, "MODULE.gups" to TestWorlds.module("level_mode = \"deferred\""))
    val p = f.player()

    // Award 3000 XP via verb in deferred mode: crosses level 1 (1000) and level 2 (3000)
    // thresholds.
    // 2 levels become pending, and READY is sent once.
    f.input(p, "award")
    f.run(1)
    val outAward = f.out(p)
    assertContains(outAward, "You gain 3000 experience.")
    assertContains(outAward, "You feel ready to advance.")
    assertEquals(1, p.level)
    assertEquals(2, f.w.pendingLevels(p))

    // First advance: level 1 -> 2. 1 level remains pending, so READY is sent.
    f.input(p, "do_level_up")
    f.run(1)
    val outAdv1 = f.out(p)
    assertContains(outAdv1, "You have reached level 2!")
    assertContains(outAdv1, "You feel ready to advance.")
    assertContains(outAdv1, "result: True, level: 2, pending: 1")
    assertEquals(2, p.level)

    // Second advance: level 2 -> 3. 0 levels remain pending, so READY is NOT sent.
    f.input(p, "do_level_up")
    f.run(1)
    val outAdv2 = f.out(p)
    assertContains(outAdv2, "You have reached level 3!")
    assertFalse(outAdv2.contains("You feel ready to advance."))
    assertContains(outAdv2, "result: True, level: 3, pending: 0")
    assertEquals(3, p.level)

    // Third advance: none pending. Returns False, no messages.
    f.input(p, "do_level_up")
    f.run(1)
    val outAdv3 = f.out(p)
    assertFalse(outAdv3.contains("You have reached level"))
    assertFalse(outAdv3.contains("You feel ready to advance."))
    assertContains(outAdv3, "result: False, level: 3, pending: 0")
    assertEquals(3, p.level)
  }

  @Test
  fun `a kill, award_xp and a script's level_up all raise levels through World levelUp`() {
    val rulesOverride =
      """
      def on_level_up(ctx):
          return struct(stats = stats(str = 2), max = vitals(hp = 5), current = vitals(hp = 5))

      def xp_for_kill(ctx):
          return 1000

      def to_hit(ctx):
          return 100

      def damage(ctx):
          return 10
      """
    val zoneFiles =
      "zones/z/a.star" to
        """
        def _award(ctx):
            ctx.award_xp(ctx.actor, 2000)

        def _do_level_up(ctx):
            ctx.level_up(ctx.actor)

        room("start", commands = {
            "award": _award,
            "do_level_up": _do_level_up,
        })
        mob("target", vitals = vitals(hp = 1))
        """

    // 1. Auto mode: a kill raises level through World.levelUp.
    val fAuto = fixture(zoneFiles, "rules/combat.star" to TestWorlds.rules(rulesOverride))
    val pAuto = fAuto.player()
    val initialStr = pAuto.stat("str")
    val initialMaxHp = pAuto.baseMax("hp")

    val dummy = fAuto.mob("//zones/z:target")
    fAuto.w.startCombat(pAuto, dummy)
    fAuto.run(60)
    assertFalse(dummy.inWorld)
    assertEquals(2, pAuto.level)
    assertEquals(initialStr + 2, pAuto.stat("str"))
    assertEquals(initialMaxHp + 5, pAuto.baseMax("hp"))
    assertEquals(LevelGain(stats = mapOf("str" to 2), max = mapOf("hp" to 5)), pAuto.levelGains[2])
    assertContains(fAuto.out(pAuto), "You have reached level 2!")

    // 2. Auto mode: ctx.award_xp raises level through World.levelUp.
    fAuto.input(pAuto, "award")
    fAuto.run(1)
    assertEquals(3, pAuto.level)
    assertEquals(initialStr + 4, pAuto.stat("str"))
    assertEquals(initialMaxHp + 10, pAuto.baseMax("hp"))
    assertEquals(LevelGain(stats = mapOf("str" to 2), max = mapOf("hp" to 5)), pAuto.levelGains[3])
    assertContains(fAuto.out(pAuto), "You have reached level 3!")

    // 3. Deferred mode: ctx.level_up raises level through World.levelUp.
    val fDef =
      fixture(
        zoneFiles,
        "MODULE.gups" to TestWorlds.module("level_mode = \"deferred\""),
        "rules/combat.star" to TestWorlds.rules(rulesOverride),
      )
    val pDef = fDef.player()
    val defInitialStr = pDef.stat("str")
    val defInitialMaxHp = pDef.baseMax("hp")

    fDef.w.awardXp(pDef, 1000)
    assertEquals(1, pDef.level)
    assertFalse(pDef.levelGains.containsKey(2))

    fDef.input(pDef, "do_level_up")
    fDef.run(1)
    assertEquals(2, pDef.level)
    assertEquals(defInitialStr + 2, pDef.stat("str"))
    assertEquals(defInitialMaxHp + 5, pDef.baseMax("hp"))
    assertNotNull(pDef.levelGains[2])
    assertEquals(LevelGain(stats = mapOf("str" to 2), max = mapOf("hp" to 5)), pDef.levelGains[2])
    assertContains(fDef.out(pDef), "You have reached level 2!")
  }
}
