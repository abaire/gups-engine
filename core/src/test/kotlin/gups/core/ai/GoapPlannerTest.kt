package gups.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoapPlannerTest {

  @Test
  fun `goal already holding returns Found with empty steps`() {
    val facts = mapOf("has_key" to FactValue.Bool(true))
    val goal = mapOf("has_key" to FactValue.Bool(true))
    val result = GoapPlanner.plan(facts, emptyList(), goal)
    assertEquals(PlanResult.Found(emptyList(), 0, 0), result)
  }

  @Test
  fun `empty goal returns Found with empty steps`() {
    val facts = mapOf("x" to FactValue.Num(1))
    val result = GoapPlanner.plan(facts, emptyList(), emptyMap())
    assertEquals(PlanResult.Found(emptyList(), 0, 0), result)
  }

  @Test
  fun `three-step chain is found in the right order`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(
        GoapAction(
          name = "step1",
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("mid1")),
        ),
        GoapAction(
          name = "step2",
          pre = mapOf("pos" to FactValue.Text("mid1")),
          effects = mapOf("pos" to FactValue.Text("mid2")),
        ),
        GoapAction(
          name = "step3",
          pre = mapOf("pos" to FactValue.Text("mid2")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("step1", "step2", "step3"), result.steps)
    assertEquals(3L, result.cost)
  }

  @Test
  fun `cheaper of two plans wins even when it has more steps`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(
        GoapAction(
          name = "expensive_shortcut",
          cost = 10,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
        GoapAction(
          name = "cheap_step1",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("mid")),
        ),
        GoapAction(
          name = "cheap_step2",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("mid")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("cheap_step1", "cheap_step2"), result.steps)
    assertEquals(2L, result.cost)
  }

  @Test
  fun `equal-cost plans shorter wins`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(
        GoapAction(
          name = "two_step_a",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("mid")),
        ),
        GoapAction(
          name = "two_step_b",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("mid")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
        GoapAction(
          name = "one_step",
          cost = 2,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("one_step"), result.steps)
    assertEquals(2L, result.cost)
  }

  @Test
  fun `equal cost and length earlier-declared actions win`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(
        GoapAction(
          name = "action_early",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
        GoapAction(
          name = "action_late",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("action_early"), result.steps)
    assertEquals(1L, result.cost)
  }

  @Test
  fun `equal cost shorter path replaces a longer one already stored for a shared state`() {
    // S0 -> P1 (cost 1) -> P2 (cost 1) -> M (cost 1): total cost 3, length 3.
    // S0 -> X (cost 2) -> M (cost 1): total cost 3, length 2.
    // P2 reaches M at cost 2 expansion, storing path of length 3 in best[M].
    // Later X (cost 2) is popped at cost 2 expansion and reaches M with shorter length 2.
    // The shorter path must overwrite best[M] and win when M -> G is expanded.
    val facts = mapOf("pos" to FactValue.Text("S0"))
    val actions =
      listOf(
        GoapAction(
          name = "p1",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("S0")),
          effects = mapOf("pos" to FactValue.Text("P1")),
        ),
        GoapAction(
          name = "x",
          cost = 2,
          pre = mapOf("pos" to FactValue.Text("S0")),
          effects = mapOf("pos" to FactValue.Text("X")),
        ),
        GoapAction(
          name = "p2",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("P1")),
          effects = mapOf("pos" to FactValue.Text("P2")),
        ),
        GoapAction(
          name = "p2_to_m",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("P2")),
          effects = mapOf("pos" to FactValue.Text("M")),
        ),
        GoapAction(
          name = "x_to_m",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("X")),
          effects = mapOf("pos" to FactValue.Text("M")),
        ),
        GoapAction(
          name = "m_to_g",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("M")),
          effects = mapOf("pos" to FactValue.Text("G")),
        ),
      )
    val goal = mapOf("pos" to FactValue.Text("G"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("x", "x_to_m", "m_to_g"), result.steps)
    assertEquals(4L, result.cost)
  }

  @Test
  fun `equal cost and length earlier actions win when the later path stored the shared state first`() {
    // B (cost 1) is expanded before A (cost 2), so [b, b_to_m] stores M first. [a, a_to_m]
    // reaches M later at the same cost and length but sorts first, and must replace it.
    fun hop(name: String, cost: Int, from: String, to: String) =
      GoapAction(
        name = name,
        cost = cost,
        pre = mapOf("pos" to FactValue.Text(from)),
        effects = mapOf("pos" to FactValue.Text(to)),
      )
    val actions =
      listOf(
        hop("a", 2, "S", "A"),
        hop("b", 1, "S", "B"),
        hop("b_to_m", 2, "B", "M"),
        hop("a_to_m", 1, "A", "M"),
        hop("m_to_g", 1, "M", "G"),
      )
    val result =
      GoapPlanner.plan(
        mapOf("pos" to FactValue.Text("S")),
        actions,
        mapOf("pos" to FactValue.Text("G")),
      )
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("a", "a_to_m", "m_to_g"), result.steps)
    assertEquals(4L, result.cost)
  }

  @Test
  fun `absent fact matches nothing until an effect sets it`() {
    val facts = emptyMap<String, FactValue>()
    val actions =
      listOf(
        GoapAction(
          name = "needs_absent",
          pre = mapOf("has_gem" to FactValue.Bool(true)),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
        GoapAction(name = "find_gem", effects = mapOf("has_gem" to FactValue.Bool(true))),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("find_gem", "needs_absent"), result.steps)
  }

  @Test
  fun `bool true does not match num 1`() {
    val facts = mapOf("flag" to FactValue.Bool(true))
    val actions =
      listOf(
        GoapAction(
          name = "act",
          pre = mapOf("flag" to FactValue.Num(1)),
          effects = mapOf("goal" to FactValue.Bool(true)),
        )
      )
    val goal = mapOf("goal" to FactValue.Bool(true))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertEquals(PlanResult.NotFound(expansions = 1, exhausted = false), result)
  }

  @Test
  fun `action whose effects change nothing is never added to a plan`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(
        GoapAction(name = "noop", cost = 1, effects = emptyMap()),
        GoapAction(
          name = "noop_same_value",
          cost = 1,
          effects = mapOf("pos" to FactValue.Text("start")),
        ),
        GoapAction(name = "reach_goal", cost = 1, effects = mapOf("pos" to FactValue.Text("goal"))),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.Found)
    assertEquals(listOf("reach_goal"), result.steps)
  }

  @Test
  fun `unreachable goal returns NotFound with exhausted false`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(GoapAction(name = "dead_end", effects = mapOf("pos" to FactValue.Text("dead_end"))))
    val goal = mapOf("pos" to FactValue.Text("unreachable"))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertEquals(PlanResult.NotFound(expansions = 2, exhausted = false), result)
  }

  @Test
  fun `small maxExpansions on solvable problem returns NotFound with exhausted true`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(
        GoapAction(
          name = "step1",
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("mid")),
        ),
        GoapAction(
          name = "step2",
          pre = mapOf("pos" to FactValue.Text("mid")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val result = GoapPlanner.plan(facts, actions, goal, maxExpansions = 1)
    assertEquals(PlanResult.NotFound(expansions = 1, exhausted = true), result)
  }

  @Test
  fun `goal needing 33 steps returns NotFound with exhausted true`() {
    val facts = mapOf("step" to FactValue.Num(0))
    val actions =
      (0 until 33).map { i ->
        GoapAction(
          name = "step_$i",
          pre = mapOf("step" to FactValue.Num(i.toLong())),
          effects = mapOf("step" to FactValue.Num((i + 1).toLong())),
        )
      }
    val goal = mapOf("step" to FactValue.Num(33))
    val result = GoapPlanner.plan(facts, actions, goal)
    assertTrue(result is PlanResult.NotFound)
    assertTrue(result.exhausted)
  }

  @Test
  fun `action-check bound returns NotFound with exhausted true without exceeding bound`() {
    // 256 always-applicable actions spread over 60 facts, so almost every successor is new.
    val facts = mapOf("depth" to FactValue.Num(0))
    val goal = mapOf("unreachable" to FactValue.Bool(true))
    val factActions =
      (0 until 256).map { i ->
        val factId = i % 60
        val valueId = i / 60
        GoapAction(
          name = "act_$i",
          cost = 1,
          effects = mapOf("f_$factId" to FactValue.Num(valueId.toLong())),
        )
      }
    val result = GoapPlanner.plan(facts, factActions, goal, maxExpansions = 16_384)
    assertTrue(result is PlanResult.NotFound)
    assertTrue(result.exhausted)
    // Each expansion tests all 256 actions, so the check cap is reached long before 16,384.
    assertTrue(result.expansions <= GoapPlanner.MAX_ACTION_CHECKS / 256 + 1)
  }

  @Test
  fun `validation errors and messages`() {
    val validFacts = mapOf("a" to FactValue.Bool(true))
    val validActions = listOf(GoapAction(name = "act1"))
    val validGoal = mapOf("a" to FactValue.Bool(true))

    val e1 =
      assertFailsWith<IllegalArgumentException> {
        GoapPlanner.plan(validFacts, validActions, validGoal, maxExpansions = 0)
      }
    assertEquals("max_expansions must be between 1 and 16384, got 0", e1.message)

    val e2 =
      assertFailsWith<IllegalArgumentException> {
        GoapPlanner.plan(validFacts, validActions, validGoal, maxExpansions = 16_385)
      }
    assertEquals("max_expansions must be between 1 and 16384, got 16385", e2.message)

    val tooManyActions = (0..256).map { GoapAction(name = "act_$it") }
    val e3 =
      assertFailsWith<IllegalArgumentException> {
        GoapPlanner.plan(validFacts, tooManyActions, validGoal)
      }
    assertEquals("actions must have at most 256 entries, got 257", e3.message)

    val e4 =
      assertFailsWith<IllegalArgumentException> {
        GoapPlanner.plan(validFacts, listOf(GoapAction(name = "")), validGoal)
      }
    assertEquals("action name must not be empty", e4.message)

    val e5 =
      assertFailsWith<IllegalArgumentException> {
        GoapPlanner.plan(
          validFacts,
          listOf(GoapAction(name = "dup"), GoapAction(name = "dup")),
          validGoal,
        )
      }
    assertEquals("duplicate action name 'dup'", e5.message)

    val e6 =
      assertFailsWith<IllegalArgumentException> {
        GoapPlanner.plan(validFacts, listOf(GoapAction(name = "act", cost = 0)), validGoal)
      }
    assertEquals("action 'act' cost must be at least 1, got 0", e6.message)

    val tooManyFacts = (0..64).associate { "fact_$it" to FactValue.Num(it.toLong()) }
    val e7 =
      assertFailsWith<IllegalArgumentException> {
        GoapPlanner.plan(tooManyFacts, validActions, emptyMap())
      }
    assertEquals(
      "facts across facts, goal, and actions must not exceed 64 distinct names, got 65",
      e7.message,
    )
  }

  @Test
  fun `determinism test same call twice gives equal results`() {
    val facts = mapOf("pos" to FactValue.Text("start"))
    val actions =
      listOf(
        GoapAction(
          name = "a1",
          cost = 2,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
        GoapAction(
          name = "a2",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("start")),
          effects = mapOf("pos" to FactValue.Text("mid")),
        ),
        GoapAction(
          name = "a3",
          cost = 1,
          pre = mapOf("pos" to FactValue.Text("mid")),
          effects = mapOf("pos" to FactValue.Text("goal")),
        ),
      )
    val goal = mapOf("pos" to FactValue.Text("goal"))
    val r1 = GoapPlanner.plan(facts, actions, goal)
    val r2 = GoapPlanner.plan(facts, actions, goal)
    assertEquals(r1, r2)
  }

  @Test
  fun `worst-case timing test`() {
    val facts = mapOf("f_0" to FactValue.Num(0))
    val actions =
      (0 until 256).map { i ->
        val factId = i % 60
        val valueId = (i / 60) + 1
        GoapAction(
          name = "act_$i",
          cost = 1,
          effects = mapOf("f_$factId" to FactValue.Num(valueId.toLong())),
        )
      }
    val goal = mapOf("f_0" to FactValue.Num(999))

    // Warm up JIT
    for (i in 0 until 25) {
      GoapPlanner.plan(facts, actions, goal, maxExpansions = 16_384)
    }

    val times = mutableListOf<Long>()
    var lastResult: PlanResult? = null
    for (i in 0 until 10) {
      val start = System.nanoTime()
      lastResult = GoapPlanner.plan(facts, actions, goal, maxExpansions = 16_384)
      val durationMs = (System.nanoTime() - start) / 1_000_000
      times.add(durationMs)
    }
    times.sort()
    val medianMs = times[times.size / 2]
    println("GoapPlanner worst-case median time: ${medianMs} ms")
    assertTrue(lastResult is PlanResult.NotFound)
    assertTrue(lastResult.exhausted)
  }
}
