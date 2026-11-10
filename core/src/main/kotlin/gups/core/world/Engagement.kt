package gups.core.world

import gups.core.EntityId
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room

internal fun World.setPrimaryTarget(a: Actor, id: EntityId?) {
  if (a.primaryTarget != id) {
    a.primaryTarget = id
    a.primaryTargetSince = pulse
  }
}

fun World.startCombat(a: Actor, b: Actor) = startCombat(a, b, coupled = false)

internal fun World.startCombat(a: Actor, b: Actor, coupled: Boolean) {
  if (Feature.COMBAT !in features) return
  if (!canEngage(a, b)) return
  val fresh = b.id !in a.fighting
  engage(a, b)
  if (!fresh) return
  announceCombatStart(a, b)
  drawOwnerIn(pet = a, foe = b)
  drawOwnerIn(pet = b, foe = a)
  if (!coupled) provoke(a, b)
}

/**
 * Whether [a] and [b] can be set against each other. An actor whose death is being run is about to
 * be removed or respawned; engaging it now would leave its opponent fighting someone who is gone.
 */
private fun World.canEngage(a: Actor, b: Actor): Boolean {
  if (a === b) return false
  if (!a.inWorld || !b.inWorld) return false
  if (a.room !== b.room) return false
  return !isDying(a) && !isDying(b)
}

private fun World.engage(a: Actor, b: Actor) {
  a.fighting += b.id
  b.fighting += a.id
  if (a.primaryTarget == null) setPrimaryTarget(a, b.id)
  if (b.primaryTarget == null) setPrimaryTarget(b, a.id)
  a.dirty = true
  b.dirty = true
}

private fun World.announceCombatStart(a: Actor, b: Actor) {
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

/** A pet that is drawn into a fight brings its owner in against the same [foe]. */
private fun World.drawOwnerIn(pet: Actor, foe: Actor) {
  val owner = (pet as? Mob)?.let { ownerOf(it) } ?: return
  if (owner.room === pet.room && owner !== foe) startCombat(owner, foe, coupled = true)
}

/** What a fresh, uncoupled attack on [b] sets off beyond the fight itself. */
private fun World.provoke(a: Actor, b: Actor) {
  (b as? Mob)?.let { remember(it, a, "attack") }
  raiseStandingEvent(StandingEvent("attack", affected = b, actor = a))
}

/** Ends [a]'s engagement with [b], or with everyone if [b] is null. */
fun World.stopCombat(a: Actor, b: Actor? = null) {
  if (b == null) return disengageAll(a)
  fireDisengage(unlink(a, b).filter(::isSimulated), "stop")
}

/** The body of a stop: edits the engagement with [b], returning whoever this freed, in order. */
private fun World.unlink(a: Actor, b: Actor): List<Actor> {
  val aWas = a.isEngaged
  val bWas = b.isEngaged
  a.fighting -= b.id
  b.fighting -= a.id
  releaseTarget(a, b)
  releaseTarget(b, a)

  val original = listOfNotNull(a.takeIf { aWas && !a.isEngaged }, b.takeIf { bWas && !b.isEngaged })
  return (original + unlinkPets(a, b)).distinct()
}

/** Drops [a]'s aim at [from] and, once [a] is out of every fight, its initiative. */
private fun World.releaseTarget(a: Actor, from: Actor) {
  if (a.primaryTarget == from.id) setPrimaryTarget(a, null)
  if (!a.isEngaged) a.initiative = null
}

/** Unlinks the pets of whichever of [a] and [b] is a player from their opponent, in order. */
private fun World.unlinkPets(a: Actor, b: Actor): List<Actor> =
  listOf(a to b, b to a).flatMap { (owner, foe) -> unlinkPetsOf(owner, foe) }

private fun World.unlinkPetsOf(owner: Actor, foe: Actor): List<Actor> {
  if (owner !is Player) return emptyList()
  return petsEngagedWith(owner, owner.room, foe).flatMap { unlink(it, foe) }
}

/** [owner]'s pets in [room] engaged with [foe], ascending id. */
private fun World.petsEngagedWith(owner: Player, room: Room, foe: Actor): List<Mob> =
  room.actorIndex.values
    .filterIsInstance<Mob>()
    .filter { it.owner == owner.id && foe.id in it.fighting }
    .sortedBy { it.id }

internal fun World.disengageAll(a: Actor, reason: String? = "stop") {
  val freed = unlinkAll(a)
  if (reason != null) fireDisengage(freed, reason)
}

/**
 * Ends every engagement of [a] without firing anything, returning whoever this freed that is
 * simulated, in order. A caller that is about to move or remove [a] fires [fireDisengage] once that
 * is done, so a hook can't re-engage an actor that is half gone. Simulation is judged here, where
 * the fight was, so a mob that flees into a dormant room still hears of it.
 */
internal fun World.unlinkAll(a: Actor): List<Actor> {
  val freed = LinkedHashSet<Actor>()
  for (id in a.fighting.toList()) actor(id)?.let { freed += unlink(a, it) }
  a.fighting.clear()
  setPrimaryTarget(a, null)
  a.initiative = null
  return freed.filter(::isSimulated)
}

/** Fires `on_disengage` with [reason], on each of [freed] still in the world and globally. */
internal fun World.fireDisengage(freed: Collection<Actor>, reason: String) {
  if (Feature.COMBAT !in features) return
  for (x in freed) {
    if (!x.inWorld) continue
    val ctx = HookContext(actor = x, event = reason)
    dispatch(HookName.ON_DISENGAGE, x, x.room, ctx)
    dispatchGlobal(HookName.ON_DISENGAGE, ctx)
  }
}

/** Opponents of [a] who are, for validation, in the same room; ascending id. */
fun World.opponentsOf(a: Actor): List<Actor> {
  val room = roomOf(a)
  return a.fighting.mapNotNull { actor(it) }.filter { isPresent(it, room) && isSimulated(it) }
}

/**
 * Raises an `assist` standing event for each opponent of [helped] other than [source], in ascending
 * id order; [event] builds the event for one such opponent.
 */
internal fun World.raiseAssists(
  helped: Actor,
  source: Actor,
  event: (opponent: Actor) -> StandingEvent,
) {
  for (opponent in opponentsOf(helped).filter { it !== source }.sortedBy { it.id }) {
    raiseStandingEvent(event(opponent))
  }
}

/** True if an opponent shares [a]'s track index. */
fun World.isPinned(a: Actor): Boolean =
  Feature.TRACK in features && opponentsOf(a).any { indexOf(it) == indexOf(a) }

/** Nearest opponent by track distance, then lowest id. */
internal fun World.nearestOpponent(a: Actor): Actor? =
  opponentsOf(a)
    .minWithOrNull(compareBy<Actor>({ kotlin.math.abs(indexOf(it) - indexOf(a)) }, { it.id }))
