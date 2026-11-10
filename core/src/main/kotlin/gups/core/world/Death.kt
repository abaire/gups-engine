package gups.core.world

import gups.core.CORPSE_DECAY_PULSES
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.TraitEvent
import gups.core.rules.Contributor
import gups.core.rules.RulesContext

/** True while [a]'s death is being run, before it is removed or respawned. */
fun World.isDying(a: Actor): Boolean = a.id in dying

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
  val killer = killerOf(victim)
  val snapshot = contributorsOf(victim, killer)
  wipeActivity(victim)
  // The opponents it frees hear of it once the victim is gone or respawned, so a hook can't
  // re-engage it here.
  val freed = unlinkAll(victim) - victim
  removedThisPhase += victim.id
  announceDeath(victim, room)
  fireDeathHooks(victim, killer, room)
  // A debug mob is worth nothing, and everything it leaves is debug too. A summon is
  // worth nothing either and drops no loot, so it can't be farmed.
  val summoned = victim is Mob && victim.summonedBy != null
  // Priced now, against the victim as it died, but awarded last: a level the award raises fires
  // on_level_change, which must not reach the victim or the belongings it is about to lose.
  val awards = xpAwards(victim, killer, snapshot, summoned)
  creditKill(victim, killer)

  val corpse = makeCorpse(victim, room)
  leaveBelongings(victim, ItemLocation.Contained(corpse.id), killer, summoned)
  fireDisengage(freed, "death")
  for ((a, n) in awards.entries.sortedBy { it.key.id }) if (a.inWorld && n > 0) awardXp(a, n)
}

private fun World.killerOf(victim: Actor): Actor? =
  victim.lastAttacker?.let(::actor)?.takeIf { it.inWorld && it !== victim }

/** Clears what a dying actor was in the middle of. */
private fun wipeActivity(victim: Actor) {
  victim.contributions.clear()
  victim.casting = null
  victim.actionQueue.clear()
  victim.waitState = 0
  victim.heldWaitState = 0
  victim.heldSince = 0
}

private fun World.announceDeath(victim: Actor, room: Room) {
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
}

private fun World.fireDeathHooks(victim: Actor, killer: Actor?, room: Room) {
  dispatch(HookName.ON_DEATH, victim, room, HookContext(actor = killer, target = victim))
  fireTraitEvent(victim, TraitEvent.DIED, mapOf("killer" to killer))
  if (killer != null) fireTraitEvent(killer, TraitEvent.KILLED, mapOf("victim" to victim))
  // The global hook sees the victim's belongings and level as at death, before the corpse is made
  // and XP is priced.
  dispatchGlobal(HookName.ON_DEATH, HookContext(actor = killer, target = victim), subject = victim)
}

/**
 * What each contributor to the kill earns, by who and how much; empty when the kill pays nothing.
 */
private fun World.xpAwards(
  victim: Actor,
  killer: Actor?,
  contributors: List<Contributor>,
  summoned: Boolean,
): Map<Actor, Long> {
  val pool = killPool(victim, killer, summoned)
  if (killer == null || pool <= 0) return emptyMap()
  return rules.xpShare(RulesContext(this, killer, victim, xp = pool, contributors = contributors))
}

private fun World.killPool(victim: Actor, killer: Actor?, summoned: Boolean): Long {
  if (Feature.LEVELING !in features || killer == null) return 0L
  if (victim.debug || summoned) return 0L
  return rules.xpForKill(RulesContext(this, killer, victim))
}

private fun World.creditKill(victim: Actor, killer: Actor?) {
  if (killer == null || killer === victim) return
  witness(killer.room, killer, "kill", target = victim)
  raiseStandingEvent(StandingEvent("kill", affected = victim, actor = killer))
}

private fun World.makeCorpse(victim: Actor, room: Room): Item {
  val corpse =
    spawnItem(
      gups.core.model.Prototypes.CORPSE.label,
      ItemLocation.InRoom(room.id, victim.trackIndex),
      debug = victim.debug,
    )
  corpse.nameOverride = "the corpse of ${victim.name}"
  corpse.decayAt = pulse + CORPSE_DECAY_PULSES
  return corpse
}

/** Moves what [victim] leaves into [into]: a mob is removed, a player respawns. */
private fun World.leaveBelongings(
  victim: Actor,
  into: ItemLocation.Contained,
  killer: Actor?,
  summoned: Boolean,
) {
  when (victim) {
    is Mob -> dropBelongings(victim, into, summoned)
    is Player -> respawnPlayer(victim, into, killer)
  }
}

/** Moves a dead mob's belongings and loot into [into] and removes the mob. */
private fun World.dropBelongings(victim: Mob, into: ItemLocation.Contained, summoned: Boolean) {
  for (item in victim.inventory + victim.equipment.values) {
    if (victim.debug) markDebug(item)
    moveItem(item, into)
  }
  if (!summoned) rollLoot(victim, into)
  removeActor(victim)
}

private fun World.rollLoot(victim: Mob, into: ItemLocation.Contained) {
  for (rule in victim.proto.loot) {
    if (!rng.passes(rule.chance)) continue
    spawnItem(rule.label, into, rule.count, debug = victim.debug)
  }
}

/** Moves a dead player's belongings into [into] and puts them back where the rules say. */
private fun World.respawnPlayer(victim: Player, into: ItemLocation.Contained, killer: Actor?) {
  for (item in victim.inventory) moveItem(item, into)
  val outcome = rules.onPlayerDeath(RulesContext(this, victim, killer))
  if (Feature.LEVELING in features) victim.xp = (victim.xp - outcome.xpLoss).coerceAtLeast(0)
  victim.affects.clear()
  victim.condition = Condition.OK
  respawnVitals(victim, outcome.vitals)
  placeActor(victim, room(outcome.respawn), 0)
  announceArrival(victim, victim.room)
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
