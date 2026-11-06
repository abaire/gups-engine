package gups.core.world

import gups.core.model.Actor
import gups.core.model.Contribution
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.rules.Contributor

// The contribution ledger: who contributed to a fight against a victim. Held on the victim,
// transient, and never saved. The engine records damage, healing and support; the world decides
// when to clear.

/**
 * Credits [source] with [amount] vital loss against [victim]. Ignores a null or self source, a
 * source or victim outside the world, and amounts of 0 or less.
 */
internal fun World.recordDamage(victim: Actor, source: Actor, amount: Int) {
  if (source === victim || !source.inWorld || !victim.inWorld || amount <= 0) return
  val entry = victim.contributions.computeIfAbsent(source.id) { Contribution() }
  entry.damage += amount
  entry.lastPulse = pulse
}

/**
 * Credits [source] with [effective] healing on the ledger of each of [healed]'s opponents, never on
 * [healed] itself. A heal outside combat earns nothing.
 */
internal fun World.recordHealing(healed: Actor, source: Actor, effective: Int) {
  if (!source.inWorld || !healed.inWorld || effective <= 0) return
  for (opponent in opponentsOf(healed)) {
    if (opponent === source) continue
    val entry = opponent.contributions.computeIfAbsent(source.id) { Contribution() }
    entry.healing += effective
    entry.lastPulse = pulse
  }
}

/**
 * Credits [source] with one support affect on [target]. [hostile] is true for a bane (the victim's
 * own ledger), false for help (each of the victim's opponents' ledgers), and null when the caller
 * can't say, in which case a bane is inferred from whether [source] is already engaged with
 * [target].
 */
internal fun World.recordSupport(target: Actor, source: Actor, hostile: Boolean?) {
  if (source === target || !source.inWorld || !target.inWorld) return
  val bane = hostile ?: (source.id in target.fighting)
  val ledgers = if (bane) listOf(target) else opponentsOf(target).filter { it !== source }
  for (victim in ledgers) {
    val entry = victim.contributions.computeIfAbsent(source.id) { Contribution() }
    entry.support += 1
    entry.lastPulse = pulse
  }
}

/** Empties [victim]'s contribution record, or removes one contributor's entry when given. */
fun World.clearContributions(victim: Actor, contributor: Actor? = null) {
  if (contributor == null) victim.contributions.clear()
  else victim.contributions.remove(contributor.id)
}

/**
 * The in-world player a pet belongs to: [Mob]`summonedBy` else [Mob]`leader`; players return null.
 */
fun World.ownerOf(a: Actor): Player? {
  if (a !is Mob) return null
  val id = a.summonedBy ?: a.leader ?: return null
  return actor(id)?.takeIf { it is Player && it.inWorld } as? Player
}

/**
 * The in-world contributors of a kill, ordered by actor id. The victim is excluded, and [killer] is
 * added with a zero entry when it has none; a null [killer] (an environmental death) adds nothing.
 * Every in-world member of a contributor's group is also listed, even with no contribution and
 * wherever it is; `inRoom` tells the formula where it is.
 */
internal fun World.contributorsOf(victim: Actor, killer: Actor?): List<Contributor> {
  val ids = victim.contributions.keys.mapTo(HashSet()) { it }
  if (killer != null && killer !== victim) ids += killer.id
  val actors = ids.mapNotNull(::actor).filter { it.inWorld && it !== victim }
  val groups = actors.mapNotNull(::groupOf).distinct()
  for (group in groups) for (m in group.members) if (m.inWorld && m !== victim) ids += m.id
  return ids
    .mapNotNull(::actor)
    .filter { it.inWorld && it !== victim }
    .distinctBy { it.id }
    .sortedBy { it.id }
    .map { a ->
      val e = victim.contributions[a.id]
      Contributor(
        actor = a,
        damage = e?.damage ?: 0,
        healing = e?.healing ?: 0,
        support = e?.support ?: 0,
        lastPulse = e?.lastPulse ?: 0,
        owner = ownerOf(a),
        groupId = groupOf(a)?.id,
        inRoom = a.room === victim.room,
      )
    }
}
