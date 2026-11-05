package gups.script

import gups.core.model.Player
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanVerbTest {

  private fun runHook(code: String, seed: Long = 1): Triple<ScriptFixture, Player, String?> {
    val indentedCode = code.trimIndent().prependIndent("    ")
    val fileContent =
      "def _enter(ctx):\n" +
        indentedCode +
        "\n\nroom(\"start\", exits = [exit(\"north\", \":hall\")])\n" +
        "room(\"hall\", on_enter = _enter, exits = [exit(\"south\", \":start\")])\n"
    val f =
      ScriptFixture(
        mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to fileContent),
        seed = seed,
      )
    if (!f.result.ok) {
      throw RuntimeException("Load failed: ${f.result.report()}")
    }
    val p = f.player()
    f.input(p, "north")
    f.run(2)
    val err = f.diagnostics("script_error").firstOrNull()?.message
    return Triple(f, p, err)
  }

  @Test
  fun `small plan returns expected list of names and empty list when goal holds`() {
    val (f, p, err) =
      runHook(
        """
        p1 = ctx.plan(
            {"at": "start"},
            [
                {"name": "step1", "pre": {"at": "start"}, "effects": {"at": "mid"}},
                {"name": "step2", "pre": {"at": "mid"}, "effects": {"at": "goal"}},
            ],
            {"at": "goal"},
        )
        ctx.send(ctx.actor, "P1:" + repr(p1))

        p2 = ctx.plan({"at": "goal"}, [], {"at": "goal"})
        ctx.send(ctx.actor, "P2:" + repr(p2))
        """
      )
    assertEquals(null, err)
    val out = f.out(p)
    assertContains(out, "P1:[\"step1\", \"step2\"]")
    assertContains(out, "P2:[]")
  }

  @Test
  fun `None for unreachable goal and max_expansions 1 on two-step problem with no diagnostics`() {
    val (f, p, err) =
      runHook(
        """
        p1 = ctx.plan({"at": "start"}, [{"name": "act", "effects": {"at": "other"}}], {"at": "unreachable"})
        ctx.send(ctx.actor, "P1:" + repr(p1))

        p2 = ctx.plan(
            {"at": "start"},
            [
                {"name": "step1", "pre": {"at": "start"}, "effects": {"at": "mid"}},
                {"name": "step2", "pre": {"at": "mid"}, "effects": {"at": "goal"}},
            ],
            {"at": "goal"},
            max_expansions = 1,
        )
        ctx.send(ctx.actor, "P2:" + repr(p2))
        """
      )
    assertEquals(null, err)
    val out = f.out(p)
    assertContains(out, "P1:None")
    assertContains(out, "P2:None")
    assertEquals(emptyList(), f.diagnostics())
  }

  @Test
  fun `bool vs int fact values do not match`() {
    val (f, p, err) =
      runHook(
        """
        p = ctx.plan({"a": True}, [{"name": "act", "pre": {"a": 1}, "effects": {"b": True}}], {"b": True})
        ctx.send(ctx.actor, "RES:" + repr(p))
        """
      )
    assertEquals(null, err)
    val out = f.out(p)
    assertContains(out, "RES:None")
  }

  @Test
  fun `errors raise script error starting with ctx dot plan`() {
    val cases =
      listOf(
        // Float fact value
        "ctx.plan({\"x\": 1.5}, [], {\"x\": 1})",
        // List fact value
        "ctx.plan({\"x\": [1]}, [], {\"x\": 1})",
        // Unknown action key
        "ctx.plan({}, [{\"name\": \"a\", \"unknown\": 1}], {})",
        // Duplicate name
        "ctx.plan({}, [{\"name\": \"a\"}, {\"name\": \"a\"}], {})",
        // Cost = 0
        "ctx.plan({}, [{\"name\": \"a\", \"cost\": 0}], {})",
        // max_expansions = 0
        "ctx.plan({}, [], {}, max_expansions = 0)",
        // max_expansions = 16385
        "ctx.plan({}, [], {}, max_expansions = 16385)",
        // 257 actions
        "ctx.plan({}, [{\"name\": \"a_%d\" % i} for i in range(257)], {})",
        // 65 facts
        "ctx.plan({\"f_%d\" % i: 1 for i in range(65)}, [], {})",
        // Wrong argument types
        "ctx.plan([], [], {})",
        "ctx.plan({}, {}, {})",
        "ctx.plan({}, [1], {})",
        "ctx.plan({}, [{\"cost\": 1}], {})",
        "ctx.plan({}, [{\"name\": 1}], {})",
        "ctx.plan({}, [{\"name\": \"a\", \"cost\": \"1\"}], {})",
        "ctx.plan({}, [{\"name\": \"a\", \"pre\": []}], {})",
        "ctx.plan({}, [], {}, max_expansions = \"9\")",
      )

    for (code in cases) {
      val (_, _, err) = runHook(code)
      assertTrue(err != null, "Expected error for: $code")
      val msg = err.substringAfter(": ")
      assertTrue(msg.startsWith("ctx.plan("), "Expected 'ctx.plan(' prefix for $code, got: $err")
    }
  }

  @Test
  fun `step cost test search spends no script steps`() {
    val (f, p, err) =
      runHook(
        """
        # 13 independent actions and a goal needing 6 of them: a few thousand expansions, all
        # inside the action-check cap, which a Starlark search could not afford.
        actions = [
            {"name": "act_%d" % i, "effects": {"f_%d" % i: 1}}
            for i in range(13)
        ]
        goal = {"f_%d" % i: 1 for i in range(6)}
        res = ctx.plan({}, actions, goal, max_expansions = 16384)
        ctx.send(ctx.actor, "RES:" + repr(res))
        """
      )
    assertEquals(null, err)
    val out = f.out(p)
    assertContains(out, "RES:[\"act_0\", \"act_1\", \"act_2\", \"act_3\", \"act_4\", \"act_5\"]")
    assertEquals(emptyList(), f.diagnostics("step_limit"))
  }

  @Test
  fun `determinism test two worlds with different seeds return same plan`() {
    val code =
      """
      p = ctx.plan(
          {"x": 0},
          [
              {"name": "fast", "cost": 2, "pre": {"x": 0}, "effects": {"x": 2}},
              {"name": "slow1", "cost": 1, "pre": {"x": 0}, "effects": {"x": 1}},
              {"name": "slow2", "cost": 1, "pre": {"x": 1}, "effects": {"x": 2}},
          ],
          {"x": 2},
      )
      ctx.send(ctx.actor, "RES:" + repr(p))
      """
    val (f1, p1, _) = runHook(code, seed = 123)
    val (f2, p2, _) = runHook(code, seed = 999)
    val out1 = f1.out(p1)
    val out2 = f2.out(p2)
    assertEquals(out1, out2)
    assertContains(out1, "RES:[\"fast\"]")
  }
}
