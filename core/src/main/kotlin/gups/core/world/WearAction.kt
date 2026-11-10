package gups.core.world

import gups.core.model.Actor
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest

/**
 * `wear <item>` and `wield <item>`: equips a carried item. `wield` accepts items whose `wear` is
 * `wield`, the family that contains `wield`, or another slot of that family. `wear` accepts any
 * slotted item. Slot choice and displacement follow slot families, per-actor slots, and slot costs.
 * Swapping out a single item checks the REMOVE restriction point.
 */
internal fun planWear(c: CommandCall, wield: Boolean): Action? {
  val w = c.world
  val item =
    w.findItem(c.actor, c.args, inventory = true, ground = false)
      ?: return null.also { w.tell(c.actor, KernelMsg.NOT_CARRYING) }
  val wanted = item.proto.wear
  if (wanted == null || !w.canEquip(wanted, wield)) {
    return null.also { w.tell(c.actor, cantMessage(wield), "item" to item.name) }
  }
  return WearAction(item, wield)
}

private fun cantMessage(wield: Boolean) =
  if (wield) KernelMsg.WEAR_CANT_WIELD else KernelMsg.WEAR_CANT_WEAR

/** True if an item whose `wear` is [wanted] can be worn, or for [wield], wielded. */
private fun World.canEquip(wanted: String, wield: Boolean): Boolean {
  if (!wield) return true
  val wieldFamily = familyOfSlot("wield")
  return (wieldFamily != null && familyOfWear(wanted) == wieldFamily) || wanted == "wield"
}

private class WearAction(private val item: Item, private val wield: Boolean) : Action {
  private var validatedPlan: SlotPlan? = null

  /** Why [plan] can't go ahead, or null if it can. */
  private fun refusal(w: World, actor: Actor, plan: SlotPlan?): String? {
    if (plan == null) return w.msg(cantMessage(wield), "item" to item.name)
    if (plan.ambiguous) return w.msg(KernelMsg.WEAR_NO_ROOM, "item" to item.name)
    if (plan.displaced.size >= 2) {
      return w.msg(
        KernelMsg.WEAR_REMOVE_FIRST,
        "items" to plan.displaced.joinToString(", ") { it.name },
      )
    }
    val request =
      RestrictionRequest(
        point = RestrictionPoint.WEAR,
        actor = actor,
        item = item,
        slot = plan.slot,
      )
    return w.refusalFor(request) ?: removalRefusal(w, actor, plan.displaced.singleOrNull())
  }

  private fun removalRefusal(w: World, actor: Actor, occupant: Item?): String? {
    if (occupant == null) return null
    return w.refusalFor(
      RestrictionRequest(
        point = RestrictionPoint.REMOVE,
        actor = actor,
        item = occupant,
        slot = (occupant.location as ItemLocation.Equipped).slot,
      )
    )
  }

  override fun validate(w: World, actor: Actor): String? {
    if (item.location != ItemLocation.Carried(actor.id)) return w.msg(KernelMsg.NOT_CARRYING)
    val plan = w.planSlot(actor, item)
    refusal(w, actor, plan)?.let {
      return it
    }
    validatedPlan = plan
    return null
  }

  override fun resolve(w: World, actor: Actor): Int {
    if (item.location != ItemLocation.Carried(actor.id)) {
      w.tell(actor, KernelMsg.NOT_CARRYING)
      return 0
    }
    val plan = w.planSlot(actor, item)
    // Hooks between validate and resolve can change what is worn; a different plan is checked in
    // full again, so it never skips a restriction.
    if (differsFromValidated(plan)) {
      refusal(w, actor, plan)?.let {
        w.send(actor, it)
        return 0
      }
    }
    if (plan != null) equip(w, actor, plan)
    return 0
  }

  private fun differsFromValidated(plan: SlotPlan?): Boolean {
    val validated = validatedPlan
    return plan == null ||
      validated == null ||
      plan.slot != validated.slot ||
      plan.displaced != validated.displaced
  }

  private fun equip(w: World, actor: Actor, plan: SlotPlan) {
    for (displaced in plan.displaced) {
      w.moveItem(displaced, ItemLocation.Carried(actor.id))
      w.tell(actor, KernelMsg.WEAR_STOP, "item" to displaced.name)
    }
    w.moveItem(item, ItemLocation.Equipped(actor.id, plan.slot))
    val wieldFamily = w.familyOfSlot("wield")
    val wielded =
      plan.slot == "wield" || (wieldFamily != null && w.familyOfSlot(plan.slot) == wieldFamily)
    w.tell(actor, if (wielded) KernelMsg.WIELD_SELF else KernelMsg.WEAR_SELF, "item" to item.name)
    w.tellSight(
      actor.room,
      if (wielded) "wield" else "wear",
      actor,
      w.msg(
        if (wielded) KernelMsg.WIELD_ROOM else KernelMsg.WEAR_ROOM,
        "actor" to actor.name.cap(),
        "item" to item.name,
      ),
      actor,
    )
  }
}
