package gups.core.world

import gups.core.model.Actor
import gups.core.model.DoorState
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest

private val TO = Regex("\\s+to\\s+", RegexOption.IGNORE_CASE)
private val IN = Regex("\\s+(in|into)\\s+", RegexOption.IGNORE_CASE)

internal fun planGet(c: CommandCall): Action? {
  if (c.args.isBlank()) return null.also { c.world.tell(c.actor, KernelMsg.GET_WHAT) }
  return GetAction(c.args.trim())
}

internal fun planDrop(c: CommandCall): Action? {
  val spec =
    parseItemSpec(c.args) ?: return null.also { c.world.tell(c.actor, KernelMsg.NOT_CARRYING) }
  return DropAction(spec)
}

/**
 * `give <what> [to] <target>`: hands one item, a count, or all matching items over; fires `on_give`
 * once per stack moved. The items are resolved when the action runs, so several queued gives each
 * act on the state the previous left.
 */
internal fun planGive(c: CommandCall): Action? {
  val (whatStr, targetStr) =
    c.args.splitAtLast(TO) ?: return null.also { c.world.tell(c.actor, KernelMsg.GIVE_USAGE) }
  val spec =
    parseItemSpec(whatStr) ?: return null.also { c.world.tell(c.actor, KernelMsg.GIVE_USAGE) }
  return GiveAction(spec, targetStr)
}

/**
 * `put <what> [in|into] <container>`: the container is carried or on the ground within reach, and
 * open. Moves one item, a count, or all matching items, never the container itself; the items are
 * resolved when the action runs, so several queued puts each act on the state the previous left.
 */
internal fun planPut(c: CommandCall): Action? {
  val (whatStr, boxStr) =
    c.args.splitAtLast(IN) ?: return null.also { c.world.tell(c.actor, KernelMsg.PUT_USAGE) }
  val spec =
    parseItemSpec(whatStr) ?: return null.also { c.world.tell(c.actor, KernelMsg.PUT_USAGE) }
  return PutAction(spec, boxStr)
}

internal fun planRemove(c: CommandCall): Action? {
  val item =
    c.actor.equipment.values.firstOrNull { it.matches(c.args) }
      ?: return null.also { c.world.tell(c.actor, KernelMsg.NOT_USING) }
  return RemoveAction(item)
}

private fun Item.isCarriedBy(actor: Actor) = location == ItemLocation.Carried(actor.id)

private class DropAction(private val spec: ItemSpec) : Action {
  override fun validate(w: World, actor: Actor): String? =
    if (matchItems(actor.inventory, spec).isEmpty()) w.msg(KernelMsg.NOT_CARRYING) else null

  override fun resolve(w: World, actor: Actor): Int {
    for ((item, wanted) in matchItems(actor.inventory, spec)) dropStack(w, actor, item, wanted)
    return 0
  }

  private fun dropStack(w: World, actor: Actor, item: Item, wanted: Int) {
    if (!item.isCarriedBy(actor)) return
    val refusal =
      w.refusalFor(RestrictionRequest(point = RestrictionPoint.DROP, actor = actor, item = item))
    if (refusal != null) return w.send(actor, refusal)
    val veto =
      w.dispatch(HookName.BEFORE_DROP, item, actor.room, HookContext(actor = actor, item = item))
    if (veto != null) return w.send(actor, veto)
    // The hook may have destroyed or moved the stack.
    if (item.id !in w.entities || !item.isCarriedBy(actor)) return
    val moved =
      w.moveItem(
        item,
        ItemLocation.InRoom(actor.room.id, actor.trackIndex),
        minOf(wanted, item.count),
      )
    val desc = moved.describe()
    w.tell(actor, KernelMsg.DROP_SELF, "item" to desc)
    w.tellSight(
      actor.room,
      "drop",
      actor,
      w.msg(KernelMsg.DROP_ROOM, "actor" to actor.name.cap(), "item" to desc),
      actor,
    )
    w.dispatch(HookName.ON_DROP, moved, actor.room, HookContext(actor = actor, item = moved))
    if (moved.id in w.entities) w.stack(moved)
  }
}

private class GiveAction(private val spec: ItemSpec, private val targetStr: String) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val target = w.findActor(actor, targetStr) ?: return w.msg(KernelMsg.NOT_HERE)
    if (target === actor) return w.msg(KernelMsg.GIVE_ALREADY)
    val matches = matchItems(actor.inventory, spec)
    if (matches.isEmpty()) return w.msg(KernelMsg.NOT_CARRYING)
    if (matches.none { w.carryFit(target, it.first, 1) > 0 })
      return w.msg(KernelMsg.GIVE_FULL, "target" to target.name)
    return null
  }

  override fun resolve(w: World, actor: Actor): Int {
    val target = w.findActor(actor, targetStr) ?: return 0
    val giver = Giver(w, actor, target)
    var capped = false
    for ((item, wanted) in matchItems(actor.inventory, spec)) {
      if (giver.give(item, wanted)) capped = true
    }
    if (capped) w.tell(actor, KernelMsg.GIVE_FULL, "target" to target.name)
    return 0
  }
}

/** Hands stacks from [actor] to [target]. */
private class Giver(private val w: World, private val actor: Actor, private val target: Actor) {
  /** Gives up to [wanted] units of [item]; true if the target's carry limit cut it short. */
  fun give(item: Item, wanted: Int): Boolean {
    // An on_give hook for an earlier stack may have destroyed or moved this one.
    if (item.id !in w.entities || !item.isCarriedBy(actor)) return false
    val refusal =
      w.refusalFor(
        RestrictionRequest(
          point = RestrictionPoint.GIVE,
          actor = actor,
          item = item,
          target = target,
        )
      )
    if (refusal != null) {
      w.send(actor, refusal)
      return false
    }
    val n = minOf(wanted, item.count)
    val fit = w.carryFit(target, item, n)
    if (fit > 0) handOver(item, minOf(n, fit))
    return fit < n
  }

  private fun handOver(item: Item, n: Int) {
    val moved = w.moveItem(item, ItemLocation.Carried(target.id), n)
    val desc = moved.describe()
    w.tell(actor, KernelMsg.GIVE_SELF, "item" to desc, "target" to target.name)
    w.tell(target, KernelMsg.GIVE_TARGET, "actor" to actor.name.cap(), "item" to desc)
    w.tellSight(
      actor.room,
      "give",
      actor,
      w.msg(
        KernelMsg.GIVE_ROOM,
        "actor" to actor.name.cap(),
        "item" to desc,
        "target" to target.name,
      ),
      actor,
      target,
    )
    w.raiseStandingEvent(
      StandingEvent("give", affected = target, actor = actor, item = moved, amount = n.toLong())
    )
    w.dispatch(
      HookName.ON_GIVE,
      moved,
      actor.room,
      HookContext(actor = actor, item = moved, target = target),
    )
    if (moved.id in w.entities) w.stack(moved)
  }
}

/** What went wrong across the stacks of one `put`, reported once at the end. */
private class PutShortfall {
  var full = false
  var tooHeavy: String? = null
}

private class PutAction(private val spec: ItemSpec, private val boxStr: String) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val box =
      w.findItem(actor, boxStr, inventory = true, ground = true) ?: return w.msg(KernelMsg.DONT_SEE)
    val matches = matchItems(actor.inventory, spec, exclude = box)
    if (matches.isEmpty()) {
      return w.msg(if (namesItself(actor, box)) KernelMsg.PUT_ITSELF else KernelMsg.NOT_CARRYING)
    }
    val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)
    if (!w.isPresent(box, w.roomOf(actor))) return w.msg(KernelMsg.DONT_SEE)
    w.reach(actor, box)?.let {
      return it
    }
    if (state.state != DoorState.OPEN) return w.msg(KernelMsg.PUT_CLOSED, "container" to box.name)
    return validateRoomFor(w, box, matches)
  }

  /** True if the spec names the carried container itself, so there is nothing else to put. */
  private fun namesItself(actor: Actor, box: Item) =
    !spec.all && box.isCarriedBy(actor) && spec.keyword != null && box.matches(spec.keyword)

  private fun validateRoomFor(w: World, box: Item, matches: List<Pair<Item, Int>>): String? {
    val state = box.container ?: return null
    if (matches.none { w.mergesInto(it.first, box) || box.contents.size < state.capacity })
      return w.msg(KernelMsg.PUT_FULL, "container" to box.name)
    val free = state.maxWeight - box.contents.sumOf { it.weight }
    if (matches.none { fitWithin(free, it.first, 1) > 0 })
      return w.msg(
        KernelMsg.PUT_WONT_FIT,
        "item" to matches.first().first.name,
        "container" to box.name,
      )
    return null
  }

  override fun resolve(w: World, actor: Actor): Int {
    val box = w.findItem(actor, boxStr, inventory = true, ground = true) ?: return 0
    if (box.container == null) return 0
    val stower = Stower(w, actor, box)
    for ((item, wanted) in matchItems(actor.inventory, spec, exclude = box)) {
      stower.put(item, wanted)
    }
    val shortfall = stower.shortfall
    val tooHeavy = shortfall.tooHeavy
    if (tooHeavy != null)
      w.tell(actor, KernelMsg.PUT_WONT_FIT, "item" to tooHeavy, "container" to box.name)
    else if (shortfall.full) w.tell(actor, KernelMsg.PUT_FULL, "container" to box.name)
    return 0
  }
}

/** Moves stacks from [actor] into [box], noting what didn't fit. */
private class Stower(private val w: World, private val actor: Actor, private val box: Item) {
  val shortfall = PutShortfall()

  fun put(item: Item, wanted: Int) {
    val state = box.container ?: return
    if (!item.isCarriedBy(actor) || refused(item)) return
    if (box.contents.size >= state.capacity && !w.mergesInto(item, box)) {
      shortfall.full = true
      return
    }
    val n = unitsThatFit(item, minOf(wanted, item.count), state.maxWeight)
    if (n > 0) stow(item, n)
  }

  /** How many of the [n] units fit within [maxWeight], noting it when some don't. */
  private fun unitsThatFit(item: Item, n: Int, maxWeight: Int): Int {
    val fit = fitWithin(maxWeight - box.contents.sumOf { it.weight }, item, n)
    if (fit < n) shortfall.tooHeavy = shortfall.tooHeavy ?: item.name
    return minOf(n, fit)
  }

  /** True if a restriction refuses [item], after telling the actor why. */
  private fun refused(item: Item): Boolean {
    val refusal =
      w.refusalFor(
        RestrictionRequest(point = RestrictionPoint.PUT, actor = actor, item = item, target = box)
      )
    if (refusal != null) w.send(actor, refusal)
    return refusal != null
  }

  private fun stow(item: Item, n: Int) {
    val moved = w.moveItem(item, ItemLocation.Contained(box.id), n)
    val desc = moved.describe()
    w.tell(actor, KernelMsg.PUT_SELF, "item" to desc, "container" to box.name)
    w.tellSight(
      actor.room,
      "put",
      actor,
      w.msg(
        KernelMsg.PUT_ROOM,
        "actor" to actor.name.cap(),
        "item" to desc,
        "container" to box.name,
      ),
      actor,
    )
    w.stack(moved)
  }
}

private class RemoveAction(private val item: Item) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val location = item.location as? ItemLocation.Equipped
    if (location == null || location.actor != actor.id) return w.msg(KernelMsg.NOT_USING)
    return w.refusalFor(
      RestrictionRequest(
        point = RestrictionPoint.REMOVE,
        actor = actor,
        item = item,
        slot = location.slot,
      )
    )
  }

  override fun resolve(w: World, actor: Actor): Int {
    w.moveItem(item, ItemLocation.Carried(actor.id))
    w.tell(actor, KernelMsg.WEAR_STOP, "item" to item.name)
    return 0
  }
}
