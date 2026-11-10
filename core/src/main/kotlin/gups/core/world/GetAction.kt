package gups.core.world

import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.DoorState
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.rules.RulesContext

private val FROM = Regex("\\s+from\\s+", RegexOption.IGNORE_CASE)

/** What a `get` command names: the items wanted, and the container they come out of, if any. */
private sealed interface GetSource {
  class Found(val spec: ItemSpec, val container: Item?) : GetSource

  class Failed(val message: Msg) : GetSource
}

/**
 * The outcome of picking up one stack: the lag it cost, and whether the carry limit cut it short.
 */
private class Taken(val lag: Int, val capped: Boolean)

/**
 * `get <what> [[from] <container>]`, with lag by band from `rules.get_lag`. The item is resolved
 * when the action runs, so several queued gets each act on the state the previous left. A `fixed`
 * item (from a behavior) is picked up alone, in full, with no container.
 */
internal class GetAction(private val args: String, private val fixed: Item? = null) : Action {
  private var spec: ItemSpec? = null
  private var container: Item? = null
  private var carriedSource = false

  override fun validate(w: World, actor: Actor): String? {
    val failure = if (fixed != null) validateFixed(w, actor) else parseArgs(w, actor)
    if (failure != null) return failure
    carriedSource = container?.let { w.custodian(it) } === actor
    return validateContainer(w, actor) ?: validateMatches(w, actor)
  }

  private fun validateFixed(w: World, actor: Actor): String? {
    val item = fixed ?: return null
    return if (isPickable(w, actor, item)) null else w.msg(KernelMsg.DONT_SEE)
  }

  private fun validateContainer(w: World, actor: Actor): String? {
    val box = container ?: return null
    if (!w.isPresent(box, w.roomOf(actor))) return w.msg(KernelMsg.DONT_SEE)
    val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)
    if (state.state != DoorState.OPEN) return w.msg(KernelMsg.PUT_CLOSED, "container" to box.name)
    return w.reach(actor, box)
  }

  private fun validateMatches(w: World, actor: Actor): String? {
    val matches = wanted(w, actor)
    if (matches.isEmpty()) return w.msg(KernelMsg.DONT_SEE)
    if (matches.all { lag(w, actor, it.first) == null })
      return w.msg(KernelMsg.CANT_REACH, "item" to matches.first().first.name)
    if (!carriedSource && matches.none { w.carryFit(actor, it.first, 1) > 0 })
      return w.msg(KernelMsg.CANT_CARRY)
    return null
  }

  /** Parses [args] into [spec] and [container]; returns the message to tell instead, or null. */
  private fun parseArgs(w: World, actor: Actor): String? {
    val source =
      when {
        FROM.containsMatchIn(args) -> parseFrom(w, actor)
        args.none { it.isWhitespace() } -> parseBare()
        else -> parseTrailingContainer(w, actor)
      }
    return when (source) {
      is GetSource.Failed -> w.msg(source.message)
      is GetSource.Found -> {
        spec = source.spec
        container = source.container
        null
      }
    }
  }

  private fun parseFrom(w: World, actor: Actor): GetSource {
    val (whatStr, fromStr) = args.splitAtLast(FROM) ?: return GetSource.Failed(KernelMsg.GET_WHAT)
    val s = parseItemSpec(whatStr) ?: return GetSource.Failed(KernelMsg.GET_WHAT)
    val box =
      w.findItem(actor, fromStr, inventory = true, ground = true)
        ?: return GetSource.Failed(KernelMsg.DONT_SEE)
    return GetSource.Found(s, box)
  }

  private fun parseBare(): GetSource =
    parseItemSpec(args)?.let { GetSource.Found(it, null) } ?: GetSource.Failed(KernelMsg.GET_WHAT)

  /** `get <what> <container>` with no `from`: the last word may name a container. */
  private fun parseTrailingContainer(w: World, actor: Actor): GetSource {
    val words = args.trim().split(Regex("\\s+"))
    val box =
      w.findItem(actor, words.last(), inventory = true, ground = true)?.takeIf {
        it.container != null
      }
    val boxSpec = parseItemSpec(words.dropLast(1).joinToString(" "))
    if (box == null || boxSpec == null) return parseBare()
    // The container wins: `all <container>` loots it, or an open container holds a match. A closed
    // container wins too, and `validate` reports it closed.
    if (containerWins(box, boxSpec)) return GetSource.Found(boxSpec, box)
    // The box finds nothing (closed, or no matching contents). The whole phrase is a ground item
    // if one matches, else the container still wins.
    val whole = parseItemSpec(args)
    if (whole != null && matchItems(w.roomOf(actor).items, whole).isNotEmpty())
      return GetSource.Found(whole, null)
    return GetSource.Found(boxSpec, box)
  }

  private fun containerWins(box: Item, boxSpec: ItemSpec) =
    boxSpec.all ||
      (box.container?.state == DoorState.OPEN && matchItems(box.contents, boxSpec).isNotEmpty())

  /** The source stacks and the units wanted from each, recomputed from the live pool. */
  private fun wanted(w: World, actor: Actor): List<Pair<Item, Int>> {
    val f = fixed
    if (f != null) return if (isPickable(w, actor, f)) listOf(f to f.count) else emptyList()
    val s = spec ?: return emptyList()
    return matchItems(pool(w, actor), s)
  }

  /** The stacks the spec is matched against: the container's contents, or the room's items. */
  private fun pool(w: World, actor: Actor): List<Item> {
    val box = container ?: return w.roomOf(actor).items
    return if (box.id in w.entities && w.isPresent(box, w.roomOf(actor))) box.contents
    else emptyList()
  }

  /** True if [item] still exists and lies in the actor's room. */
  private fun isPickable(w: World, actor: Actor, item: Item): Boolean =
    item.id in w.entities && isOnGround(w, actor, item)

  private fun isOnGround(w: World, actor: Actor, item: Item): Boolean {
    val loc = item.location
    return loc is ItemLocation.InRoom && loc.room == w.roomOf(actor).id
  }

  /** True if [item] is still in the container, or on the ground in the actor's room. */
  private fun atSource(w: World, actor: Actor, item: Item): Boolean {
    val box = container ?: return isOnGround(w, actor, item)
    return item.location == ItemLocation.Contained(box.id)
  }

  private fun lag(w: World, actor: Actor, item: Item): Int? {
    if (Feature.TRACK !in w.features) return 0
    val band = bandOf(w, actor, item)
    return w.rules.getLag(RulesContext(w, actor, item = item, band = band), band)
  }

  private fun bandOf(w: World, actor: Actor, item: Item): Band {
    val ground = container ?: item
    return ground.groundIndex?.let { Band.between(w.indexOf(actor), it) } ?: Band.MELEE
  }

  override fun resolve(w: World, actor: Actor): Int {
    var maxLag = 0
    var capped = false
    for ((item, wanted) in wanted(w, actor)) {
      val taken = take(w, actor, item, wanted) ?: continue
      maxLag = maxOf(maxLag, taken.lag)
      capped = capped || taken.capped
    }
    if (capped) w.tell(actor, KernelMsg.CANT_CARRY)
    return maxLag
  }

  /** Picks up up to [wanted] units of [item], or null if it is skipped. */
  private fun take(w: World, actor: Actor, item: Item, wanted: Int): Taken? {
    if (!atSource(w, actor, item)) return null
    val lag = lag(w, actor, item)
    if (lag == null) {
      w.send(actor, w.msg(KernelMsg.CANT_REACH, "item" to item.name))
      return null
    }
    val veto =
      w.dispatch(HookName.BEFORE_GET, item, actor.room, HookContext(actor = actor, item = item))
    if (veto != null) {
      w.send(actor, veto)
      return null
    }
    // The hook may have destroyed or moved the stack.
    if (item.id !in w.entities || !atSource(w, actor, item)) return null
    var n = minOf(wanted, item.count)
    var capped = false
    if (!carriedSource) {
      val fit = w.carryFit(actor, item, n)
      capped = fit < n
      n = minOf(n, fit)
      if (n <= 0) return Taken(0, capped)
    }
    moveToActor(w, actor, item, n)
    return Taken(lag, capped)
  }

  private fun moveToActor(w: World, actor: Actor, item: Item, n: Int) {
    val moved = w.moveItem(item, ItemLocation.Carried(actor.id), n)
    val desc = moved.describe()
    val box = container
    w.send(
      actor,
      if (box != null) w.msg(KernelMsg.GET_SELF_FROM, "item" to desc, "container" to box.name)
      else w.msg(KernelMsg.GET_SELF, "item" to desc),
    )
    w.tellSight(
      actor.room,
      "get",
      actor,
      w.msg(KernelMsg.GET_ROOM, "actor" to actor.name.cap(), "item" to desc),
      actor,
    )
    w.dispatch(HookName.ON_GET, moved, actor.room, HookContext(actor = actor, item = moved))
    if (moved.id in w.entities) w.stack(moved)
  }
}
