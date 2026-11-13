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

private fun requireCarried(actor: Actor, item: Item) =
  require(item.isCarriedBy(actor)) { "${item.name} is not carried by ${actor.name}" }

/**
 * Asks the DROP restriction about [actor] dropping [item], without moving it or running a hook.
 * Returns the refusal message, or null; an item [actor] doesn't carry is an error.
 */
fun World.mayDrop(actor: Actor, item: Item): String? {
  requireCarried(actor, item)
  return refusalFor(RestrictionRequest(point = RestrictionPoint.DROP, actor = actor, item = item))
}

/** Why [actor] can't hand anything to [target]: not here, or the actor itself. */
private fun World.giveTargetRefusal(actor: Actor, target: Actor): String? =
  when {
    !isPresent(target, roomOf(actor)) -> msg(KernelMsg.NOT_HERE)
    target === actor -> msg(KernelMsg.GIVE_ALREADY)
    else -> null
  }

/**
 * Asks what `give` would refuse [actor] handing [item] to [target]: a receiver who isn't in the
 * room or is the giver, and the GIVE restriction. Moves nothing and runs no hook; says nothing
 * about the receiver's carry limit. An item [actor] doesn't carry is an error.
 */
fun World.mayGive(actor: Actor, item: Item, target: Actor): String? {
  requireCarried(actor, item)
  return giveTargetRefusal(actor, target)
    ?: refusalFor(
      RestrictionRequest(point = RestrictionPoint.GIVE, actor = actor, item = item, target = target)
    )
}

/** Why nothing can go into [box]: it is closed. */
private fun World.closedRefusal(box: Item): String? =
  if (box.container?.state == DoorState.OPEN) null
  else msg(KernelMsg.PUT_CLOSED, "container" to box.name)

/**
 * Asks what `put` would refuse [actor] putting [item] into [box]: the item itself, a closed
 * container, and the PUT restriction. Moves nothing and runs no hook; says nothing about whether it
 * fits. An item [actor] doesn't carry, or a [box] that isn't a container, is an error.
 */
fun World.mayPut(actor: Actor, item: Item, box: Item): String? {
  requireCarried(actor, item)
  require(box.container != null) { "${box.name} is not a container" }
  if (item === box) return msg(KernelMsg.PUT_ITSELF)
  return closedRefusal(box)
    ?: refusalFor(
      RestrictionRequest(point = RestrictionPoint.PUT, actor = actor, item = item, target = box)
    )
}

private class DropAction(private val spec: ItemSpec) : Action {
  override fun validate(w: World, actor: Actor): String? =
    if (matchItems(actor.inventory, spec).isEmpty()) w.msg(KernelMsg.NOT_CARRYING) else null

  override fun resolve(w: World, actor: Actor): Int {
    for ((item, wanted) in matchItems(actor.inventory, spec)) dropStack(w, actor, item, wanted)
    return 0
  }

  private fun dropStack(w: World, actor: Actor, item: Item, wanted: Int) {
    if (!item.isCarriedBy(actor)) return
    val refusal = w.mayDrop(actor, item)
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
    w.giveTargetRefusal(actor, target)?.let {
      return it
    }
    val matches = matchItems(actor.inventory, spec)
    if (matches.isEmpty()) return w.msg(KernelMsg.NOT_CARRYING)
    if (matches.none { w.carryFit(target, Fitting(it.first), 1) > 0 })
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
    val refusal = w.mayGive(actor, item, target)
    if (refusal != null) {
      w.send(actor, refusal)
      return false
    }
    val n = minOf(wanted, item.count)
    val fit = w.carryFit(target, Fitting(item), n)
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
    if (box.container == null) return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)
    if (!w.isPresent(box, w.roomOf(actor))) return w.msg(KernelMsg.DONT_SEE)
    w.reach(actor, box)?.let {
      return it
    }
    w.closedRefusal(box)?.let {
      return it
    }
    return validateRoomFor(w, box, matches)
  }

  /** True if the spec names the carried container itself, so there is nothing else to put. */
  private fun namesItself(actor: Actor, box: Item) =
    !spec.all && box.isCarriedBy(actor) && spec.keyword != null && box.matches(spec.keyword)

  private fun validateRoomFor(w: World, box: Item, matches: List<Pair<Item, Int>>): String? {
    val rooms = matches.map { BoxRoom(w, box, Fitting(it.first)) }
    if (rooms.none { it.byEntries(1) > 0 })
      return w.msg(KernelMsg.PUT_FULL, "container" to box.name)
    if (rooms.none { it.byWeight(1) > 0 })
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
    if (!item.isCarriedBy(actor) || refused(item)) return
    val room = BoxRoom(w, box, Fitting(item))
    val n = minOf(wanted, item.count)
    if (room.byEntries(n) == 0) {
      shortfall.full = true
      return
    }
    val fit = minOf(n, room.byWeight(n))
    if (fit < n) shortfall.tooHeavy = shortfall.tooHeavy ?: item.name
    if (fit > 0) stow(item, fit)
  }

  /** True if a restriction refuses [item], after telling the actor why. */
  private fun refused(item: Item): Boolean {
    val refusal = w.mayPut(actor, item, box)
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
    return w.removeRefusal(actor, item)
  }

  override fun resolve(w: World, actor: Actor): Int {
    w.moveItem(item, ItemLocation.Carried(actor.id))
    w.tell(actor, KernelMsg.WEAR_STOP, "item" to item.name)
    return 0
  }
}
