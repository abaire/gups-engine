package gups.core.world

import gups.core.CORPSE_DECAY_PULSES
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.Band
import gups.core.model.CastState
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Modifiers
import gups.core.model.Player
import gups.core.model.Proc
import gups.core.model.ProcTrigger
import gups.core.model.SkillProto
import gups.core.rules.RulesContext
import gups.core.saturatingAdd

// ---- Engagement ------------------------------------------------------------------------------

fun World.startCombat(a: Actor, b: Actor) {
  if (Feature.COMBAT !in features) return
  if (a === b || !a.inWorld || !b.inWorld || a.room !== b.room) return
  val fresh = b.id !in a.fighting
  a.fighting += b.id
  b.fighting += a.id
  if (a.primaryTarget == null) a.primaryTarget = b.id
  if (b.primaryTarget == null) b.primaryTarget = a.id
  a.dirty = true
  b.dirty = true
  if (fresh) {
    tellPerceivers(
      a.room,
      "combat_start",
      a,
      mapOf(
        Channel.SIGHT to msg(CombatMsg.FIGHTING, "actor" to a.name.cap(), "target" to b.name),
        Channel.SOUND to msg(CombatMsg.FIGHTING_HEARD),
      ),
      COMBAT_INTENSITY,
      exclude = arrayOf(a, b),
    )
    perceive("combat_start", Channel.SOUND, a.room, a, intensity = COMBAT_INTENSITY)
  }
}

/** Ends [a]'s engagement with [b], or with everyone if [b] is null. */
fun World.stopCombat(a: Actor, b: Actor? = null) {
  if (b == null) return disengageAll(a)
  a.fighting -= b.id
  b.fighting -= a.id
  if (a.primaryTarget == b.id) a.primaryTarget = null
  if (b.primaryTarget == a.id) b.primaryTarget = null
  if (!a.isEngaged) a.initiative = null
  if (!b.isEngaged) b.initiative = null
}

internal fun World.disengageAll(a: Actor) {
  for (id in a.fighting.toList()) actor(id)?.let { stopCombat(a, it) }
  a.fighting.clear()
  a.primaryTarget = null
  a.initiative = null
}

/** Opponents of [a] who are, for validation, in the same room; ascending id. */
fun World.opponentsOf(a: Actor): List<Actor> {
  val room = roomOf(a)
  return a.fighting.mapNotNull { actor(it) }.filter { isPresent(it, room) && isSimulated(it) }
}

/** True if an opponent shares [a]'s track index. */
fun World.isPinned(a: Actor): Boolean =
  Feature.TRACK in features && opponentsOf(a).any { indexOf(it) == indexOf(a) }

// ---- Auto-attacks ----------------------------------------------------------------------------

internal fun World.autoAttack(a: Actor) {
  val cast = a.casting
  if (cast != null && !cast.action.attackWhileCasting) return
  if (a.isIncapacitated) return
  val opponents = opponentsOf(a)
  if (opponents.isEmpty()) return
  val target = opponents.firstOrNull { it.id == a.primaryTarget } ?: opponents.first()

  val slots = actorSlots(a)
  val swings = mutableListOf<Pair<Item?, String>>()
  for (slot in slots) {
    val item = a.equipped(slot)
    if (slot == "wield" && item == null) {
      swings.add(null to "wield")
    } else if (item != null && a.isActiveSlot(slot) && item.proto.dice != null) {
      swings.add(item to slot)
    }
  }
  val otherSlots = a.equipment.keys.filter { it !in slots }.sorted()
  for (slot in otherSlots) {
    val item = a.equipped(slot) ?: continue
    if (a.isActiveSlot(slot) && item.proto.dice != null) {
      swings.add(item to slot)
    }
  }

  for ((weapon, slot) in swings) {
    if (
      !a.inWorld ||
        a.isIncapacitated ||
        !canAct(a) ||
        target !in opponentsOf(a) ||
        target.condition == Condition.DEAD
    ) {
      break
    }
    swing(a, target, weapon, slot)
  }
}

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

// ---- Damage, healing, death ------------------------------------------------------------------

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
    val cast = target.casting
    if (
      cast != null &&
        rules.interruptCheck(
          RulesContext(this, target, source, skill = (cast.action as? Castable)?.skill),
          total,
        )
    )
      interruptCast(target)
  }
  refreshCondition(target)
}

/** The `heal` verb: raises [vital] by [amount], up to its maximum. */
fun World.heal(target: Actor, vital: String, amount: Int) {
  if (!target.inWorld || amount <= 0) return
  changeVital(target, vital, amount)
}

/** Engine-owned death. Runs at most once per life. */
internal fun World.processDeath(victim: Actor) {
  // Damage dealt while the death runs, as by an on_death hook, must not start it again.
  if (!dying.add(victim.id)) return
  try {
    runDeath(victim)
  } finally {
    dying.remove(victim.id)
  }
}

private fun World.runDeath(victim: Actor) {
  if (!victim.inWorld || victim.condition != Condition.DEAD) return
  val standing = askCondition(victim)
  if (standing != Condition.DEAD) return setCondition(victim, standing)
  val room = victim.room
  val killer = victim.lastAttacker?.let(::actor)?.takeIf { it.inWorld && it !== victim }
  victim.casting = null
  victim.actionQueue.clear()
  victim.waitState = 0
  victim.heldWaitState = 0
  victim.heldSince = 0
  disengageAll(victim)
  removedThisPhase += victim.id
  tell(victim, KernelMsg.DEATH_SELF)
  tellPerceivers(
    room,
    "death",
    victim,
    mapOf(
      Channel.SIGHT to msg(KernelMsg.DEATH_ROOM, "actor" to victim.name.cap()),
      Channel.SOUND to msg(KernelMsg.DEATH_HEARD),
    ),
    COMBAT_INTENSITY,
    exclude = arrayOf(victim),
  )
  perceive("death", Channel.SOUND, room, victim, intensity = COMBAT_INTENSITY)
  dispatch(HookName.ON_DEATH, victim, room, HookContext(actor = killer, target = victim))
  // A debug mob is worth nothing, and everything it leaves is debug too. A summon is
  // worth nothing either and drops no loot, so it can't be farmed.
  val summoned = victim is Mob && victim.summonedBy != null
  // Priced now, against the victim as it died, but awarded last: a level the award raises fires
  // on_level_change, which must not reach the victim or the belongings it is about to lose.
  val killXp =
    if (Feature.LEVELING in features && killer != null && !victim.debug && !summoned)
      rules.xpForKill(RulesContext(this, killer, victim))
    else 0L

  val corpse =
    spawnItem(
      gups.core.model.Prototypes.CORPSE.label,
      ItemLocation.InRoom(room.id, victim.trackIndex),
      debug = victim.debug,
    )
  corpse.nameOverride = "the corpse of ${victim.name}"
  corpse.decayAt = pulse + CORPSE_DECAY_PULSES
  val into = ItemLocation.Contained(corpse.id)
  when (victim) {
    is Mob -> {
      for (item in victim.inventory + victim.equipment.values) {
        if (victim.debug) markDebug(item)
        moveItem(item, into)
      }
      if (!summoned)
        for (rule in victim.proto.loot) if (rng.passes(rule.chance))
          spawnItem(rule.label, into, rule.count, debug = victim.debug)
      removeActor(victim)
    }
    is Player -> {
      for (item in victim.inventory) moveItem(item, into)
      val outcome = rules.onPlayerDeath(RulesContext(this, victim, killer))
      if (Feature.LEVELING in features) victim.xp = (victim.xp - outcome.xpLoss).coerceAtLeast(0)
      victim.affects.clear()
      victim.condition = Condition.OK
      respawnVitals(victim, outcome.vitals)
      placeActor(victim, room(outcome.respawn), 0)
      tellSight(
        victim.room,
        "enter",
        victim,
        msg(KernelMsg.DEATH_RESPAWN, "name" to victim.name),
        victim,
      )
      send(victim, describeRoom(victim))
      if (outcome.levelLoss > 0) levelDown(victim, outcome.levelLoss)
    }
  }
  if (killer != null && killer.inWorld) awardXp(killer, killXp)
}

/**
 * Sets the vitals of a respawned player: [given] where the rules name one (capped at its maximum),
 * else the maximum. A rules result that still calls the player dead is an error, and fills them.
 */
private fun World.respawnVitals(victim: Actor, given: Map<String, Int>?) {
  for ((name, v) in victim.vitalMap) {
    val effMax = victim.max(name)
    val next = given?.get(name)?.coerceAtMost(effMax) ?: effMax
    v.belowZeroSince = if (next < 0) pulse else null
    v.current = next
  }
  victim.dirty = true
  settleNewCondition(victim, "on_player_death")
}

// ---- Affects ---------------------------------------------------------------------------------

/**
 * The `apply_affect` verb. Re-applying a name replaces the old affect only if the new one would run
 * out later; otherwise the old one stays and is returned, so a short stun or debuff can't cut a
 * longer one short.
 */
fun World.applyAffect(
  target: Actor,
  name: String,
  duration: Long,
  statDeltas: Map<String, Int> = emptyMap(),
  flags: Set<String> = emptySet(),
  tickEvery: Int? = null,
  onTick: HookRef? = null,
  onExpire: HookRef? = null,
  expireMessage: String = "",
  source: Actor? = null,
  modifiers: Modifiers = emptyMap(),
  wornFrom: EntityId? = null,
  senseDeltas: Map<Channel, Int> = emptyMap(),
  maxVitalDeltas: Map<String, Int> = emptyMap(),
  resistDeltas: Map<DamageType, Int> = emptyMap(),
  procs: List<Proc> = emptyList(),
  power: Int = 100,
): Affect? {
  if (!target.inWorld) return null
  require(duration >= 1) { "apply_affect: duration must be at least 1" }
  // A worn item's affect lasts until the item comes off.
  val expiresAt = if (wornFrom != null) Long.MAX_VALUE else saturatingAdd(pulse, duration)
  target.affects.firstOrNull { it.name == name }?.let { if (it.expiresAt >= expiresAt) return it }
  val wasDisabled = target.isDisabled
  target.affects.removeIf { it.name == name }
  val affect =
    Affect(
      allocateHandle(),
      name,
      source?.id,
      pulse,
      expiresAt,
      statDeltas,
      flags,
      tickEvery,
      onTick,
      onExpire,
      expireMessage,
      modifiers,
      wornFrom,
      senseDeltas,
      maxVitalDeltas = maxVitalDeltas,
      resistDeltas = resistDeltas,
      procs = procs,
      power = power,
    )
  target.affects += affect
  target.dirty = true
  if ("stunned" in flags && target.casting?.action?.interrupt?.stun == true) interruptCast(target)
  disabledChanged(target, wasDisabled)
  forgetActorSlots(target)
  refreshFit(target)
  clampVitals(target)
  return affect
}

fun World.removeAffect(target: Actor, name: String): Boolean =
  removeAffects(target) { it.name == name }

/** Ends every affect on [target] that [matches], as removed early; true if any matched. */
private fun World.removeAffects(target: Actor, matches: (Affect) -> Boolean): Boolean {
  val gone = target.affects.filter(matches)
  for (affect in gone) endAffect(target, affect, AffectEndReason.REMOVED)
  return gone.isNotEmpty()
}

internal fun World.expireAffect(a: Actor, affect: Affect) =
  endAffect(a, affect, AffectEndReason.EXPIRED)

/**
 * Takes [affect] off [a] and runs its end: the expire message (on expiry, or always when
 * [announce]), `on_expire` and, for a summon, the banishing. An affect that has reached its expiry
 * pulse counts as expired however it was taken off.
 */
private fun World.endAffect(
  a: Actor,
  affect: Affect,
  reason: AffectEndReason,
  announce: Boolean = false,
) {
  val wasDisabled = a.isDisabled
  if (!a.affects.remove(affect)) return
  a.dirty = true
  disabledChanged(a, wasDisabled)
  forgetActorSlots(a)
  refreshFit(a)
  clampVitals(a)
  val why = if (pulse >= affect.expiresAt) AffectEndReason.EXPIRED else reason
  if ((announce || why == AffectEndReason.EXPIRED) && affect.expireMessage.isNotEmpty())
    send(a, affect.expireMessage)
  affect.onExpire?.let { invokeRef(a, it, HookContext(affect = affect, affectEndReason = why)) }
  if ("summoned" in affect.flags && a is Mob && a.inWorld) {
    tellSight(a.room, "exit", a, msg(KernelMsg.AFFECT_FADES, "actor" to a.name.cap()), a)
    disengageAll(a)
    removeActor(a)
  }
}

/**
 * Puts instance affect [name] on [item]; [duration] null makes it permanent. A same-name affect is
 * replaced only by one that expires later.
 */
fun World.applyItemAffect(
  item: Item,
  name: String,
  duration: Long?,
  applies: AffectScope = AffectScope.WORN,
  statDeltas: Map<String, Int> = emptyMap(),
  flags: Set<String> = emptySet(),
  tickEvery: Int? = null,
  onTick: HookRef? = null,
  onExpire: HookRef? = null,
  expireMessage: String = "",
  source: Actor? = null,
  modifiers: Modifiers = emptyMap(),
  senseDeltas: Map<Channel, Int> = emptyMap(),
  maxVitalDeltas: Map<String, Int> = emptyMap(),
  resistDeltas: Map<DamageType, Int> = emptyMap(),
  procs: List<Proc> = emptyList(),
  power: Int = 100,
): Affect? {
  if (item.id !in entities) return null
  require(duration == null || duration >= 1) { "apply_affect: duration must be at least 1" }
  require("stunned" !in flags) { "apply_affect: item affects can't stun" }
  val expiresAt = duration?.let { saturatingAdd(pulse, it) } ?: Long.MAX_VALUE
  item.affects.firstOrNull { it.name == name }?.let { if (it.expiresAt >= expiresAt) return it }
  item.affects.removeIf { it.name == name }
  val affect =
    Affect(
      allocateHandle(),
      name,
      source?.id,
      pulse,
      expiresAt,
      statDeltas,
      flags,
      tickEvery,
      onTick,
      onExpire,
      expireMessage,
      modifiers,
      null,
      senseDeltas,
      applies,
      maxVitalDeltas,
      resistDeltas,
      procs,
      power,
    )
  item.affects += affect
  item.dirty = true
  custodian(item)?.let { refreshHoldings(it) }
  return affect
}

/** Removes instance affect [name] from [item]; true if there was one. */
fun World.removeItemAffect(item: Item, name: String): Boolean {
  val gone = item.affects.filter { it.name == name }
  for (affect in gone) endItemAffect(item, affect, AffectEndReason.REMOVED)
  return gone.isNotEmpty()
}

internal fun World.expireItemAffect(item: Item, affect: Affect) =
  endItemAffect(item, affect, AffectEndReason.EXPIRED)

/**
 * Takes [affect] off [item] and runs its end: the expire message to the custodian on expiry, and
 * `on_expire`, whose `ctx.self` is the item. An affect that has reached its expiry pulse counts as
 * expired however it was taken off. With no custodian the message is dropped. An item that has left
 * the world took its affects with it, so nothing runs for them.
 */
private fun World.endItemAffect(item: Item, affect: Affect, reason: AffectEndReason) {
  if (item.id !in entities || !item.affects.remove(affect)) return
  item.dirty = true
  custodian(item)?.let { refreshHoldings(it) }
  val why = if (pulse >= affect.expiresAt) AffectEndReason.EXPIRED else reason
  if (why == AffectEndReason.EXPIRED && affect.expireMessage.isNotEmpty())
    custodian(item)?.let { send(it, affect.expireMessage) }
  affect.onExpire?.let { invokeRef(item, it, HookContext(affect = affect, affectEndReason = why)) }
}

/**
 * Clears [a]'s recovery when a stun or incapacitation starts and puts it back when the last one
 * ends, so recovery neither counts down nor runs out meanwhile. Any lag from a cast that broke is
 * part of what is put back. A cast that didn't break has its warmup pushed back by the length of
 * the interruption, so it stays paused.
 */
internal fun World.disabledChanged(a: Actor, wasDisabled: Boolean) {
  val disabled = a.isDisabled
  if (disabled && !wasDisabled) {
    a.heldWaitState = a.waitState
    a.heldSince = pulse
    a.waitState = 0
  } else if (wasDisabled && !disabled) {
    a.waitState = maxOf(a.waitState, a.heldWaitState)
    a.heldWaitState = 0
    a.casting?.let { c ->
      a.casting = CastState(c.action, c.startedAt, c.endsAt + (pulse - a.heldSince))
    }
  }
}

/**
 * Applies [item]'s `equip_affects` to [wearer] as it is equipped, through [applyAffect] like any
 * other affect. Untimed entries last until [removeEquipAffects]; timed ones count down. If
 * [untimedOnly] is true, timed entries are skipped (used when an inactive item becomes active
 * again).
 */
internal fun World.applyEquipAffects(wearer: Actor, item: Item, untimedOnly: Boolean = false) {
  for (e in item.proto.equipAffects) {
    if (untimedOnly && e.pulses != null) continue
    val name = equipAffectName(item, e.flag)
    if (e.pulses != null) applyAffect(wearer, name, e.pulses.toLong(), flags = setOf(e.flag))
    else applyAffect(wearer, name, 1, flags = setOf(e.flag), wornFrom = item.id)
  }
}

/** Removes the untimed `equip_affects` of [item] from [wearer] as it comes off. */
internal fun World.removeEquipAffects(wearer: Actor, item: Item) {
  for (e in item.proto.equipAffects) {
    if (e.pulses == null) removeAffect(wearer, equipAffectName(item, e.flag))
  }
}

private fun equipAffectName(item: Item, flag: String) = "worn:${item.id.v}:$flag"

// ---- Effect primitives -----------------------------------------------------------------------

/**
 * Scales a declared amount by [power] percent: rounded down, never below 1 when it was positive.
 */
internal fun scaled(amount: Int, power: Int): Int =
  if (amount <= 0) amount else maxOf(1, (amount.toLong() * power / 100).toInt())

/**
 * Like [scaled] for a Long amount: rounded down, never below 1 when positive, and stopping at
 * Long.MAX_VALUE. A duration of Long.MAX_VALUE means "never ends" and is never scaled.
 */
internal fun scaledDuration(amount: Long, power: Int): Long {
  if (amount <= 0 || amount == Long.MAX_VALUE) return amount
  if (power <= 0) return 1L
  // amount * power / 100, split so the intermediate product can't overflow.
  val q = amount / 100
  val r = amount % 100
  if (q > (Long.MAX_VALUE - r * power / 100) / power) return Long.MAX_VALUE
  return maxOf(1L, q * power + r * power / 100)
}

/**
 * Spawns [count] mobs of [label] beside [caster] as its summons; [duration] null means they stay.
 */
fun World.summon(
  caster: Actor,
  label: Label,
  count: Int = 1,
  duration: Int? = null,
  power: Int = 100,
): List<Mob> {
  require(count >= 1) { "summon: count must be at least 1" }
  require(duration == null || duration >= 1) { "summon: duration must be at least 1" }
  val mobs = mutableListOf<Mob>()
  repeat(count) {
    val m = spawnMob(label, caster.room, caster.trackIndex)
    m.summonedBy = caster.id
    duration?.let {
      applyAffect(
        m,
        "summoned",
        scaled(it, power).toLong(),
        flags = setOf("summoned"),
        source = caster,
        power = power,
      )
    }
    mobs += m
  }
  return mobs
}

/** Applies [effect] from [caster] to [target] (null for `target = "none"` skills). */
fun World.applyEffect(
  effect: Effect,
  caster: Actor,
  target: Actor?,
  skill: SkillProto? = null,
  power: Int = 100,
) {
  when (effect) {
    is Effect.Sequence -> effect.effects.forEach { applyEffect(it, caster, target, skill, power) }
    is Effect.Plugin -> pluginEffects.apply(this, effect, caster, target, null, null, power)
    is Effect.Summon -> summon(caster, effect.label, effect.count, effect.duration, power)
    else -> {
      val t = target ?: return
      if (!t.inWorld) return
      // Harmful primitives engage, so a stun or shove can't be used on a mob without aggro.
      val hostile =
        effect is Effect.Damage ||
          effect is Effect.Stun ||
          effect is Effect.Knockback ||
          effect is Effect.Pull
      if (hostile && t !== caster) startCombat(caster, t)
      when (effect) {
        is Effect.Damage -> {
          val c = RulesContext(this, caster, t, skill = skill, damageType = effect.type)
          val hit = resolveDamage(c, scaled(effect.dice.roll(rng), power), effect.type)
          traceRules(caster, hit.text)
          damage(t, hit.losses, effect.type, caster, hit.parts)
        }
        is Effect.Heal -> heal(t, effect.vital, scaled(effect.dice.roll(rng), power))
        is Effect.ApplyAffect ->
          applyAffect(
            t,
            effect.name,
            scaledDuration(
              requireNotNull(effect.duration) { "apply_affect: duration is required for an actor" },
              power,
            ),
            effect.statDeltas,
            effect.flags,
            effect.tickEvery,
            effect.onTick,
            effect.onExpire,
            effect.expireMessage,
            caster,
            effect.modifiers,
            senseDeltas = effect.senseDeltas,
            maxVitalDeltas = effect.maxVitalDeltas,
            resistDeltas = effect.resistDeltas,
            procs = effect.procs,
            power = power,
          )
        is Effect.Dispel -> {
          val gone = t.affects.filter(effect::matches)
          // Unlike remove_affect, a dispel tells the target its affect ended.
          for (affect in gone.take(effect.count ?: gone.size)) endAffect(
            t,
            affect,
            AffectEndReason.REMOVED,
            announce = true,
          )
        }
        is Effect.Knockback ->
          if (Feature.TRACK in features) shove(t, awayFrom = caster, steps = effect.steps)
        is Effect.Pull ->
          if (Feature.TRACK in features) pull(t, toward = caster, steps = effect.steps)
        is Effect.Stun ->
          applyAffect(
            t,
            "stun",
            scaled(effect.pulses, power).toLong(),
            flags = setOf("stunned"),
            source = caster,
            power = power,
          )
        is Effect.Sequence,
        is Effect.Summon,
        is Effect.Plugin -> Unit
      }
    }
  }
}

/** Applies an item skill's [effect] from [caster] to [item]. */
fun World.applyItemEffect(effect: Effect, caster: Actor, item: Item, power: Int = 100) {
  when (effect) {
    is Effect.Sequence -> effect.effects.forEach { applyItemEffect(it, caster, item, power) }
    is Effect.ApplyAffect ->
      applyItemAffect(
        item,
        effect.name,
        effect.duration?.let { scaledDuration(it, power) },
        effect.applies ?: AffectScope.WORN,
        effect.statDeltas,
        effect.flags,
        effect.tickEvery,
        effect.onTick,
        effect.onExpire,
        effect.expireMessage,
        caster,
        effect.modifiers,
        effect.senseDeltas,
        effect.maxVitalDeltas,
        effect.resistDeltas,
        effect.procs,
        power = power,
      )
    is Effect.Plugin -> pluginEffects.apply(this, effect, caster, null, item, null, power)
    else -> error("${effect::class.simpleName} can't act on an item")
  }
}

/** Pushes [t] along the track away from [awayFrom]; track movement applies immediately. */
internal fun World.shove(t: Actor, awayFrom: Actor, steps: Int) {
  val dir =
    when {
      t.trackIndex > awayFrom.trackIndex -> 1
      t.trackIndex < awayFrom.trackIndex -> -1
      t.room.trackSize - 1 - t.trackIndex >= t.trackIndex -> 1
      else -> -1
    }
  placeActor(t, t.room, t.trackIndex + dir * steps)
}

internal fun World.pull(t: Actor, toward: Actor, steps: Int) {
  val gap = toward.trackIndex - t.trackIndex
  val moved = gap.coerceIn(-steps, steps)
  placeActor(t, t.room, t.trackIndex + moved)
}

/** Nearest opponent by track distance, then lowest id. */
internal fun World.nearestOpponent(a: Actor): Actor? =
  opponentsOf(a)
    .minWithOrNull(compareBy<Actor>({ kotlin.math.abs(indexOf(it) - indexOf(a)) }, { it.id }))
