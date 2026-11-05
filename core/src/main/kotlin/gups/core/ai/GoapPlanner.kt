package gups.core.ai

import java.util.PriorityQueue

/** A fact's value: equal only to a value of the same kind and content. */
sealed interface FactValue {
  data class Bool(val v: Boolean) : FactValue

  data class Num(val v: Long) : FactValue

  data class Text(val v: String) : FactValue
}

/** An action with preconditions and effects over facts, and a positive execution cost. */
data class GoapAction(
  val name: String,
  val cost: Int = 1,
  val pre: Map<String, FactValue> = emptyMap(),
  val effects: Map<String, FactValue> = emptyMap(),
)

/** Result of a GOAP search. */
sealed interface PlanResult {
  /** [steps] is empty when the goal already holds. */
  data class Found(val steps: List<String>, val cost: Long, val expansions: Int) : PlanResult

  /** [exhausted] is true when a bound ran out, false when the goal is unreachable. */
  data class NotFound(val expansions: Int, val exhausted: Boolean) : PlanResult
}

/**
 * Deterministic, bounded goal-oriented action planner (GOAP).
 *
 * Finds the cheapest sequence of actions satisfying a goal state using uniform-cost search. Ties
 * are broken by fewer steps, then lexicographically by declaration order of actions.
 */
object GoapPlanner {
  /** Default search node expansion cap. */
  const val DEFAULT_EXPANSIONS = 1_024

  /** Maximum allowed search node expansion cap. */
  const val MAX_EXPANSIONS = 16_384

  /** Maximum number of actions allowed in a planning problem. */
  const val MAX_ACTIONS = 256

  /**
   * Maximum number of distinct fact names across initial facts, goal, and all action preconditions
   * and effects.
   */
  const val MAX_FACTS = 64

  /** Maximum plan step depth. Nodes at this depth are not expanded. */
  const val MAX_PLAN_LENGTH = 32

  /**
   * Maximum action checks per search call. One action tested against one expanded node counts as
   * one check. With more than 4 actions, this cap ends the search before [MAX_EXPANSIONS] (16,384)
   * can be reached.
   *
   * Sized so the worst case (256 actions, 64 facts, every successor new) stays well inside the
   * `think` wall-clock backstop: a median of about 3 ms on an Apple M5 Max (arm64). 131,072 took
   * about 15 ms and 262,144 about 26 ms on the same machine.
   */
  const val MAX_ACTION_CHECKS = 65_536

  private class OrderKey(val cost: Long, val path: IntArray) : Comparable<OrderKey> {
    override fun compareTo(other: OrderKey): Int {
      if (this.cost != other.cost) return this.cost.compareTo(other.cost)
      if (this.path.size != other.path.size) return this.path.size.compareTo(other.path.size)
      for (i in path.indices) {
        val cmp = this.path[i].compareTo(other.path[i])
        if (cmp != 0) return cmp
      }
      return 0
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is OrderKey) return false
      return cost == other.cost && path.contentEquals(other.path)
    }

    override fun hashCode(): Int = 31 * cost.hashCode() + path.contentHashCode()
  }

  /**
   * Whether the order key of [parent] extended by [action] at [cost] sorts before [other], without
   * allocating the extended path.
   */
  private fun precedes(cost: Long, parent: IntArray, action: Int, other: OrderKey): Boolean {
    if (cost != other.cost) return cost < other.cost
    if (parent.size + 1 != other.path.size) return parent.size + 1 < other.path.size
    for (i in parent.indices) {
      if (parent[i] != other.path[i]) return parent[i] < other.path[i]
    }
    return action < other.path[parent.size]
  }

  private class StateKey(val state: IntArray) {
    var hash = state.contentHashCode()

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is StateKey) return false
      return state.contentEquals(other.state)
    }
  }

  private class SearchNode(val state: IntArray, val stateKey: StateKey, val orderKey: OrderKey) :
    Comparable<SearchNode> {
    override fun compareTo(other: SearchNode): Int = this.orderKey.compareTo(other.orderKey)
  }

  private class CompiledAction(
    val cost: Int,
    val preFactIndices: IntArray,
    val preValueIds: IntArray,
    val effectFactIndices: IntArray,
    val effectValueIds: IntArray,
  ) {
    fun matchesPre(state: IntArray): Boolean {
      for (i in preFactIndices.indices) {
        if (state[preFactIndices[i]] != preValueIds[i]) return false
      }
      return true
    }

    fun hasChanges(state: IntArray): Boolean {
      for (i in effectFactIndices.indices) {
        if (state[effectFactIndices[i]] != effectValueIds[i]) return true
      }
      return false
    }

    fun applyEffects(source: IntArray, dest: IntArray) {
      System.arraycopy(source, 0, dest, 0, source.size)
      for (i in effectFactIndices.indices) {
        dest[effectFactIndices[i]] = effectValueIds[i]
      }
    }
  }

  /**
   * Plans the cheapest action sequence to reach [goal] from [facts] using uniform-cost search.
   *
   * Conditions and goal facts are checked for exact equality (matching both type and value). A fact
   * absent from the state matches no precondition or goal entry until an action effect sets it. If
   * the goal already holds in [facts], returns [PlanResult.Found] with empty steps and zero cost.
   * Ties between equal-cost plans are broken first by shorter path length, then lexicographically
   * by action indices in declaration order.
   *
   * The search is bounded by [maxExpansions], [MAX_PLAN_LENGTH] steps, and [MAX_ACTION_CHECKS]
   * action checks. With more than 4 actions the action-check cap ends the search before
   * [MAX_EXPANSIONS] can be reached. Running out of either bound returns [PlanResult.NotFound] with
   * `exhausted` set, never an error. Actions that produce no state change are discarded.
   *
   * @throws IllegalArgumentException if argument limits are exceeded or action definitions are
   *   invalid.
   */
  fun plan(
    facts: Map<String, FactValue>,
    actions: List<GoapAction>,
    goal: Map<String, FactValue>,
    maxExpansions: Int = DEFAULT_EXPANSIONS,
  ): PlanResult {
    if (maxExpansions !in 1..MAX_EXPANSIONS) {
      throw IllegalArgumentException(
        "max_expansions must be between 1 and $MAX_EXPANSIONS, got $maxExpansions"
      )
    }
    if (actions.size > MAX_ACTIONS) {
      throw IllegalArgumentException(
        "actions must have at most $MAX_ACTIONS entries, got ${actions.size}"
      )
    }

    val actionNames = HashSet<String>(actions.size)
    for (action in actions) {
      if (action.name.isEmpty()) {
        throw IllegalArgumentException("action name must not be empty")
      }
      if (!actionNames.add(action.name)) {
        throw IllegalArgumentException("duplicate action name '${action.name}'")
      }
      if (action.cost < 1) {
        throw IllegalArgumentException(
          "action '${action.name}' cost must be at least 1, got ${action.cost}"
        )
      }
    }

    // Intern fact names in first-seen order
    val factIndexMap = LinkedHashMap<String, Int>()
    fun internFact(name: String) {
      if (!factIndexMap.containsKey(name)) {
        factIndexMap[name] = factIndexMap.size
      }
    }
    for (k in facts.keys) internFact(k)
    for (k in goal.keys) internFact(k)
    for (a in actions) {
      for (k in a.pre.keys) internFact(k)
      for (k in a.effects.keys) internFact(k)
    }

    if (factIndexMap.size > MAX_FACTS) {
      throw IllegalArgumentException(
        "facts across facts, goal, and actions must not exceed $MAX_FACTS distinct names, got ${factIndexMap.size}"
      )
    }

    val numFacts = factIndexMap.size
    val factValueMap = Array(numFacts) { mutableMapOf<FactValue, Int>() }
    fun internValue(factIdx: Int, value: FactValue): Int =
      factValueMap[factIdx].getOrPut(value) { factValueMap[factIdx].size }

    val startState = IntArray(numFacts) { -1 }
    for ((name, value) in facts) {
      val fIdx = factIndexMap[name]!!
      val vId = internValue(fIdx, value)
      startState[fIdx] = vId
    }

    val goalFactIndices = IntArray(goal.size)
    val goalValueIds = IntArray(goal.size)
    var gIdx = 0
    for ((name, value) in goal) {
      val fIdx = factIndexMap[name]!!
      val vId = internValue(fIdx, value)
      goalFactIndices[gIdx] = fIdx
      goalValueIds[gIdx] = vId
      gIdx++
    }

    fun satisfiesGoal(state: IntArray): Boolean {
      for (i in goalFactIndices.indices) {
        if (state[goalFactIndices[i]] != goalValueIds[i]) return false
      }
      return true
    }

    if (satisfiesGoal(startState)) {
      return PlanResult.Found(emptyList(), 0L, 0)
    }

    val compiledActions =
      actions.map { action ->
        val preFactIdx = IntArray(action.pre.size)
        val preValId = IntArray(action.pre.size)
        var pIdx = 0
        for ((name, value) in action.pre) {
          val fIdx = factIndexMap[name]!!
          val vId = internValue(fIdx, value)
          preFactIdx[pIdx] = fIdx
          preValId[pIdx] = vId
          pIdx++
        }

        val effFactIdx = IntArray(action.effects.size)
        val effValId = IntArray(action.effects.size)
        var eIdx = 0
        for ((name, value) in action.effects) {
          val fIdx = factIndexMap[name]!!
          val vId = internValue(fIdx, value)
          effFactIdx[eIdx] = fIdx
          effValId[eIdx] = vId
          eIdx++
        }

        CompiledAction(
          cost = action.cost,
          preFactIndices = preFactIdx,
          preValueIds = preValId,
          effectFactIndices = effFactIdx,
          effectValueIds = effValId,
        )
      }

    val pow31 = IntArray(numFacts)
    if (numFacts > 0) {
      pow31[0] = 1
      for (i in 1 until numFacts) {
        pow31[i] = pow31[i - 1] * 31
      }
    }

    val startStateKey = StateKey(startState)
    val startOrderKey = OrderKey(0L, IntArray(0))
    val best = HashMap<StateKey, OrderKey>()
    best[startStateKey] = startOrderKey

    val frontier = PriorityQueue<SearchNode>()
    frontier.add(SearchNode(startState, startStateKey, startOrderKey))

    val scratchState = IntArray(numFacts)
    val queryStateKey = StateKey(scratchState)

    var expansions = 0
    var actionChecks = 0
    var depthLimitHit = false

    while (frontier.isNotEmpty()) {
      val node = frontier.poll()
      if (node.orderKey != best[node.stateKey]) continue

      if (satisfiesGoal(node.state)) {
        val steps = node.orderKey.path.map { actions[it].name }
        return PlanResult.Found(steps, node.orderKey.cost, expansions)
      }

      if (expansions >= maxExpansions) {
        return PlanResult.NotFound(expansions, exhausted = true)
      }

      expansions++

      if (node.orderKey.path.size == MAX_PLAN_LENGTH) {
        depthLimitHit = true
        continue
      }

      val parentHash = node.stateKey.hash
      System.arraycopy(node.state, 0, scratchState, 0, numFacts)

      for (actionIndex in compiledActions.indices) {
        actionChecks++
        if (actionChecks >= MAX_ACTION_CHECKS) {
          return PlanResult.NotFound(expansions, exhausted = true)
        }

        val action = compiledActions[actionIndex]
        if (!action.matchesPre(node.state)) continue
        if (!action.hasChanges(node.state)) continue

        val nextCost = node.orderKey.cost + action.cost
        val nextPathLength = node.orderKey.path.size + 1

        var nextHash = parentHash
        for (i in action.effectFactIndices.indices) {
          val fIdx = action.effectFactIndices[i]
          val newVal = action.effectValueIds[i]
          val oldVal = node.state[fIdx]
          if (oldVal != newVal) {
            nextHash += (newVal - oldVal) * pow31[numFacts - 1 - fIdx]
            scratchState[fIdx] = newVal
          }
        }

        queryStateKey.hash = nextHash
        val currentBest = best[queryStateKey]
        if (
          currentBest == null || precedes(nextCost, node.orderKey.path, actionIndex, currentBest)
        ) {
          val nextPath = IntArray(nextPathLength)
          System.arraycopy(node.orderKey.path, 0, nextPath, 0, node.orderKey.path.size)
          nextPath[node.orderKey.path.size] = actionIndex
          val nextOrderKey = OrderKey(nextCost, nextPath)
          val nextState = scratchState.clone()
          val nextStateKey = StateKey(nextState)
          nextStateKey.hash = nextHash
          best[nextStateKey] = nextOrderKey
          frontier.add(SearchNode(nextState, nextStateKey, nextOrderKey))
        }

        // Restore scratchState
        for (i in action.effectFactIndices.indices) {
          val fIdx = action.effectFactIndices[i]
          scratchState[fIdx] = node.state[fIdx]
        }
      }
    }

    if (depthLimitHit) {
      return PlanResult.NotFound(expansions, exhausted = true)
    }
    return PlanResult.NotFound(expansions, exhausted = false)
  }
}
