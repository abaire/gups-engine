package gups.core.world

import gups.core.model.Actor
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.TargetKind
import gups.core.rules.RulesContext

/** What a `use` is aimed at: an actor, an item, or neither. */
private class UseTargets(val actor: Actor? = null, val item: Item? = null)

/** Tells the actor [message] and returns null, so a planner can bail out in one expression. */
private fun CommandCall.refuse(message: Msg, vararg args: Pair<String, Any?>): Nothing? {
  world.tell(actor, message, *args)
  return null
}

/**
 * `use <item> [target]`: an item worn, carried, or on the ground within reach whose `before_use`
 * doesn't veto. Resolves target by `useTarget` when `useEffect` is present, applies the effect,
 * fires `on_use`, spends a charge, and applies recovery lag.
 */
internal fun planUse(c: CommandCall): Action? {
  val w = c.world
  val words = c.args.split(Regex("\\s+"), limit = 2)
  if (words[0].isEmpty()) return c.refuse(KernelMsg.USE_WHAT)
  val item =
    (c.actor.equipment.values.firstOrNull { it.matches(words[0]) }
      ?: w.findItem(c.actor, words[0], inventory = true, ground = true))
      ?: return c.refuse(KernelMsg.DONT_SEE)
  // Checked before the target too, so an empty item isn't answered with a target prompt.
  if (item.charges == 0) return c.refuse(KernelMsg.USE_EMPTY, "item" to item.name)
  val word = words.getOrNull(1)?.trim()?.removePrefix("on ")?.trim()?.ifEmpty { null }
  val targets = resolveUseTargets(c, item, word) ?: return null
  val announce = item.proto.useEffect != null && !w.scripts.hasHook(item, HookName.ON_USE)
  return UseAction(item, targets, announce)
}

private fun resolveUseTargets(c: CommandCall, item: Item, word: String?): UseTargets? {
  if (item.proto.useEffect == null) return resolveLooseTargets(c, word)
  return when (item.proto.useTarget) {
    TargetKind.SELF ->
      if (word != null) c.refuse(KernelMsg.USE_SELF_ONLY, "item" to item.name)
      else UseTargets(actor = c.actor)
    TargetKind.ALLY -> resolveAlly(c, word)
    TargetKind.ENEMY -> resolveEnemy(c, item, word)
    TargetKind.ITEM -> resolveItemTarget(c, item, word)
    TargetKind.NONE,
    TargetKind.ROOM -> UseTargets()
  }
}

/** An item with no `useEffect`: the word, if any, names an actor or else an item for its hooks. */
private fun resolveLooseTargets(c: CommandCall, word: String?): UseTargets? {
  val w = c.world
  val target = word?.let { w.findActor(c.actor, it) }
  if (word == null || target != null) return UseTargets(actor = target)
  val targetItem =
    w.findItem(c.actor, word, inventory = true, ground = true)
      ?: return c.refuse(KernelMsg.DONT_SEE)
  return UseTargets(item = targetItem)
}

private fun resolveAlly(c: CommandCall, word: String?): UseTargets? {
  if (word == null) return UseTargets(actor = c.actor)
  val ally = c.world.findActor(c.actor, word) ?: return c.refuse(KernelMsg.NOT_HERE)
  return UseTargets(actor = ally)
}

private fun resolveEnemy(c: CommandCall, item: Item, word: String?): UseTargets? {
  val w = c.world
  if (word != null) {
    val named = w.findActor(c.actor, word) ?: return c.refuse(KernelMsg.NOT_HERE)
    return UseTargets(actor = named)
  }
  val current =
    c.actor.primaryTarget?.let(w::actor)
      ?: return c.refuse(KernelMsg.USE_AT_WHOM, "item" to item.name)
  return UseTargets(actor = current)
}

private fun resolveItemTarget(c: CommandCall, item: Item, word: String?): UseTargets? {
  val w = c.world
  val itemWord = word ?: return c.refuse(KernelMsg.USE_ON_WHAT, "item" to item.name)
  val targetItem =
    c.actor.equipment.values.firstOrNull { it.matches(itemWord) }
      ?: w.findItem(c.actor, itemWord, inventory = true, ground = true)
      ?: return c.refuse(KernelMsg.DONT_SEE)
  return UseTargets(item = targetItem)
}

private class UseAction(
  private val item: Item,
  targets: UseTargets,
  private val announce: Boolean,
) : Action {
  private val target = targets.actor
  private val targetItem = targets.item
  private val useLag = item.proto.useLag
  private val effect = item.proto.useEffect
  private val useTarget = item.proto.useTarget

  private fun hookContext(actor: Actor) =
    HookContext(actor = actor, item = item, target = target, targetItem = targetItem)

  override fun validate(w: World, actor: Actor): String? {
    reachRefusal(w, actor)?.let {
      return it
    }
    targetRefusal(w, actor)?.let {
      return it
    }
    if (!w.scripts.hasHook(item, HookName.ON_USE) && item.proto.useEffect == null)
      return w.msg(KernelMsg.USE_CANT, "item" to item.name)
    if (item.charges == 0) return w.msg(KernelMsg.USE_EMPTY, "item" to item.name)
    return null
  }

  /** Why the actor can't get at the item, or null if it is held or within reach. */
  private fun reachRefusal(w: World, actor: Actor): String? {
    val held =
      item.location == ItemLocation.Carried(actor.id) ||
        (item.location as? ItemLocation.Equipped)?.actor == actor.id
    if (held) return null
    if ((item.location as? ItemLocation.InRoom)?.room != w.roomOf(actor).id)
      return w.msg(KernelMsg.DONT_SEE)
    return w.reach(actor, item)
  }

  private fun targetRefusal(w: World, actor: Actor): String? {
    val room = w.roomOf(actor)
    if (target != null && !w.isPresent(target, room)) return w.msg(KernelMsg.NOT_HERE)
    if (targetItem == null) return null
    if (w.item(targetItem.id) !== targetItem || !w.isPresent(targetItem, room))
      return w.msg(KernelMsg.DONT_SEE)
    return w.reach(actor, targetItem)
  }

  override fun veto(w: World, actor: Actor): String? =
    w.dispatch(HookName.BEFORE_USE, item, actor.room, hookContext(actor))

  override fun resolve(w: World, actor: Actor): Int {
    if (announce) announceUse(w, actor)
    if (effect != null) applyUseEffect(w, actor, effect)
    w.dispatch(HookName.ON_USE, item, actor.room, hookContext(actor))
    if (w.item(item.id) === item) w.spendCharge(item)
    return useLag
  }

  private fun announceUse(w: World, actor: Actor) {
    w.tell(actor, KernelMsg.USE_DONE, "item" to item.name)
    w.tellSight(
      actor.room,
      "use",
      actor,
      w.msg(KernelMsg.USE_DONE_ROOM, "actor" to actor.name.cap(), "item" to item.name),
      actor,
    )
  }

  private fun applyUseEffect(w: World, actor: Actor, effect: Effect) {
    val power = w.itemPowerChain(RulesContext(w, actor, target, item = item, event = "use"))
    if (useTarget == TargetKind.ITEM) {
      targetItem?.let { w.applyItemEffect(effect, actor, it, power = power) }
    } else {
      w.applyEffect(effect, actor, target, EffectContext(power = power))
    }
  }
}
