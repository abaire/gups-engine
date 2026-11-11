package gups.persist

import gups.core.EntityId
import gups.core.Label
import gups.core.model.StandingChange
import gups.core.model.StandingRecord
import gups.core.model.StandingSubject
import gups.core.world.World
import kotlinx.serialization.builtins.ListSerializer

private const val ACTOR_PREFIX = "actor:"

/** Replaces [World.factionStanding] with the saved [rows]. */
internal fun World.loadStanding(rows: List<StandingRow>) {
  factionStanding.clear()
  for (row in rows) {
    val subject =
      if (row.subject.startsWith(ACTOR_PREFIX)) {
        StandingSubject.Of(EntityId(row.subject.removePrefix(ACTOR_PREFIX).toLong()))
      } else {
        StandingSubject.Faction(Label(row.subject))
      }
    val record = StandingRecord()
    record.amount = row.amount
    record.updatedPulse = row.updatedPulse
    val history =
      JSON.decodeFromString(ListSerializer(StandingChangeJson.serializer()), row.historyJson)
    for (h in history) {
      record.history.add(StandingChange(h.pulse, h.amount, h.event, h.via?.let(::EntityId)))
    }
    factionStanding[Label(row.holder) to subject] = record
  }
}

/** The rows that save [World.factionStanding]. */
internal fun World.standingRows(): List<StandingRow> =
  factionStanding.map { (key, record) ->
    val (holder, subject) = key
    val subjectStr =
      when (subject) {
        is StandingSubject.Of -> "$ACTOR_PREFIX${subject.actor.v}"
        is StandingSubject.Faction -> subject.label.v
      }
    val history =
      record.history.map { StandingChangeJson(it.pulse, it.amount, it.event, it.via?.v) }
    val historyJson = JSON.encodeToString(ListSerializer(StandingChangeJson.serializer()), history)
    StandingRow(holder.v, subjectStr, record.amount, record.updatedPulse, historyJson)
  }
