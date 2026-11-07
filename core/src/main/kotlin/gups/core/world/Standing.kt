package gups.core.world

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.DispositionParts
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.STANDING_HISTORY_MAX
import gups.core.model.STANDING_MAX
import gups.core.model.STANDING_MIN
import gups.core.model.StandingChange
import gups.core.model.StandingDelta
import gups.core.model.StandingHolder
import gups.core.model.StandingRecord
import gups.core.model.StandingSubject
import gups.core.model.StandingTotals
import gups.core.rules.RulesContext
import kotlin.math.abs

data class StandingEvent(
  val event: String,
  val affected: Actor,
  val actor: Actor,
  val amount: Long? = null,
  val item: Item? = null,
  val affect: Affect? = null,
  val helped: Actor? = null,
)

val BUILTIN_STANDING_EVENTS = setOf("attack", "kill", "heal", "help", "assist", "give")

/** What a world-raised standing event's name must look like. */
val STANDING_EVENT_NAME = Regex("[a-z][a-z0-9_]*")

fun World.factionsOf(a: Actor): List<Label> {
  if (Feature.STANDING !in features) return emptyList()
  factionCache[a.id]?.let {
    return it
  }
  // The export and plugins may ask about other actors, but asking about this one again would
  // never finish.
  check(factionsInProgress.add(a.id)) { "the factions of ${a.name} are still being worked out" }
  try {
    val base =
      rules.factions(RulesContext(world = this, actor = a))
        ?: when (a) {
          is Player -> emptyList()
          is Mob -> a.proto.factions ?: listOf(a.proto.label)
        }
    val folded = factionAdjusters.fold(base) { acc, adjuster -> adjuster.adjust(this, a, acc) }
    val result = folded.filter { protos.isFaction(it) }
    factionCache[a.id] = result
    return result
  } finally {
    factionsInProgress.remove(a.id)
  }
}

fun World.forgetFactions(a: Actor) {
  factionCache.remove(a.id)
  dispositionMemo.clear()
}

fun World.dispositionParts(holder: Actor, subject: Actor): DispositionParts {
  val hFactions = factionsOf(holder)
  val sFactions = factionsOf(subject)

  var declared = 0
  for (h in hFactions) {
    val hProto = protos.factions[h] ?: continue
    for (s in sFactions) {
      declared += hProto.standing[s] ?: 0
    }
  }

  var factionToActor = 0
  for (h in hFactions) {
    factionToActor += factionStanding[h to StandingSubject.Of(subject.id)]?.amount ?: 0
  }

  var factionToFaction = 0
  for (h in hFactions) {
    for (s in sFactions) {
      factionToFaction += factionStanding[h to StandingSubject.Faction(s)]?.amount ?: 0
    }
  }

  val personal =
    if (holder is Mob) {
      holder.personalStanding[subject.id]?.amount ?: 0
    } else {
      0
    }

  return DispositionParts(
    declared = declared,
    factionToActor = factionToActor,
    factionToFaction = factionToFaction,
    personal = personal,
  )
}

fun World.disposition(holder: Actor, subject: Actor): Int {
  if (Feature.STANDING !in features) return 0
  if (holder === subject || holder.id == subject.id) return STANDING_MAX
  val key = holder.id to subject.id
  dispositionMemo[key]?.let {
    return it
  }
  check(dispositionInProgress.add(key)) {
    "the disposition of ${holder.name} toward ${subject.name} is still being worked out"
  }
  try {
    val parts = dispositionParts(holder, subject)
    val exportVal =
      rules.disposition(
        RulesContext(world = this, actor = holder, target = subject, dispositionParts = parts)
      )
    val base = (exportVal ?: parts.total).coerceIn(STANDING_MIN, STANDING_MAX)
    val value =
      dispositionModifiers.fold(base) { acc, modifier ->
        modifier.adjust(this, holder, subject, acc).coerceIn(STANDING_MIN, STANDING_MAX)
      }
    dispositionMemo[key] = value
    return value
  } finally {
    dispositionInProgress.remove(key)
  }
}

/**
 * The amount of [holder]'s record about [subject], or 0 when there is none. Declared faction
 * opinions are not records, so they are not included.
 */
fun World.standing(holder: StandingHolder, subject: StandingSubject): Int =
  when (holder) {
    is StandingHolder.Personal -> {
      require(subject is StandingSubject.Of) {
        "a personal standing record's subject must be an actor"
      }
      holder.mob.personalStanding[subject.actor]?.amount ?: 0
    }
    is StandingHolder.Faction -> factionStanding[holder.label to subject]?.amount ?: 0
  }

fun World.applyStanding(
  holder: StandingHolder,
  subject: StandingSubject,
  amount: Int,
  event: String,
  via: Mob? = null,
) {
  if (Feature.STANDING !in features || amount == 0) return
  val record =
    when (holder) {
      is StandingHolder.Personal -> {
        if (subject !is StandingSubject.Of) {
          throw IllegalArgumentException("personal standing subject must be an actor")
        }
        holder.mob.dirty = true
        holder.mob.personalStanding.getOrPut(subject.actor) { StandingRecord() }
      }
      is StandingHolder.Faction -> {
        factionStanding.getOrPut(holder.label to subject) { StandingRecord() }
      }
    }
  record.amount = (record.amount + amount).coerceIn(STANDING_MIN, STANDING_MAX)
  record.updatedPulse = pulse
  record.history.addLast(StandingChange(pulse, amount, event, via?.id))
  val cutoff = pulse - standingHistoryPulses
  while (record.history.isNotEmpty() && record.history.first().pulse < cutoff) {
    record.history.removeFirst()
  }
  while (record.history.size > STANDING_HISTORY_MAX) {
    record.history.removeFirst()
  }
  dispositionMemo.clear()
}

fun World.clearStanding(holder: StandingHolder, subject: StandingSubject?) {
  when (holder) {
    is StandingHolder.Personal -> {
      holder.mob.dirty = true
      if (subject != null) {
        if (subject !is StandingSubject.Of) {
          throw IllegalArgumentException("personal standing subject must be an actor")
        }
        holder.mob.personalStanding.remove(subject.actor)
      } else {
        holder.mob.personalStanding.clear()
      }
    }
    is StandingHolder.Faction -> {
      if (subject != null) {
        factionStanding.remove(holder.label to subject)
      } else {
        factionStanding.keys.removeIf { it.first == holder.label }
      }
    }
  }
  dispositionMemo.clear()
}

fun World.standingHistory(
  holder: StandingHolder,
  subject: StandingSubject,
  since: Long,
  via: EntityId?,
  event: String?,
): StandingTotals {
  val record =
    when (holder) {
      is StandingHolder.Personal -> {
        if (subject !is StandingSubject.Of) return StandingTotals(0, 0)
        holder.mob.personalStanding[subject.actor]
      }
      is StandingHolder.Faction -> factionStanding[holder.label to subject]
    } ?: return StandingTotals(0, 0)

  var gained = 0L
  var lost = 0L
  for (change in record.history) {
    if (change.pulse < since) continue
    if (via != null && change.via != via) continue
    if (event != null && change.event != event) continue
    if (change.amount > 0) {
      gained += change.amount
    } else if (change.amount < 0) {
      lost += abs(change.amount.toLong())
    }
  }
  return StandingTotals(gained = gained, lost = lost)
}

fun World.pruneStanding() {
  val cutoff = pulse - standingHistoryPulses
  val factionIterator = factionStanding.entries.iterator()
  while (factionIterator.hasNext()) {
    val entry = factionIterator.next()
    val record = entry.value
    while (record.history.isNotEmpty() && record.history.first().pulse < cutoff) {
      record.history.removeFirst()
    }
    if (record.amount == 0 && record.history.isEmpty()) {
      factionIterator.remove()
    }
  }
  for (actor in actorIndex.values) {
    if (actor is Mob) {
      val personalIterator = actor.personalStanding.entries.iterator()
      while (personalIterator.hasNext()) {
        val entry = personalIterator.next()
        val record = entry.value
        while (record.history.isNotEmpty() && record.history.first().pulse < cutoff) {
          record.history.removeFirst()
        }
        if (record.amount == 0 && record.history.isEmpty()) {
          personalIterator.remove()
        }
      }
    }
  }
}

fun World.dropOrphanStanding(checkActors: Boolean = true): List<String> {
  val notes = mutableListOf<String>()
  val iter = factionStanding.entries.iterator()
  while (iter.hasNext()) {
    val entry = iter.next()
    val (holder, subject) = entry.key
    if (!protos.isFaction(holder)) {
      iter.remove()
      notes += "standing record for faction $holder dropped: holder is gone"
      continue
    }
    when (subject) {
      is StandingSubject.Faction -> {
        if (!protos.isFaction(subject.label)) {
          iter.remove()
          notes += "standing record for $holder -> ${subject.label} dropped: faction is gone"
        }
      }
      is StandingSubject.Of -> {
        if (
          checkActors && entity(subject.actor) == null && !persistence.isSavedActor(subject.actor)
        ) {
          iter.remove()
          notes += "standing record for $holder -> actor #${subject.actor.v} dropped: actor is gone"
        }
      }
    }
  }
  return notes
}

fun World.raiseStandingEvent(e: StandingEvent) {
  if (Feature.STANDING !in features) return
  if (e.affected === e.actor || !e.affected.inWorld || !e.actor.inWorld) return

  fun fire(actor: Actor, pet: Actor?) {
    val ctx =
      RulesContext(
        world = this,
        actor = e.affected,
        target = actor,
        event = e.event,
        amount = e.amount,
        item = e.item,
        affect = e.affect,
        helped = e.helped,
        pet = pet,
      )
    val deltas = rules.standingChange(ctx) ?: defaultStandingDeltas(e.affected, actor, e.event)
    for (delta in deltas) {
      applyStanding(delta.holder, delta.subject, delta.amount, e.event, via = e.affected as? Mob)
    }
  }

  fire(e.actor, null)
  val owner = ownerOf(e.actor)
  if (owner != null && owner !== e.affected) {
    fire(owner, e.actor)
  }
}

internal fun World.defaultStandingDeltas(
  affected: Actor,
  actor: Actor,
  event: String,
): List<StandingDelta> {
  return when (event) {
    "attack" -> {
      if (affected is Mob) {
        listOf(StandingDelta(StandingHolder.Personal(affected), StandingSubject.Of(actor.id), -50))
      } else {
        emptyList()
      }
    }
    "kill" -> {
      val deltas = mutableListOf<StandingDelta>()
      val fList = factionsOf(affected)
      val gList = factionsOf(actor)
      for (f in fList) {
        deltas += StandingDelta(StandingHolder.Faction(f), StandingSubject.Of(actor.id), -50)
        for (g in gList) {
          deltas += StandingDelta(StandingHolder.Faction(f), StandingSubject.Faction(g), -5)
        }
      }
      deltas
    }
    "heal",
    "help" -> {
      if (affected is Mob) {
        val current = affected.personalStanding[actor.id]?.amount ?: 0
        if (current < 25) {
          val delta = minOf(5, 25 - current)
          listOf(
            StandingDelta(StandingHolder.Personal(affected), StandingSubject.Of(actor.id), delta)
          )
        } else {
          emptyList()
        }
      } else {
        emptyList()
      }
    }
    "assist" -> {
      if (affected is Mob) {
        listOf(StandingDelta(StandingHolder.Personal(affected), StandingSubject.Of(actor.id), -10))
      } else {
        emptyList()
      }
    }
    else -> emptyList()
  }
}
