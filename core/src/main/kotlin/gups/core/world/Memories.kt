package gups.core.world

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.MEMORY_MAX_ENTRIES
import gups.core.model.Memory
import gups.core.model.MemoryKey
import gups.core.model.Mob
import gups.core.model.Room

/** The mob's memory window in pulses; 0 without the combat feature. */
fun World.memoryPulsesOf(mob: Mob): Long =
  if (Feature.COMBAT !in features) 0 else mob.proto.memoryPulses ?: memoryPulses

private fun World.pruneMemories(mob: Mob, window: Long) {
  if (window <= 0) {
    mob.memories.clear()
    return
  }
  val cutoff = pulse - window
  mob.memories.entries.removeIf { it.value.pulse < cutoff }
}

/** Records or merges a memory; does nothing when the window is 0 or the mob is not in the world. */
fun World.remember(
  mob: Mob,
  subject: Actor,
  event: String,
  skill: Label? = null,
  amount: Long = 0,
  target: Actor? = null,
  channel: Channel? = null,
) {
  if (!mob.inWorld) return
  val window = memoryPulsesOf(mob)
  if (window <= 0) return
  pruneMemories(mob, window)
  val key = MemoryKey(subject.id, event, skill, target?.id)
  val existing = mob.memories[key]
  if (existing != null) {
    existing.pulse = pulse
    existing.count += 1
    existing.amount += amount
    existing.channel = channel ?: existing.channel
  } else {
    val m = Memory(key, pulse)
    m.amount = amount
    m.channel = channel
    mob.memories[key] = m
  }
  while (mob.memories.size > MEMORY_MAX_ENTRIES) {
    val oldest =
      mob.memories.entries.minWith(
        compareBy<Map.Entry<MemoryKey, Memory>> { it.value.pulse }.thenBy { it.value.firstPulse }
      )
    mob.memories.remove(oldest.key)
  }
}

/** The mob's live memories, newest first, filtered; prunes expired ones first. */
fun World.memoriesOf(
  mob: Mob,
  subject: Actor? = null,
  event: String? = null,
  since: Long = 0,
): List<Memory> {
  val window = memoryPulsesOf(mob)
  pruneMemories(mob, window)
  if (window <= 0) return emptyList()
  return mob.memories.values
    .filter {
      (subject == null || it.key.subject == subject.id) &&
        (event == null || it.key.event == event) &&
        it.pulse >= since
    }
    .sortedWith(
      compareByDescending<Memory> { it.pulse }
        .thenBy { it.key.subject }
        .thenBy { it.key.event }
        .thenBy { it.key.skill?.v ?: "" }
        .thenBy { it.key.target?.v ?: 0L }
    )
}

/**
 * Removes the mob's memories about [subject] and/or named [event]; all of them when both are null.
 */
fun World.forget(mob: Mob, subject: EntityId? = null, event: String? = null): Int {
  pruneMemories(mob, memoryPulsesOf(mob))
  var removed = 0
  val iter = mob.memories.entries.iterator()
  while (iter.hasNext()) {
    val entry = iter.next()
    if (
      (subject == null || entry.key.subject == subject) &&
        (event == null || entry.key.event == event)
    ) {
      iter.remove()
      removed++
    }
  }
  return removed
}

/** Has every simulated in-world mob with memory in [room], except [subject], witness [event]. */
internal fun World.witness(
  room: Room,
  subject: Actor,
  event: String,
  skill: Label? = null,
  amount: Long = 0,
  target: Actor? = null,
) {
  if (!subject.inWorld) return
  for (a in room.actorIndex.values) {
    if (a !is Mob || a === subject || !a.inWorld || !isSimulated(a)) continue
    if (memoryPulsesOf(a) <= 0) continue
    val ch = sensedChannel(a, subject, event) ?: continue
    remember(a, subject, event, skill, amount, target, ch)
  }
}
