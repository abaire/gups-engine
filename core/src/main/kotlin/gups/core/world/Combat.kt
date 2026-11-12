package gups.core.world

import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Item
import gups.core.model.ProcTrigger
import gups.core.model.TraitEvent
import gups.core.rules.RulesContext

// ---- Auto-attacks ----------------------------------------------------------------------------

/** One attack an actor makes in a round: with [weapon] (null for unarmed) from [slot]. */
private class Swing(val weapon: Item?, val slot: String)

internal fun World.autoAttack(a: Actor) {
  if (a.castBlocksAttacks() || a.isIncapacitated) return
  val opponents = opponentsOf(a)
  if (opponents.isEmpty()) return
  val target = opponents.firstOrNull { it.id == a.primaryTarget } ?: opponents.first()

  for (attack in swingsOf(a)) {
    if (!canKeepSwinging(a, target)) break
    swing(a, target, attack.weapon, attack.slot)
  }
}

private fun Actor.castBlocksAttacks(): Boolean = activity?.action?.attackWhileCasting == false

/** The swings [a] makes in a round: its actor slots in order, then any other equipped slots. */
private fun World.swingsOf(a: Actor): List<Swing> {
  val slots = actorSlots(a)
  val others = a.equipment.keys.filter { it !in slots }.sorted()
  return slots.mapNotNull { primarySwing(a, it) } + others.mapNotNull { armedSwing(a, it) }
}

/** A bare wield slot swings unarmed; any other slot swings only if it holds an active weapon. */
private fun primarySwing(a: Actor, slot: String): Swing? {
  val bare = slot == "wield" && a.equipped(slot) == null
  return if (bare) Swing(null, slot) else armedSwing(a, slot)
}

private fun armedSwing(a: Actor, slot: String): Swing? {
  val item = a.equipped(slot) ?: return null
  if (!a.isActiveSlot(slot)) return null
  if (item.proto.dice == null) return null
  return Swing(item, slot)
}

private fun World.canKeepSwinging(a: Actor, target: Actor): Boolean =
  isAbleToSwing(a) && isStillOpposing(a, target)

private fun World.isAbleToSwing(a: Actor): Boolean = a.inWorld && !a.isIncapacitated && canAct(a)

private fun World.isStillOpposing(a: Actor, target: Actor): Boolean =
  target in opponentsOf(a) && target.condition != Condition.DEAD

/** One auto-attack swing by [a] at [target] with [weapon] (null for unarmed) from [slot]. */
private fun World.swing(a: Actor, target: Actor, weapon: Item?, slot: String) {
  val reach = weapon?.range ?: Band.MELEE
  val band = Band.between(indexOf(a), indexOf(target))
  if (!reach.reaches(band)) {
    tell(a, CombatMsg.OUT_OF_REACH, "target" to target.name)
    return
  }
  dispatch(
      HookName.BEFORE_ATTACK,
      target,
      a.room,
      HookContext(actor = a, target = target, weapon = weapon),
    )
    ?.let {
      send(a, it)
      return
    }
  val type = weapon?.proto?.damageType ?: schema.unarmed
  val ctx = RulesContext(this, a, target, weapon, band, damageType = type, slot = slot)
  val chance = toHitChain(ctx)
  val roll = rng.int(1, 100)
  traceRules(a, "${chance.text} → roll $roll ${if (roll > chance.value) "MISS" else "HIT"}")
  if (roll > chance.value) {
    tell(a, CombatMsg.MISS_SELF, "target" to target.name)
    tell(target, CombatMsg.MISS_TARGET, "actor" to a.name.cap())
    tellPerceivers(
      a.room,
      "combat",
      a,
      mapOf(
        Channel.SIGHT to msg(CombatMsg.MISS_ROOM, "actor" to a.name.cap(), "target" to target.name),
        Channel.SOUND to msg(CombatMsg.COMBAT_HEARD),
      ),
      COMBAT_INTENSITY,
      exclude = arrayOf(a, target),
    )
    fireProcs(ProcTrigger.AVOID, target, a, weapon)
    return
  }
  val hit = resolveDamage(ctx, rules.damage(ctx), type)
  traceRules(a, hit.text)
  tell(a, CombatMsg.HIT_SELF, "target" to target.name)
  tell(target, CombatMsg.HIT_TARGET, "actor" to a.name.cap())
  tellPerceivers(
    a.room,
    "combat",
    a,
    mapOf(
      Channel.SIGHT to msg(CombatMsg.HIT_ROOM, "actor" to a.name.cap(), "target" to target.name),
      Channel.SOUND to msg(CombatMsg.COMBAT_HEARD),
    ),
    COMBAT_INTENSITY,
    exclude = arrayOf(a, target),
  )
  damage(target, hit.losses, type, a, hit.parts)
  fireProcs(ProcTrigger.HIT, a, target, weapon)
  fireProcs(ProcTrigger.STRUCK, target, a, weapon)
}

// ---- Damage and healing ----------------------------------------------------------------------

/**
 * The `damage` verb: lowers each vital in [losses] by its amount, with no floor; a negative entry
 * is a gain, raising that vital up to its maximum and never lowering it. When any loss is positive,
 * `on_damage` fires once with those losses and the interrupt check runs. A target whose condition
 * becomes dead is mortally wounded until death processing. [parts] are the typed parts the losses
 * came from, the hit's type first; the raw verb makes one part.
 */
fun World.damage(
  target: Actor,
  losses: Map<String, Int>,
  type: DamageType,
  source: Actor? = null,
  parts: List<DamagePart> = listOf(DamagePart(type, losses.filterValues { it > 0 })),
) {
  if (!target.inWorld) return
  val dealt = losses.filterValues { it > 0 }
  val total = dealt.values.sum()
  val gains =
    losses
      .filterValues { it < 0 }
      .mapValues { (name, loss) -> minOf(-loss, maxOf(0, target.max(name) - target.current(name))) }
      .filterValues { it > 0 }
  if (dealt.isEmpty() && gains.isEmpty()) return
  applyVitalDeltas(target, dealt.mapValues { -it.value } + gains, source)
  if (source != null && source !== target && total > 0) recordDamage(target, source, total)
  if (total > 0) {
    dispatch(
      HookName.ON_DAMAGE,
      target,
      target.room,
      HookContext(
        actor = source,
        target = target,
        amount = total,
        type = type,
        losses = dealt,
        parts = parts,
      ),
    )
    fireTraitEvent(
      target,
      TraitEvent.DAMAGED,
      mapOf(
        "source" to source,
        "amount" to total,
        "type" to type.name,
        "losses" to dealt,
        "parts" to parts.map { mapOf("type" to it.type.name, "losses" to it.losses) },
      ),
    )
    interruptOnDamage(target, source, total)
  }
  refreshCondition(target)
}

private fun World.interruptOnDamage(target: Actor, source: Actor?, total: Int) {
  val state = target.activity ?: return
  val skill = (state.action as? Castable)?.skill
  val c = RulesContext(this, target, source, skill = skill, task = taskInfoOf(state))
  if (rules.interruptCheck(c, total)) interruptActivity(target)
}

/** The `heal` verb: raises [vital] by [amount], up to its maximum. */
fun World.heal(target: Actor, vital: String, amount: Int, source: Actor? = null) {
  if (!target.inWorld || amount <= 0) return
  val effective = minOf(amount, maxOf(0, target.max(vital) - target.current(vital)))
  changeVital(target, vital, amount)
  if (source != null) creditHealing(target, source, effective)
  if (effective > 0) {
    fireTraitEvent(
      target,
      TraitEvent.HEALED,
      mapOf("source" to source, "vital" to vital, "amount" to effective, "requested" to amount),
    )
  }
}

private fun World.creditHealing(target: Actor, source: Actor, effective: Int) {
  recordHealing(target, source, effective)
  if (effective <= 0) return
  witness(source.room, source, "heal", amount = effective.toLong(), target = target)
  if (source === target) return
  val amount = effective.toLong()
  raiseStandingEvent(StandingEvent("heal", affected = target, actor = source, amount = amount))
  raiseAssists(target, source) { opponent ->
    StandingEvent("assist", affected = opponent, actor = source, amount = amount, helped = target)
  }
}
