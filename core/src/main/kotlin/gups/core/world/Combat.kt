package gups.core.world

import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Modifiers
import gups.core.model.Player
import gups.core.model.SkillProto
import gups.core.rules.RulesContext

const val CORPSE_DECAY_PULSES = 1200 // 5 min

// ---- Engagement (SPEC §5.4) ------------------------------------------------------------------

fun World.startCombat(a: Actor, b: Actor) {
    if (a === b || !a.inWorld || !b.inWorld || a.room !== b.room) return
    val fresh = b.id !in a.fighting
    a.fighting += b.id
    b.fighting += a.id
    if (a.primaryTarget == null) a.primaryTarget = b.id
    if (b.primaryTarget == null) b.primaryTarget = a.id
    a.dirty = true
    b.dirty = true
    if (fresh) {
        echo(a.room, "${a.name.cap()} and ${b.name} are fighting!", a, b)
        perceive("combat_start", Channel.NOISE, a.room, a)
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

/** True if an opponent shares [a]'s track index (SPEC §5.5). */
fun World.isPinned(a: Actor): Boolean = opponentsOf(a).any { indexOf(it) == indexOf(a) }

// ---- Auto-attacks ----------------------------------------------------------------------------

internal fun World.autoAttack(a: Actor) {
    val cast = a.casting
    if (cast != null && !cast.action.attackWhileCasting) return
    val opponents = opponentsOf(a)
    if (opponents.isEmpty()) return
    val target = opponents.firstOrNull { it.id == a.primaryTarget } ?: opponents.first()
    val weapon = a.equipped("wield")
    val reach = weapon?.range ?: Band.MELEE
    val band = Band.between(indexOf(a), indexOf(target))
    if (!reach.reaches(band)) {
        send(a, "${target.name.cap()} is out of reach.")
        return
    }
    dispatch(HookName.BEFORE_ATTACK, target, a.room, HookContext(actor = a, target = target, weapon = weapon))?.let {
        send(a, it)
        return
    }
    val ctx = RulesContext(this, a, target, weapon, band)
    val chance = toHitChain(ctx)
    val roll = rng.int(1, 100)
    traceRules(a, "${chance.text} → roll $roll ${if (roll > chance.value) "MISS" else "HIT"}")
    if (roll > chance.value) {
        send(a, "You miss ${target.name}.")
        send(target, "${a.name.cap()} misses you.")
        echo(a.room, "${a.name.cap()} misses ${target.name}.", a, target)
        return
    }
    val type = weapon?.proto?.damageType ?: DamageType.BLUDGEON
    val raw = damageChain(ctx)
    val dealt = rules.mitigate(ctx, raw.value, type)
    traceRules(a, "${raw.text} → mitigate $dealt $type")
    send(a, "You hit ${target.name}.")
    send(target, "${a.name.cap()} hits you.")
    echo(a.room, "${a.name.cap()} hits ${target.name}.", a, target)
    damage(target, dealt, type, a)
}

// ---- Damage, healing, death ------------------------------------------------------------------

/** The `damage` verb. A target at 0 hp or below is mortally wounded until death processing. */
fun World.damage(target: Actor, amount: Int, type: DamageType, source: Actor? = null) {
    if (!target.inWorld || amount <= 0) return
    target.hp -= amount
    target.dirty = true
    if (source != null && source !== target) target.lastAttacker = source.id
    dispatch(HookName.ON_DAMAGE, target, target.room, HookContext(actor = source, target = target, amount = amount, type = type))
    val cast = target.casting
    if (cast != null && rules.interruptCheck(RulesContext(this, target, source, skill = (cast.action as? Castable)?.skill), amount)) interruptCast(target)
    if (target.hp <= 0) deferRemoval(target, Deferred.Kind.DEATH) { processDeath(target) }
}

fun World.heal(target: Actor, amount: Int) {
    if (!target.inWorld || amount <= 0) return
    target.hp = (target.hp + amount).coerceAtMost(target.maxHp)
    target.dirty = true
}

/** Engine-owned death (SPEC §5.4). Runs at most once per life. */
internal fun World.processDeath(victim: Actor) {
    if (!victim.inWorld || victim.hp > 0) return
    val room = victim.room
    val killer = victim.lastAttacker?.let(::actor)?.takeIf { it.inWorld && it !== victim }
    victim.casting = null
    victim.actionQueue.clear()
    victim.waitState = 0
    disengageAll(victim)
    removedThisPhase += victim.id
    send(victim, "You are DEAD!")
    echo(room, "${victim.name.cap()} is DEAD!", victim)
    perceive("death", Channel.NOISE, room, victim)
    dispatch(HookName.ON_DEATH, victim, room, HookContext(actor = killer, target = victim))
    if (killer != null) awardXp(killer, rules.xpForKill(RulesContext(this, killer, victim)))

    val corpse = spawnItem(gups.core.model.Prototypes.CORPSE.label, ItemLocation.InRoom(room.id, victim.trackIndex))
    corpse.nameOverride = "the corpse of ${victim.name}"
    corpse.decayAt = pulse + CORPSE_DECAY_PULSES
    val into = ItemLocation.Contained(corpse.id)
    when (victim) {
        is Mob -> {
            for (item in victim.inventory + victim.equipment.values) moveItem(item, into)
            for (rule in victim.proto.loot) if (rng.fraction() < rule.chance) spawnItem(rule.label, into, rule.count)
            removeActor(victim)
        }
        is Player -> {
            for (item in victim.inventory) moveItem(item, into)
            val outcome = rules.onPlayerDeath(RulesContext(this, victim, killer))
            victim.xp = (victim.xp - outcome.xpLoss).coerceAtLeast(0)
            victim.hp = 1
            victim.affects.clear()
            placeActor(victim, room(outcome.respawn), 0)
            echo(victim.room, "${victim.name} appears, looking shaken.", victim)
            send(victim, describeRoom(victim))
        }
    }
}

fun World.awardXp(a: Actor, amount: Long) {
    if (amount <= 0) return
    a.xp += amount
    send(a, "You gain $amount experience.")
    while (a.xp >= rules.xpToLevel(a.level)) {
        a.level++
        send(a, "You have reached level ${a.level}!")
        if (a is Player) requestImmediate("level")
    }
    a.dirty = true
}

// ---- Affects ---------------------------------------------------------------------------------

/** The `apply_affect` verb. Re-applying a name replaces the old affect. */
fun World.applyAffect(
    target: Actor,
    name: String,
    duration: Int,
    statDeltas: Map<String, Int> = emptyMap(),
    flags: Set<String> = emptySet(),
    tickEvery: Int? = null,
    onTick: HookRef? = null,
    onExpire: HookRef? = null,
    expireMessage: String = "",
    source: Actor? = null,
    modifiers: Modifiers = emptyMap(),
): Affect? {
    if (!target.inWorld) return null
    require(duration >= 1) { "apply_affect: duration must be at least 1" }
    target.affects.removeIf { it.name == name }
    val affect = Affect(
        allocateHandle(), name, source?.id, pulse, pulse + duration,
        statDeltas, flags, tickEvery, onTick, onExpire, expireMessage, modifiers,
    )
    target.affects += affect
    target.dirty = true
    if ("stunned" in flags && target.casting?.action?.interrupt?.stun == true) interruptCast(target)
    return affect
}

fun World.removeAffect(target: Actor, name: String): Boolean = target.affects.removeIf { it.name == name }.also {
    if (it) target.dirty = true
}

internal fun World.expireAffect(a: Actor, affect: Affect) {
    if (!a.affects.remove(affect)) return
    a.dirty = true
    if (affect.expireMessage.isNotEmpty()) send(a, affect.expireMessage)
    affect.onExpire?.let { invokeRef(a, it, HookContext(affect = affect)) }
    if ("summoned" in affect.flags && a is Mob) {
        echo(a.room, "${a.name.cap()} fades away.", a)
        disengageAll(a)
        removeActor(a)
    }
}

// ---- Effect primitives (SPEC §4.3) -----------------------------------------------------------

/** Applies [effect] from [caster] to [target] (null for `target = "none"` skills). */
fun World.applyEffect(effect: Effect, caster: Actor, target: Actor?, skill: SkillProto? = null) {
    when (effect) {
        is Effect.Sequence -> effect.effects.forEach { applyEffect(it, caster, target, skill) }
        is Effect.Summon -> repeat(effect.count) {
            val m = spawnMob(effect.label, caster.room, caster.trackIndex)
            effect.duration?.let { applyAffect(m, "summoned", it, flags = setOf("summoned"), source = caster) }
        }
        else -> {
            val t = target ?: return
            if (!t.inWorld) return
            when (effect) {
                is Effect.Damage -> {
                    val amount = rules.mitigate(RulesContext(this, caster, t, skill = skill), effect.dice.roll(rng), effect.type)
                    if (t !== caster) startCombat(caster, t)
                    damage(t, amount, effect.type, caster)
                }
                is Effect.Heal -> heal(t, effect.dice.roll(rng))
                is Effect.ApplyAffect -> applyAffect(
                    t, effect.name, effect.duration, effect.statDeltas, effect.flags,
                    effect.tickEvery, effect.onTick, effect.onExpire, effect.expireMessage, caster, effect.modifiers,
                )
                is Effect.Dispel -> {
                    val f = effect.filter
                    val matches: (Affect) -> Boolean =
                        if (f.endsWith("*")) { a -> a.name.startsWith(f.dropLast(1)) } else { a -> a.name == f }
                    if (t.affects.removeIf(matches)) t.dirty = true
                }
                is Effect.Knockback -> shove(t, awayFrom = caster, steps = effect.steps)
                is Effect.Pull -> pull(t, toward = caster, steps = effect.steps)
                is Effect.Stun -> applyAffect(t, "stun", effect.pulses, flags = setOf("stunned"), source = caster)
                is Effect.Sequence, is Effect.Summon -> Unit
            }
        }
    }
}

/** Pushes [t] along the track away from [awayFrom]; track movement applies immediately. */
internal fun World.shove(t: Actor, awayFrom: Actor, steps: Int) {
    val dir = when {
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
    opponentsOf(a).minWithOrNull(compareBy<Actor>({ kotlin.math.abs(indexOf(it) - indexOf(a)) }, { it.id }))
