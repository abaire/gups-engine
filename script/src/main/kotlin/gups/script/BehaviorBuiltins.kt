package gups.script

import gups.core.model.Direction
import gups.core.world.Aggressive
import gups.core.world.Assist
import gups.core.world.Defend
import gups.core.world.Features
import gups.core.world.Flee
import gups.core.world.Follow
import gups.core.world.Guard
import gups.core.world.Heel
import gups.core.world.PRIORITY_AGGRESSIVE
import gups.core.world.PRIORITY_ASSIST
import gups.core.world.PRIORITY_DEFEND
import gups.core.world.PRIORITY_FLEE
import gups.core.world.PRIORITY_FOLLOW
import gups.core.world.PRIORITY_HEEL
import gups.core.world.PRIORITY_PURSUE
import gups.core.world.PRIORITY_RETARGET
import gups.core.world.PRIORITY_SCAVENGE
import gups.core.world.PRIORITY_WANDER
import gups.core.world.Pursue
import gups.core.world.Retarget
import gups.core.world.Scavenge
import gups.core.world.Sentinel
import gups.core.world.Wander
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.StarlarkThread

/** The behavior builtins a mob's `behaviors` list takes: `wander(...)`, `flee(...)` and so on. */
internal class BehaviorBuiltins(private val features: Features) {
  private fun priorityArg(v: Any, default: Int, what: String): Int =
    if (isNone(v)) default else int(v, what)

  private fun need(symbol: String, name: String = symbol) = requireFeature(features, symbol, name)

  /** A disposition threshold in -100..100, or null for None; the [param] needs its feature. */
  private fun dispositionArg(v: Any, fn: String, param: String): Int? {
    if (isNone(v)) return null
    need("$fn.$param", "$fn($param)")
    val threshold = int(v, "$fn($param)")
    if (threshold !in -100..100) fail("$fn($param): must be in -100..100")
    return threshold
  }

  // ---- Behaviors --------------------------------------------------------------------------------

  @StarlarkMethod(
    name = "wander",
    doc =
      "Behavior: with a percent chance per think, steps through a random open exit within radius exits of home.",
    parameters =
      [
        Param(
          name = "radius",
          named = true,
          defaultValue = "3",
          doc = "Maximum distance from home, in exits.",
        ),
        Param(
          name = "chance",
          named = true,
          defaultValue = "10",
          doc = "Percent chance of taking a step on each think, 0 to 100.",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun wander(radius: Any, chance: Any, priority: Any, thread: StarlarkThread): Any {
    behaviorCtx(thread, "wander")
    val r =
      int(radius, "wander(radius)").also { if (it < 0) fail("wander(radius): can't be negative") }
    return BehaviorDecl(
      Wander(
        r,
        percent(chance, "wander(chance)"),
        priorityArg(priority, PRIORITY_WANDER, "wander(priority)"),
      )
    )
  }

  @StarlarkMethod(
    name = "aggressive",
    doc =
      "Behavior: attacks matching actors it notices. toward is 'players', 'mobs', 'all' or label patterns.",
    parameters =
      [
        Param(
          name = "toward",
          named = true,
          defaultValue = "'players'",
          doc = "Who to attack: 'players', 'mobs', 'all', or label patterns such as 'goblin_*'.",
        ),
        Param(
          name = "unless_level_above",
          named = true,
          defaultValue = "None",
          doc = "Spare actors above this level, or None to attack any level.",
        ),
        Param(
          name = "unless_level_below",
          named = true,
          defaultValue = "None",
          doc = "Spare actors below this level, or None to attack any level.",
        ),
        Param(
          name = "chance",
          named = true,
          defaultValue = "100",
          doc = "Percent chance of looking for a target on each think, 0 to 100.",
        ),
        Param(
          name = "disposition_below",
          named = true,
          defaultValue = "None",
          doc =
            "Attack only targets whose disposition is strictly below this threshold (-100..100).",
        ),
        Param(
          name = "prefer",
          named = true,
          defaultValue = "None",
          doc = "Plain data passed to the world's target_order; None chooses as described.",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun aggressive(
    toward: Any,
    unlessLevelAbove: Any,
    unlessLevelBelow: Any,
    chance: Any,
    dispositionBelow: Any,
    prefer: Any,
    priority: Any,
    thread: StarlarkThread,
  ): Any {
    val pkgPath = behaviorCtx(thread, "aggressive")
    need("aggressive", "aggressive()")
    if (!isNone(unlessLevelAbove))
      need("aggressive.unless_level_above", "aggressive(unless_level_above)")
    if (!isNone(unlessLevelBelow))
      need("aggressive.unless_level_below", "aggressive(unless_level_below)")
    val dispositionBelowInt = dispositionArg(dispositionBelow, "aggressive", "disposition_below")
    val preferVal = preferArg(prefer, "aggressive(prefer)")
    return BehaviorDecl(
      Aggressive(
        actorFilter(toward, "aggressive(toward)", pkgPath),
        intOrNull(unlessLevelAbove, "aggressive(unless_level_above)"),
        intOrNull(unlessLevelBelow, "aggressive(unless_level_below)"),
        percent(chance, "aggressive(chance)"),
        dispositionBelowInt,
        preferVal,
        priorityArg(priority, PRIORITY_AGGRESSIVE, "aggressive(priority)"),
      )
    )
  }

  @StarlarkMethod(
    name = "assist",
    doc = "Behavior: joins fights alongside allies, given as label patterns such as 'goblin_*'.",
    parameters =
      [
        Param(
          name = "allies",
          named = true,
          doc = "Allies to help, as label patterns such as 'goblin_*'.",
        ),
        Param(
          name = "disposition_above",
          named = true,
          defaultValue = "None",
          doc =
            "Help allies and spare opponents whose disposition is strictly above this threshold (-100..100).",
        ),
        Param(
          name = "prefer",
          named = true,
          defaultValue = "None",
          doc = "Plain data passed to the world's target_order; None chooses as described.",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun assist(
    allies: Any,
    dispositionAbove: Any,
    prefer: Any,
    priority: Any,
    thread: StarlarkThread,
  ): Any {
    val pkgPath = behaviorCtx(thread, "assist")
    need("assist", "assist()")
    val dispositionAboveInt = dispositionArg(dispositionAbove, "assist", "disposition_above")
    val preferVal = preferArg(prefer, "assist(prefer)")
    return BehaviorDecl(
      Assist(
        actorFilter(allies, "assist(allies)", pkgPath),
        dispositionAboveInt,
        preferVal,
        priorityArg(priority, PRIORITY_ASSIST, "assist(priority)"),
      )
    )
  }

  @StarlarkMethod(
    name = "retarget",
    doc =
      "Behavior: while engaged, asks target_order whom to fight and switches to its pick if that is not the primary target.",
    parameters =
      [
        Param(
          name = "prefer",
          named = true,
          defaultValue = "None",
          doc = "Plain data passed to the world's target_order; required.",
        ),
        Param(
          name = "toward",
          named = true,
          defaultValue = "'players'",
          doc =
            "Non-engaged actors in the room to consider: 'players', 'mobs', 'all', or label patterns.",
        ),
        Param(
          name = "disposition_below",
          named = true,
          defaultValue = "None",
          doc =
            "Consider only non-engaged targets whose disposition is strictly below this threshold (-100..100).",
        ),
        Param(
          name = "chance",
          named = true,
          defaultValue = "100",
          doc = "Percent chance of asking target_order on each think, 0 to 100.",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun retarget(
    prefer: Any,
    toward: Any,
    dispositionBelow: Any,
    chance: Any,
    priority: Any,
    thread: StarlarkThread,
  ): Any {
    val pkgPath = behaviorCtx(thread, "retarget")
    need("retarget", "retarget()")
    val preferVal =
      preferArg(prefer, "retarget(prefer)")
        ?: fail("retarget(prefer): required; retarget never switches without it")
    val dispositionBelowInt = dispositionArg(dispositionBelow, "retarget", "disposition_below")
    return BehaviorDecl(
      Retarget(
        preferVal,
        actorFilter(toward, "retarget(toward)", pkgPath),
        dispositionBelowInt,
        percent(chance, "retarget(chance)"),
        priorityArg(priority, PRIORITY_RETARGET, "retarget(priority)"),
      )
    )
  }

  @StarlarkMethod(
    name = "flee",
    doc = "Behavior: flees when any listed vital is at or below its percentage of its maximum.",
    parameters =
      [
        Param(
          name = "below",
          named = true,
          doc = "Percentages (0..100) by vital, built with vitals(...).",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun flee(below: Any, priority: Any, thread: StarlarkThread): Any {
    behaviorCtx(thread, "flee")
    need("flee", "flee()")
    val pcts = typed(below, TypedKind.VITALS, "flee(below)")
    if (pcts.isEmpty()) fail("flee(below): name at least one vital")
    for ((vital, pct) in pcts) if (pct !in 0..100) fail("flee(below): $vital must be 0..100")
    return BehaviorDecl(Flee(pcts, priorityArg(priority, PRIORITY_FLEE, "flee(priority)")))
  }

  @StarlarkMethod(
    name = "guard",
    doc =
      "Behavior: denies an exit to everyone who isn't an ally (default: other mobs) while the guard is present.",
    parameters =
      [
        Param(name = "exit", named = true, doc = "Direction of the exit to guard."),
        Param(
          name = "allies",
          named = true,
          defaultValue = "'mobs'",
          doc = "Who may pass: 'players', 'mobs', 'all', or label patterns.",
        ),
        Param(
          name = "disposition_above",
          named = true,
          defaultValue = "None",
          doc =
            "Actors whose disposition is strictly above this threshold (-100..100) are allowed through.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun guard(exit: Any, allies: Any, dispositionAbove: Any, thread: StarlarkThread): Any {
    val pkgPath = behaviorCtx(thread, "guard")
    val dispositionAboveInt =
      if (!isNone(dispositionAbove)) {
        need("guard.disposition_above", "guard(disposition_above)")
        val sa = int(dispositionAbove, "guard(disposition_above)")
        if (sa !in -100..100) fail("guard(disposition_above): must be in -100..100")
        sa
      } else {
        null
      }
    return BehaviorDecl(
      Guard(
        Direction.parse(str(exit, "guard(exit)")),
        actorFilter(allies, "guard(allies)", pkgPath),
        dispositionAboveInt,
      )
    )
  }

  @StarlarkMethod(
    name = "follow",
    doc =
      "Behavior: follows the first matching actor it meets ('players', 'mobs' or label patterns).",
    parameters =
      [
        Param(
          name = "target",
          named = true,
          doc = "Who to follow: 'players', 'mobs', or label patterns.",
        ),
        Param(
          name = "disposition_above",
          named = true,
          defaultValue = "None",
          doc =
            "Follow only leaders whose disposition is strictly above this threshold (-100..100).",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun follow(target: Any, dispositionAbove: Any, priority: Any, thread: StarlarkThread): Any {
    val pkgPath = behaviorCtx(thread, "follow")
    val dispositionAboveInt =
      if (!isNone(dispositionAbove)) {
        need("follow.disposition_above", "follow(disposition_above)")
        val sa = int(dispositionAbove, "follow(disposition_above)")
        if (sa !in -100..100) fail("follow(disposition_above): must be in -100..100")
        sa
      } else {
        null
      }
    return BehaviorDecl(
      Follow(
        actorFilter(target, "follow(target)", pkgPath),
        dispositionAboveInt,
        priorityArg(priority, PRIORITY_FOLLOW, "follow(priority)"),
      )
    )
  }

  @StarlarkMethod(
    name = "pursue",
    doc = "Behavior: heads for the nearest matching actor within range exits.",
    parameters =
      [
        Param(
          name = "target",
          named = true,
          doc = "Who to pursue: 'players', 'mobs', or label patterns.",
        ),
        Param(
          name = "disposition_below",
          named = true,
          defaultValue = "None",
          doc =
            "Pursue only targets whose disposition is strictly below this threshold (-100..100).",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
        Param(
          name = "range",
          named = true,
          defaultValue = "10",
          doc = "Exits searched from the mob's room; None searches the whole world.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun pursue(
    target: Any,
    dispositionBelow: Any,
    priority: Any,
    range: Any,
    thread: StarlarkThread,
  ): Any {
    val pkgPath = behaviorCtx(thread, "pursue")
    val dispositionBelowInt = dispositionArg(dispositionBelow, "pursue", "disposition_below")
    val rangeInt = if (isNone(range)) null else positive(range, "pursue(range)")
    return BehaviorDecl(
      Pursue(
        actorFilter(target, "pursue(target)", pkgPath),
        dispositionBelowInt,
        priorityArg(priority, PRIORITY_PURSUE, "pursue(priority)"),
        rangeInt,
      )
    )
  }

  @StarlarkMethod(
    name = "sentinel",
    doc = "Behavior: never leaves its room.",
    useStarlarkThread = true,
  )
  fun sentinel(thread: StarlarkThread): Any {
    behaviorCtx(thread, "sentinel")
    return BehaviorDecl(Sentinel)
  }

  @StarlarkMethod(
    name = "scavenge",
    doc = "Behavior: picks up items lying within reach.",
    parameters =
      [
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        )
      ],
    useStarlarkThread = true,
  )
  fun scavenge(priority: Any, thread: StarlarkThread): Any {
    behaviorCtx(thread, "scavenge")
    return BehaviorDecl(Scavenge(priorityArg(priority, PRIORITY_SCAVENGE, "scavenge(priority)")))
  }

  @StarlarkMethod(
    name = "heel",
    doc =
      "Behavior: follows its owner by the shortest path, however far. Does nothing for a mob without an owner in the world.",
    parameters =
      [
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        )
      ],
    useStarlarkThread = true,
  )
  fun heel(priority: Any, thread: StarlarkThread): Any {
    behaviorCtx(thread, "heel")
    return BehaviorDecl(Heel(priorityArg(priority, PRIORITY_HEEL, "heel(priority)")))
  }

  @StarlarkMethod(
    name = "defend",
    doc =
      "Behavior: attacks whatever its owner is fighting; with group, whatever the owner's in-room group members are fighting.",
    parameters =
      [
        Param(
          name = "group",
          named = true,
          defaultValue = "True",
          doc = "Also defend the owner's in-room group members.",
        ),
        Param(
          name = "priority",
          named = true,
          defaultValue = "None",
          doc = "Proposal priority; higher wins. None uses the behavior's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun defend(group: Any, priority: Any, thread: StarlarkThread): Any {
    behaviorCtx(thread, "defend")
    need("defend", "defend()")
    return BehaviorDecl(
      Defend(
        bool(group, "defend(group)"),
        priorityArg(priority, PRIORITY_DEFEND, "defend(priority)"),
      )
    )
  }
}
