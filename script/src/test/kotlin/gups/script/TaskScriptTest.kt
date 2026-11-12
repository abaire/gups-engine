package gups.script

import gups.core.model.DamageType
import gups.core.world.AffectSpec
import gups.core.world.applyAffect
import gups.core.world.damage
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ctx.start_task`, `ctx.stop_task`, `ctx.task` and the actor view's `task` and `is_busy`. */
class TaskScriptTest {
  /** A room whose commands start and inspect tasks, and a lib command that does the same. */
  private val ZONE =
    """
    def _done(ctx, gift = 0):
        ctx.send(ctx.actor, "DONE:%s:%d:%d:%s" % (ctx.task.name, gift, ctx.task.remaining, ctx.self.name))

    def _stopped(ctx, reason, gift = 0):
        ctx.send(ctx.actor, "STOP:%s:%s:%d" % (reason, ctx.task.name, gift))

    def _pray(ctx):
        err = ctx.start_task(ctx.actor, 3, _done, name = "praying", lag = 2, on_stop = _stopped, gift = 7)
        ctx.send(ctx.actor, "START:%s" % err)

    def _steady(ctx):
        err = ctx.start_task(ctx.actor, 3, _done, name = "holding steady",
                             interrupt = {"stun": False, "damage_pct": 10}, on_stop = _stopped)
        ctx.send(ctx.actor, "START:%s" % err)

    def _quit_task(ctx):
        ctx.send(ctx.actor, "STOPPED:%s" % ctx.stop_task(ctx.actor))

    def _bad(ctx):
        if ctx.args == "pulses":
            ctx.start_task(ctx.actor, 0, _done)
        elif ctx.args == "key":
            ctx.start_task(ctx.actor, 3, _done, interrupt = {"stun": True, "loud": 1})
        elif ctx.args == "pct":
            ctx.start_task(ctx.actor, 3, _done, interrupt = {"damage_pct": 0})
        elif ctx.args == "arg":
            ctx.start_task(ctx.actor, 3, _done, who = ctx.actor)

    def _later(ctx):
        for a in ctx.self.actors():
            ctx.start_task(a, 2, _done, name = "dozing")

    def _timer(ctx):
        ctx.after(1, _later)

    room("start", commands = {"pray": _pray, "steady": _steady,
                              "quittask": _quit_task, "bad": _bad, "timer": _timer})
    """

  private val LIB =
    """
    def _done(ctx, n = 0):
        ctx.send(ctx.actor, "LIBDONE:%d" % n)

    def _meditate(ctx):
        ctx.send(ctx.actor, "LIBSTART:%s" % ctx.start_task(ctx.actor, 2, _done, name = "meditating", n = 3))

    def _peek(ctx):
        t = ctx.actor.task
        ctx.send(ctx.actor, "PEEK:%s:%s:%s" % (ctx.actor.is_busy, t.name if t else None,
                                             t.interrupt["stun"] if t else None))

    global_command("meditate", _meditate, lane = "tick")
    global_command("rush", _meditate, lane = "fast")
    global_command("peek", _peek, lane = "fast")
    """

  private fun fixture(rules: String = "") =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to ZONE,
        "lib/package.star" to libPkg(),
        "lib/a.star" to LIB,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(rules),
      ),
      load = listOf("zones/z", "lib"),
    )

  private fun ScriptFixture.say(p: gups.core.model.Player, line: String, pulses: Int = 1): String {
    input(p, line)
    run(pulses)
    return out(p)
  }

  @Test
  fun `a room command starts a task whose done gets its kwargs, the task and the starter's self`() {
    val f = fixture()
    val p = f.player()
    assertContains(f.say(p, "pray"), "START:None")
    assertContains(f.say(p, "peek"), "PEEK:True:praying:True")
    val out = f.say(p, "look", pulses = 4)
    assertContains(out, "DONE:praying:7:0:")
    assertContains(f.say(p, "peek", pulses = 4), "PEEK:False:None:None")
  }

  @Test
  fun `a global command and a timer start tasks too`() {
    val f = fixture()
    val p = f.player()
    assertContains(f.say(p, "meditate"), "LIBSTART:None")
    assertContains(f.say(p, "look", pulses = 3), "LIBDONE:3")
    f.say(p, "timer", pulses = 2)
    assertContains(f.say(p, "peek"), "PEEK:True:dozing:True")
    f.run(3)
    assertContains(f.out(p), "DONE:dozing")
  }

  @Test
  fun `a busy actor gets the refusal string`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "pray")
    assertContains(f.say(p, "rush"), "LIBSTART:You're busy.")
  }

  @Test
  fun `stop cancels and on_stop hears the reason and the kwargs`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "pray")
    val out = f.say(p, "stop")
    assertContains(out, "You stop praying.")
    assertContains(out, "STOP:cancelled:praying:7")
    assertEquals(0, p.waitState)
  }

  @Test
  fun `stop_task cancels and returns whether there was one`() {
    val f = fixture()
    val p = f.player()
    assertContains(f.say(p, "quittask"), "STOPPED:False")
  }

  @Test
  fun `a stun interrupts, and on_stop hears it`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "pray")
    f.w.applyAffect(p, AffectSpec("stun", 2, flags = setOf("stunned")))
    f.run()
    val out = f.out(p)
    assertContains(out, "You are interrupted!")
    assertContains(out, "STOP:interrupted:praying:7")
  }

  @Test
  fun `bad arguments are script errors naming start_task`() {
    val f = fixture()
    val p = f.player()
    for ((arg, needle) in
      listOf(
        "pulses" to "start_task(pulses)",
        "key" to "start_task(interrupt)",
        "pct" to "start_task(interrupt)",
        "arg" to "start_task(who)",
      )) {
      val before = f.diagnostics("script_error").size
      f.say(p, "bad $arg")
      val new = f.diagnostics("script_error").drop(before).map { it.message }
      assertTrue(new.any { needle in it }, "$arg: $new")
      assertFalse(p.isBusy, arg)
    }
  }

  @Test
  fun `the template interrupt_check reads the task's damage_pct`() {
    val f = fixture()
    val p = f.player(hp = 50)
    f.say(p, "steady")
    f.w.damage(p, mapOf("hp" to 2), DamageType("slash")) // 4%: below 10
    assertTrue(p.isBusy)
    f.w.damage(p, mapOf("hp" to 5), DamageType("slash")) // 10%
    assertFalse(p.isBusy)
    f.run()
    assertContains(f.out(p), "STOP:interrupted:holding steady")
  }

  @Test
  fun `a rules interrupt_check sees ctx task and no skill`() {
    val f =
      fixture(
        """
        def interrupt_check(ctx, amount):
            t = ctx.task
            return ctx.skill == None and t.name == "praying" and t.ends_at - t.started_at == 3 and t.remaining > 0
        """
      )
    val p = f.player()
    f.say(p, "pray")
    f.w.damage(p, mapOf("hp" to 1), DamageType("slash"))
    assertFalse(p.isBusy, "the rule saw the task and broke it")
  }
}
