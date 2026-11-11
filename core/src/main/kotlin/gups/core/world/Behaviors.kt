package gups.core.world

import gups.core.AttrValue
import gups.core.BUBBLE_RADIUS
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.Direction
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// The built-in behaviors. Each proposes at most one action per think cycle; the
// think cycle sorts the proposals by priority, then by declaration order. The
// priorities default to the constants below, where fleeing beats fighting, which beats moving
// about, and each behavior takes its own.

const val PRIORITY_FLEE = 100
const val PRIORITY_DEFEND = 50
const val PRIORITY_HEEL = 30
const val PRIORITY_AGGRESSIVE = 60
const val PRIORITY_RETARGET = 55
const val PRIORITY_ASSIST = 50
const val PRIORITY_PURSUE = 40
const val PRIORITY_FOLLOW = 30
const val PRIORITY_RETURN = 20
const val PRIORITY_WANDER = 10
const val PRIORITY_SCAVENGE = 5

const val PURSUE_DEFAULT_RANGE = 10

/**
 * Whom a behavior means: `players`, `mobs`, `all`, or label patterns such as
 * `//zones/caves:goblin_*` with `*` wildcards. A pattern without a package matches the label's name
 * in any package.
 */
data class ActorFilter(val patterns: List<String>) {
  constructor(vararg patterns: String) : this(patterns.toList())

  fun matches(a: Actor): Boolean =
    patterns.any { p ->
      when (p) {
        "players" -> a is Player
        "mobs" -> a is Mob
        "all",
        "anyone" -> true
        else -> a is Mob && labelMatches(p, a.protoLabel)
      }
    }

  override fun toString(): String = patterns.joinToString(", ")

  companion object {
    fun labelMatches(pattern: String, label: Label): Boolean {
      val text = if (pattern.startsWith("//")) label.v else label.name
      val regex = pattern.split("*").joinToString(".*") { Regex.escape(it) }
      return Regex(regex).matches(text)
    }
  }
}

private fun World.others(mob: Mob): List<Actor> =
  mob.room.actorIndex.values.filter {
    it !== mob && it.inWorld && it.condition != Condition.DEAD && isSimulated(it)
  }

/**
 * `wander(radius, chance)`: with probability [chance] percent per think, steps through a random
 * open exit that stays within [radius] exits of home and never past the mob's leash. Outside that
 * area it steps toward home instead.
 */
data class Wander(val radius: Int, val chance: Int, val priority: Int = PRIORITY_WANDER) :
  Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged || !world.rng.passes(chance)) return null
    val home = world.roomOrNull(mob.homeRoom) ?: mob.room
    val area = world.homeArea(home, wanderRadius(world, mob))
    return if (mob.room !in area) stepHome(world, mob, home) else stepOut(world, mob, area)
  }

  private fun wanderRadius(world: World, mob: Mob): Int =
    world.leashArea(mob)?.let { minOf(radius, mob.leash!!) } ?: radius

  private fun stepHome(world: World, mob: Mob, home: Room): Proposal? {
    val step = world.pathTo(mob.room, home)?.firstOrNull() ?: return null
    return Proposal(step.name, priority, "wander", MoveAction(step))
  }

  private fun stepOut(world: World, mob: Mob, area: Set<Room>): Proposal? {
    val exits = mob.room.exits.values.filter { openExit(world, mob.room, it, area) }
    if (exits.isEmpty()) return null
    val exit = world.rng.choice(exits)
    return Proposal(exit.direction.name, priority, "wander", MoveAction(exit.direction))
  }

  private fun openExit(world: World, room: Room, exit: Exit, area: Set<Room>): Boolean {
    if (!exit.passable) return false
    if (world.roomOrNull(exit.to) !in area) return false
    return exit.to != room.label
  }
}

/** Rooms within [radius] exits of [from] through open doors. */
internal fun World.within(from: Room, radius: Int): Set<Room> {
  val seen = linkedMapOf(from to 0)
  val queue = ArrayDeque(listOf(from))
  while (queue.isNotEmpty()) {
    val r = queue.removeFirst()
    val d = seen.getValue(r)
    if (d < radius) addWithinNeighbors(r, d, seen, queue)
  }
  return seen.keys
}

private fun World.addWithinNeighbors(
  room: Room,
  depth: Int,
  seen: MutableMap<Room, Int>,
  queue: ArrayDeque<Room>,
) {
  for (e in room.exits.values) {
    if (!e.passable) continue
    val next = roomOrNull(e.to) ?: continue
    if (next !in seen) {
      seen[next] = depth + 1
      queue.addLast(next)
    }
  }
}

/**
 * The first step toward the nearest room within [range] exits of [from] that holds an actor
 * [accept] takes, with that actor (lowest id among those at the nearest distance). Breadth-first in
 * exit declaration order through passable exits, entering only rooms in [allowed] when it is
 * non-null. [from] itself is not searched.
 */
internal fun World.nearestBy(
  from: Room,
  range: Int?,
  allowed: Set<Room>?,
  accept: (Actor) -> Boolean,
): Pair<Actor, Direction>? {
  val firstStep = HashMap<Room, Direction>()
  val seen = hashSetOf(from)
  var level = listOf(from)
  var depth = 0
  val maxDepth = range ?: Int.MAX_VALUE
  while (level.isNotEmpty() && depth < maxDepth) {
    depth++
    level = expandLevel(level, from, seen, firstStep, allowed)
    val best = nearestActor(level, firstStep, accept)
    if (best != null) return best
  }
  return null
}

/** The rooms one exit farther out than [level], recording each newcomer's entry direction. */
private fun World.expandLevel(
  level: List<Room>,
  from: Room,
  seen: MutableSet<Room>,
  firstStep: MutableMap<Room, Direction>,
  allowed: Set<Room>?,
): List<Room> {
  val next = mutableListOf<Room>()
  for (room in level) {
    for (exit in room.exits.values) {
      val r = openRoom(exit) ?: continue
      if (r in seen || allowed?.contains(r) == false) continue
      seen.add(r)
      firstStep[r] = if (room === from) exit.direction else firstStep.getValue(room)
      next.add(r)
    }
  }
  return next
}

private fun World.openRoom(exit: Exit): Room? {
  if (!exit.passable) return null
  return roomOrNull(exit.to)
}

/** The lowest-id actor [accept] takes anywhere in [level], with the direction to enter it from. */
private fun nearestActor(
  level: List<Room>,
  firstStep: Map<Room, Direction>,
  accept: (Actor) -> Boolean,
): Pair<Actor, Direction>? {
  val candidate = level.flatMap { it.actors }.filter(accept).minByOrNull { it.id } ?: return null
  return candidate to firstStep.getValue(candidate.room)
}

private fun World.below(mob: Mob, a: Actor, n: Int?): Boolean = n == null || disposition(mob, a) < n

private fun World.above(mob: Mob, a: Actor, n: Int?): Boolean = n == null || disposition(mob, a) > n

/** Result of `rules.target_order` or a plugin target-order adjuster. */
sealed interface TargetChoice {
  /** Export not defined or failed: use the behavior's default choice. */
  data object Default : TargetChoice

  /** Export returned `None`: propose nothing. */
  data object Nobody : TargetChoice

  /** Export returned a candidate actor. */
  data class Pick(val actor: Actor) : TargetChoice
}

/** Arguments passed to `rules.target_order` and plugin target-order adjusters. */
class TargetOrderCall(
  val actor: Mob,
  val behavior: String,
  val prefer: AttrValue?,
  val candidates: List<Actor>,
  val current: Actor?,
  val currentSince: Long?,
  val dispositions: List<Int>?,
  val ally: Actor?,
)

/** A plugin adjuster that can refine or override the target chosen for a behavior. */
fun interface TargetOrderAdjuster {
  fun adjust(world: World, call: TargetOrderCall, choice: TargetChoice): TargetChoice
}

/** Standings of [actor] toward [candidates] while the STANDING feature is on, else null. */
internal fun World.dispositionsOf(actor: Mob, candidates: List<Actor>): List<Int>? =
  if (Feature.STANDING in features) candidates.map { disposition(actor, it) } else null

internal fun World.chooseTarget(call: TargetOrderCall): TargetChoice {
  val ctx =
    RulesContext(
      world = this,
      actor = call.actor,
      room = call.actor.room,
      behavior = call.behavior,
      prefer = call.prefer,
      candidates = call.candidates,
      current = call.current,
      currentSince = call.currentSince,
      dispositions = call.dispositions,
      ally = call.ally,
    )
  var choice = rules.targetOrder(ctx)
  for (adjuster in targetOrderAdjusters) choice = applyAdjuster(call, adjuster, choice)
  return choice
}

private fun World.applyAdjuster(
  call: TargetOrderCall,
  adjuster: TargetOrderAdjuster,
  choice: TargetChoice,
): TargetChoice =
  try {
    val next = adjuster.adjust(this, call, choice)
    if (next is TargetChoice.Pick && call.candidates.none { it === next.actor }) {
      record(
        Diagnostic(
          pulse,
          "plugin",
          "target order adjuster returned actor '${next.actor.name}' not in candidates",
        )
      )
      choice
    } else {
      next
    }
  } catch (e: Throwable) {
    record(Diagnostic(pulse, "plugin", "target order adjuster failed: $e", e))
    choice
  }

/**
 * `aggressive(toward, unless_level_above, unless_level_below, chance)`: on each think, with
 * probability [chance] percent, attacks the first matching actor in the room the mob notices,
 * sparing actors outside the level band.
 */
data class Aggressive(
  val toward: ActorFilter,
  val unlessLevelAbove: Int? = null,
  val unlessLevelBelow: Int? = null,
  val chance: Int = 100,
  val dispositionBelow: Int? = null,
  val prefer: AttrValue? = null,
  val priority: Int = PRIORITY_AGGRESSIVE,
) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features || mob.isEngaged) return null
    if (!world.rng.passes(chance)) return null
    val candidates = world.others(mob).filter { canAttack(world, mob, it) }
    if (candidates.isEmpty()) return null
    val target = pickTarget(world, mob, candidates) ?: return null
    return Proposal(
      "${world.commands.nameOf("kill")} ${target.keywords.first()}",
      priority,
      "aggressive",
      KillAction(target),
    )
  }

  private fun canAttack(world: World, mob: Mob, a: Actor): Boolean {
    if (!toward.matches(a)) return false
    if (!levelOk(a)) return false
    if (world.isOwnerSide(mob, a)) return false
    if (!world.below(mob, a, dispositionBelow)) return false
    return world.notices(mob, a)
  }

  private fun pickTarget(world: World, mob: Mob, candidates: List<Actor>): Actor? {
    if (prefer == null) return candidates.first()
    val choice =
      world.chooseTarget(
        TargetOrderCall(
          actor = mob,
          behavior = "aggressive",
          prefer = prefer,
          candidates = candidates,
          current = null,
          currentSince = null,
          dispositions = world.dispositionsOf(mob, candidates),
          ally = null,
        )
      )
    return when (choice) {
      is TargetChoice.Pick -> choice.actor
      TargetChoice.Default -> candidates.first()
      TargetChoice.Nobody -> null
    }
  }

  private fun levelOk(a: Actor): Boolean =
    (unlessLevelAbove == null || a.level <= unlessLevelAbove) &&
      (unlessLevelBelow == null || a.level >= unlessLevelBelow)
}

/**
 * The first channel, in the world's perception order, on which [mob] senses [who] for [event]: one
 * `rules.can_perceive` call per channel whose effective sense is above 0. Null when none passes.
 */
internal fun World.sensedChannel(mob: Mob, who: Actor, event: String): Channel? {
  for (ch in schema.perceptionOrder) {
    if (mob.sense(ch) <= 0) continue
    if (
      rules.canPerceive(
        RulesContext(
          this,
          actor = mob,
          target = mob,
          source = who,
          room = mob.room,
          sourceRoom = mob.room,
          channel = ch,
          event = event,
          intensity = 1,
          cost = 0,
        )
      )
    ) {
      return ch
    }
  }
  return null
}

/** Whether [mob] notices [who] in its own room, as the target-seeking behaviors ask it. */
internal fun World.notices(mob: Mob, who: Actor): Boolean =
  sensedChannel(mob, who, "notice") != null

/** `assist(allies)`: joins a fight an ally in the room is in, against the ally's opponent. */
data class Assist(
  val allies: ActorFilter,
  val dispositionAbove: Int? = null,
  val prefer: AttrValue? = null,
  val priority: Int = PRIORITY_ASSIST,
) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features) return null
    if (mob.isEngaged) return null
    for (ally in world.others(mob)) {
      if (!engagedAlly(world, mob, ally)) continue
      val foes = world.opponentsOf(ally).filter { isFoe(world, mob, it) }
      if (foes.isEmpty()) continue
      val foe = pickFoe(world, mob, ally, foes) ?: return null
      return Proposal(
        "${world.commands.nameOf("kill")} ${foe.keywords.first()}",
        priority,
        "assist",
        KillAction(foe),
      )
    }
    return null
  }

  private fun engagedAlly(world: World, mob: Mob, ally: Actor): Boolean {
    if (!allies.matches(ally)) return false
    if (!ally.isEngaged) return false
    return world.above(mob, ally, dispositionAbove)
  }

  private fun isFoe(world: World, mob: Mob, a: Actor): Boolean {
    if (a === mob) return false
    if (allies.matches(a)) return false
    if (world.isOwnerSide(mob, a)) return false
    return dispositionAbove == null || world.disposition(mob, a) <= dispositionAbove
  }

  private fun pickFoe(world: World, mob: Mob, ally: Actor, foes: List<Actor>): Actor? {
    if (prefer == null) return defaultFoe(foes, ally)
    val current = ally.primaryTarget?.let { id -> foes.firstOrNull { it.id == id } }
    val choice =
      world.chooseTarget(
        TargetOrderCall(
          actor = mob,
          behavior = "assist",
          prefer = prefer,
          candidates = foes,
          current = current,
          currentSince = null,
          dispositions = world.dispositionsOf(mob, foes),
          ally = ally,
        )
      )
    return when (choice) {
      is TargetChoice.Pick -> choice.actor
      TargetChoice.Default -> defaultFoe(foes, ally)
      TargetChoice.Nobody -> null
    }
  }

  private fun defaultFoe(foes: List<Actor>, ally: Actor): Actor =
    foes.firstOrNull { it.id == ally.primaryTarget } ?: foes.first()
}

/**
 * `retarget(prefer, ...)`: while engaged, switches to the opponent or bystander target_order picks.
 */
data class Retarget(
  val prefer: AttrValue,
  val toward: ActorFilter = ActorFilter("players"),
  val dispositionBelow: Int? = null,
  val chance: Int = 100,
  val priority: Int = PRIORITY_RETARGET,
) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features || !mob.isEngaged) return null
    if (!world.rng.passes(chance)) return null
    val others = world.others(mob)
    val candidates = retargetCandidates(world, mob, others)
    if (candidates.isEmpty()) return null
    val current = mob.primaryTarget?.let { id -> others.firstOrNull { it.id == id } }
    val pick = pickTarget(world, mob, candidates, current) ?: return null
    if (pick === current) return null
    return Proposal(
      "${world.commands.nameOf("kill")} ${pick.keywords.first()}",
      priority,
      "retarget",
      KillAction(pick),
    )
  }

  private fun retargetCandidates(world: World, mob: Mob, others: List<Actor>): List<Actor> {
    val engaged = others.filter { isEngagedFoe(world, mob, it) }
    val bystanders = others.filter { isBystander(world, mob, it) }
    return (engaged + bystanders).sortedBy { it.id }
  }

  private fun isEngagedFoe(world: World, mob: Mob, a: Actor): Boolean {
    if (a.id !in mob.fighting) return false
    return !world.isOwnerSide(mob, a)
  }

  private fun isBystander(world: World, mob: Mob, a: Actor): Boolean {
    if (a.id in mob.fighting) return false
    if (!toward.matches(a)) return false
    if (world.isOwnerSide(mob, a)) return false
    if (!world.below(mob, a, dispositionBelow)) return false
    return world.notices(mob, a)
  }

  private fun pickTarget(world: World, mob: Mob, candidates: List<Actor>, current: Actor?): Actor? {
    val choice =
      world.chooseTarget(
        TargetOrderCall(
          actor = mob,
          behavior = "retarget",
          prefer = prefer,
          candidates = candidates,
          current = current,
          currentSince = mob.primaryTargetSince,
          dispositions = world.dispositionsOf(mob, candidates),
          ally = null,
        )
      )
    return when (choice) {
      is TargetChoice.Pick -> choice.actor
      TargetChoice.Default,
      TargetChoice.Nobody -> null
    }
  }
}

/**
 * `flee(below)`: flees once any listed vital is at or below its percentage of its maximum, engaged
 * or not.
 */
data class Flee(val below: Map<String, Int>, val priority: Int = PRIORITY_FLEE) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features) return null
    val low =
      below.any { (vital, pct) ->
        val max = mob.max(vital)
        // A vital the mob has no capacity for is never low.
        max > 0 && mob.current(vital).toLong() * 100 <= pct.toLong() * max
      }
    if (!low) return null
    if (mob.room.exits.values.none { it.passable }) return null
    return Proposal(world.commands.nameOf("flee"), priority, "flee", FleeAction)
  }
}

/**
 * `guard(exit)`: denies [exit] to everyone but [allies] while the guard is in the room and able to
 * act. A built-in `before_exit` observer, not a proposal.
 */
data class Guard(
  val exit: Direction,
  val allies: ActorFilter = ActorFilter("mobs"),
  val dispositionAbove: Int? = null,
) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? = null

  fun denies(world: World, guard: Mob, actor: Actor, dir: Direction): Boolean {
    if (dir != exit) return false
    if (actor === guard) return false
    if (allies.matches(actor)) return false
    if (dispositionAbove != null && world.disposition(guard, actor) > dispositionAbove) return false
    if (!world.canAct(guard)) return false
    return world.isSimulated(guard)
  }
}

/** The first guard in [room] that bars [actor] from leaving by [dir], as a denial message. */
internal fun World.guardDenial(actor: Actor, room: Room, dir: Direction): String? {
  for (m in room.actorIndex.values) {
    if (m !is Mob) continue
    if (!m.inWorld || m === actor) continue
    if (deniesExit(m, actor, dir)) return msg(KernelMsg.GUARD_BLOCKS, "actor" to m.name.cap())
  }
  return null
}

private fun World.deniesExit(m: Mob, actor: Actor, dir: Direction): Boolean =
  behaviorsOf(m).any { it is Guard && it.denies(this, m, actor, dir) }

/**
 * `follow(target)`: picks the first matching actor in its room as leader, then follows it along the
 * shortest path while it is within [BUBBLE_RADIUS] exits; farther away, it gives up. While the mob
 * or its leader is outside its leash it proposes nothing, so the mob heads home, and it follows
 * again if the leader comes back inside it.
 */
data class Follow(
  val target: ActorFilter,
  val dispositionAbove: Int? = null,
  val priority: Int = PRIORITY_FOLLOW,
) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged) return null
    val leader = currentLeader(world, mob) ?: return null
    if (leader.room === mob.room) return null
    val path = world.pathTo(mob.room, leader.room)
    if (path == null || path.size > BUBBLE_RADIUS) {
      mob.leader = null
      return null
    }
    // Checking the leader's room, not just the next step, keeps the mob from stepping out after
    // its leader and being sent straight back by the return proposal.
    if (!insideLeash(world, mob, leader)) return null
    return Proposal(path.first().name, priority, "follow", MoveAction(path.first()))
  }

  private fun currentLeader(world: World, mob: Mob): Actor? {
    val leader = mob.leader?.let(world::actor)?.takeIf { it.inWorld && target.matches(it) }
    if (leader != null) {
      if (!world.above(mob, leader, dispositionAbove)) {
        mob.leader = null
        return null
      }
      return leader
    }
    mob.leader = world.others(mob).firstOrNull { isLeaderCandidate(world, mob, it) }?.id
    return null
  }

  private fun isLeaderCandidate(world: World, mob: Mob, a: Actor): Boolean {
    if (!target.matches(a)) return false
    return world.above(mob, a, dispositionAbove)
  }

  private fun insideLeash(world: World, mob: Mob, leader: Actor): Boolean {
    val area = world.leashArea(mob)
    if (area == null) return true
    return mob.room in area && leader.room in area
  }

  override fun holdsReturn(world: World, mob: Mob): Boolean {
    val leader = mob.leader?.let(world::actor) ?: return false
    return leader.inWorld &&
      leader.room === mob.room &&
      target.matches(leader) &&
      world.leashArea(mob)?.contains(mob.room) != false
  }
}

/**
 * `pursue(target, range=10)`: heads for the nearest simulated matching actor within `range` exits,
 * searching only rooms inside the mob's leash.
 */
data class Pursue(
  val target: ActorFilter,
  val dispositionBelow: Int? = null,
  val priority: Int = PRIORITY_PURSUE,
  /** Exits searched from the pursuer's room; null searches the whole world. */
  val range: Int? = PURSUE_DEFAULT_RANGE,
) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged) return null
    if (targetNearby(world, mob)) return null
    val area = world.leashArea(mob)
    if (area != null && mob.room !in area) return null
    val (_, step) =
      world.nearestBy(mob.room, range, area) { reachable(world, mob, it) } ?: return null
    return Proposal(step.name, priority, "pursue", MoveAction(step))
  }

  private fun targetNearby(world: World, mob: Mob): Boolean =
    world.others(mob).any { target.matches(it) && world.below(mob, it, dispositionBelow) }

  private fun reachable(world: World, mob: Mob, a: Actor): Boolean {
    if (a === mob) return false
    if (!a.inWorld) return false
    if (a.condition == Condition.DEAD) return false
    if (!target.matches(a)) return false
    if (!world.isSimulated(a)) return false
    return world.below(mob, a, dispositionBelow)
  }

  override fun holdsReturn(world: World, mob: Mob): Boolean =
    world.leashArea(mob)?.contains(mob.room) != false &&
      world.others(mob).any { target.matches(it) && world.below(mob, it, dispositionBelow) }
}

/** `heel()`: follows the mob's owner by the shortest path, however far. */
data class Heel(val priority: Int = PRIORITY_HEEL) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged) return null
    val owner = world.ownerOf(mob) ?: return null
    if (owner.room === mob.room) return null
    val path = world.pathTo(mob.room, owner.room)?.takeIf { it.isNotEmpty() } ?: return null
    return Proposal(path.first().name, priority, "heel", MoveAction(path.first()))
  }
}

/**
 * `defend(group)`: joins the fights of the mob's owner, and with [group] its in-room group members.
 */
data class Defend(val group: Boolean = true, val priority: Int = PRIORITY_DEFEND) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features || mob.isEngaged) return null
    val owner = world.ownerOf(mob) ?: return null
    for (c in defenders(world, mob, owner)) {
      val foe = pickFoe(world, mob, c) ?: continue
      return Proposal(
        "${world.commands.nameOf("kill")} ${foe.keywords.first()}",
        priority,
        "defend",
        KillAction(foe),
      )
    }
    return null
  }

  private fun defenders(world: World, mob: Mob, owner: Player): List<Actor> = buildList {
    if (owner.room === mob.room) add(owner)
    if (group) addAll(inRoomGroup(world, mob, owner))
  }

  private fun inRoomGroup(world: World, mob: Mob, owner: Player): List<Player> =
    world.groupMembers(owner).filter { it !== owner && it.room === mob.room }.sortedBy { it.id }

  private fun pickFoe(world: World, mob: Mob, c: Actor): Actor? {
    if (!c.isEngaged) return null
    val foes = world.opponentsOf(c).filter { it !== mob && !world.isOwnerSide(mob, it) }
    return foes.firstOrNull { it.id == c.primaryTarget } ?: foes.firstOrNull()
  }
}

/** The behaviors [mob] runs now: its owned list while it has an owner, else its prototype's. */
fun World.behaviorsOf(mob: Mob): List<Behavior> =
  if (mob.owner != null) mob.ownedBehaviors ?: defaultOwnedBehaviors(mob) else mob.proto.behaviors

/** `sentinel()`: never leaves its room. Removes other behaviors' moves and flights. */
data object Sentinel : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? = null

  fun allows(p: Proposal): Boolean = p.action !is MoveAction && p.action !== FleeAction
}

/**
 * `scavenge()`: picks up the first item on the ground it can reach and carry. Corpses are left
 * alone.
 */
data class Scavenge(val priority: Int = PRIORITY_SCAVENGE) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged) return null
    val item = mob.room.items.firstOrNull { world.canScavenge(mob, it) } ?: return null
    return Proposal(
      "${world.commands.nameOf("get")} ${item.keywords.first()}",
      priority,
      "scavenge",
      GetAction("", fixed = item),
    )
  }
}

private fun World.canScavenge(mob: Mob, item: Item): Boolean {
  if (item.kind == "corpse") return false
  if (Feature.TRACK in features) {
    val band = Band.between(mob.trackIndex, item.groundIndex ?: return false)
    if (rules.getLag(RulesContext(this, mob, item = item, band = band), band) == null) return false
  }
  return mob.carriedWeight() + item.weight <= rules.carryLimit(RulesContext(this, mob, item = item))
}
