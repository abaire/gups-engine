package gups.core.world

import gups.core.EntityId
import gups.core.model.Actor
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.rules.RulesContext

internal fun ownerReason(ownerId: EntityId): String = "owner:${ownerId.v}"

private const val OWNER_PREFIX = "owner:"

/**
 * Makes [owner] the owner of [mob], replacing any previous one. Returns whether anything changed.
 */
fun World.setOwner(mob: Mob, owner: Player): Boolean {
  if (mob.owner == owner.id) return false
  val old = mob.owner
  assignOwner(mob, owner)
  if (old != null) (actor(old) as? Player)?.let { announceOwner(mob, it, "clear") }
  announceOwner(mob, owner, "set")
  return true
}

/** Removes [mob]'s owner. Returns whether it had one. */
fun World.clearOwner(mob: Mob): Boolean {
  val old = mob.owner ?: return false
  clearActive(mob, ownerReason(old))
  mob.owner = null
  mob.ownedBehaviors = null
  mob.dirty = true
  (actor(old) as? Player)?.let { announceOwner(mob, it, "clear") }
  return true
}

/**
 * Sets the field, the reason, the spawn release and ends fights with the owner's side; fires no
 * hook.
 */
internal fun World.assignOwner(mob: Mob, owner: Player) {
  require(owner.inWorld) { "owner must be in the world" }
  mob.owner?.takeIf { it != owner.id }?.let { clearActive(mob, ownerReason(it)) }
  mob.owner = owner.id
  setActive(mob, ownerReason(owner.id))
  releaseSpawn(mob)
  mob.dirty = true
  refreshOwnedBehaviors(mob)
  for (foe in mob.fighting.mapNotNull(::actor).sortedBy { it.id }) {
    if (isOwnerSide(mob, foe)) stopCombat(mob, foe)
  }
}

/** Fires the global `on_owner_change` for [mob] and [owner]. */
internal fun World.announceOwner(mob: Mob, owner: Player, event: String) {
  dispatchGlobal(HookName.ON_OWNER_CHANGE, HookContext(actor = mob, target = owner, event = event))
}

/** For restoring saved state: sets the field only, and drops any saved `owner:` reason. */
fun World.restoreOwner(mob: Mob, id: EntityId?) {
  mob.activeReasons.removeAll { it.startsWith(OWNER_PREFIX) }
  mob.owner = id
  refreshOwnedBehaviors(mob)
}

/** The in-world mobs whose owner is [p], ascending id, in any room. */
fun World.petsOf(p: Player): List<Mob> =
  entities.values
    .filterIsInstance<Mob>()
    .filter { it.owner == p.id && it.inWorld }
    .sortedBy { it.id }

/** True if [a] is [mob]'s owner, a member of the owner's group, or a pet of either. */
fun World.isOwnerSide(mob: Mob, a: Actor): Boolean {
  val owner = ownerOf(mob) ?: return false
  if (a === owner) return true
  val members = groupMembers(owner)
  if (a in members) return true
  val aOwner = ownerOf(a) ?: return false
  return aOwner === owner || aOwner in members
}

/** Takes [m] off its spawn rule so the zone respawns another; no-op when unbound. */
internal fun World.releaseSpawn(m: Mob) {
  val s = m.spawnedBy ?: return
  s.live.remove(m.id)
  if (s.respawnAt == null) s.respawnAt = pulse + s.rule.respawnPulses
  m.spawnedBy = null
}

/** The prototype's `flee` and `sentinel` behaviors, then `heel`, then `defend` with combat on. */
internal fun World.defaultOwnedBehaviors(mob: Mob): List<Behavior> =
  mob.proto.behaviors.filter { it is Flee || it == Sentinel } +
    Heel() +
    (if (Feature.COMBAT in features) listOf(Defend()) else emptyList())

/** Recomputes and caches [mob]'s owned list; no-op without an owner. */
internal fun World.refreshOwnedBehaviors(mob: Mob) {
  if (mob.owner == null) {
    mob.ownedBehaviors = null
    return
  }
  val owner = ownerOf(mob)
  val c = RulesContext(this, mob, owner, behaviors = mob.proto.behaviors)
  val base = rules.ownedBehaviors(c) ?: defaultOwnedBehaviors(mob)
  mob.ownedBehaviors =
    ownedBehaviorAdjusters.fold(base) { acc, adjuster -> adjuster.adjust(this, mob, owner, acc) }
}

/** Recomputes every in-world owned mob's list, after a reload changed the rules or prototypes. */
fun World.refreshAllOwnedBehaviors() {
  for (m in entities.values.filterIsInstance<Mob>().sortedBy { it.id }) {
    if (m.inWorld && m.owner != null) refreshOwnedBehaviors(m)
  }
}

/** [pets] of [p] leave the world with what they carry; their rows are kept for the next login. */
internal fun World.stowPets(p: Player, pets: List<Mob>) {
  for (pet in pets) {
    if (!pet.inWorld) continue
    tellSight(
      pet.room,
      "exit",
      pet,
      msg(KernelMsg.PET_LEAVES, "pet" to pet.name.cap(), "owner" to p.name),
    )
    removeActor(pet, stow = true)
  }
}

/** Places restored pet [m] beside [owner], resets its reason and owned list. */
fun World.restorePet(m: Mob, owner: Player) {
  check(m.owner == owner.id) { "mob ${m.id} owner ${m.owner} does not match ${owner.id}" }
  setActive(m, ownerReason(owner.id))
  refreshOwnedBehaviors(m)
  forgetActorSlots(m)
  clampVitals(m)
}
