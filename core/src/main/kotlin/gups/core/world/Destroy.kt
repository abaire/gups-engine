package gups.core.world

import gups.core.EntityId
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Player

/** Why an item is destroyed: `ctx.event` of its `on_destroy`. */
enum class DestroyCause(val word: String) {
  VERB("destroy"),
  DECAY("decay"),
  EMPTY("empty"),
}

/** True while [item] is the root of, or still inside the root of, a destroy whose hooks run. */
fun World.isBeingDestroyed(item: Item): Boolean =
  destroyRoots.isNotEmpty() && withContainers(item).any { it.id in destroyRoots }

/** [item], then each container it sits in, outward. */
private fun World.withContainers(item: Item): Sequence<Item> =
  generateSequence(item) { cur ->
    (cur.location as? ItemLocation.Contained)?.let { item(it.container) }
  }

/**
 * The `destroy` verb: removes [item] and everything inside it, firing `on_destroy` on each first,
 * or removes only [count] of a stack, firing nothing. Does nothing to an item already inside a
 * running destroy.
 */
fun World.destroy(item: Item, count: Int = item.count, cause: DestroyCause = DestroyCause.VERB) {
  require(item.id in entities) { "$item is not in the world" }
  require(count in 1..item.count) {
    "destroy: count must be between 1 and ${item.count}, got $count"
  }
  if (isBeingDestroyed(item)) return
  if (count < item.count) {
    val holder = custodian(item)
    if (holder is Player && !item.debug)
      itemEvents += ItemEvent(item.id, pulse, item.location, item.location, "reduce")
    item.count -= count
    item.dirty = true
    holder?.let { refreshHoldings(it) }
    return
  }
  destroyRoots += item.id
  val notified = HashSet<EntityId>()
  try {
    visit(item, item, cause, notified)
    if (item.id in entities) removeItem(item)
  } finally {
    destroyRoots -= item.id
    destroyNotified -= notified
  }
}

private fun World.visit(root: Item, x: Item, cause: DestroyCause, notified: MutableSet<EntityId>) {
  if (!awaitsVisit(root, x, notified)) return
  // A hook can destroy a container of a running destroy's root. Items whose hooks already ran in
  // that outer destroy don't run them again, but what they hold is still walked.
  if (!notifiedByOuterDestroy(root, x)) {
    notified += x.id
    destroyNotified += x.id
    fireDestroyHooks(root, x, cause)
  }
  for (inner in x.contentsIndex.values.sortedBy { it.id }) {
    visit(root, inner, cause, notified)
  }
}

private fun World.inTree(root: Item, x: Item): Boolean = withContainers(x).any { it === root }

/** False once [x] has left the world or the tree, or already had its turn in this destroy. */
private fun World.awaitsVisit(root: Item, x: Item, notified: Set<EntityId>): Boolean {
  if (x.id !in entities || x.id in notified) return false
  return inTree(root, x)
}

private fun World.notifiedByOuterDestroy(root: Item, x: Item): Boolean =
  x.id in destroyNotified && withContainers(x).any { it !== root && it.id in destroyRoots }

private fun World.fireDestroyHooks(root: Item, x: Item, cause: DestroyCause) {
  val room = roomOfEntity(x)
  val ctx =
    HookContext(
      item = x,
      actor = custodian(x),
      room = room,
      event = cause.word,
      destroyedRoot = root,
    )
  // The root is always in its own tree, so only contents that moved out of it escape. Anything a
  // hook removed from the world gets no further hooks.
  fun escaped() = x.id !in entities || !inTree(root, x)
  // Pass room = null so observers and other items in the room don't run on_destroy.
  dispatch(HookName.ON_DESTROY, x, null, ctx)
  if (escaped()) return
  room?.let { dispatch(HookName.ON_DESTROY, it, null, ctx) }
  if (escaped()) return
  dispatchGlobalItem(HookName.ON_DESTROY, ctx, x)
}
