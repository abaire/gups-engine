package gups.core.model

import gups.core.EntityId
import gups.core.Label

sealed interface StandingSubject {
  data class Of(val actor: EntityId) : StandingSubject

  data class Faction(val label: Label) : StandingSubject
}

data class StandingChange(val pulse: Long, val amount: Int, val event: String, val via: EntityId?)

class StandingRecord {
  var amount: Int = 0
  var updatedPulse: Long = 0
  val history: ArrayDeque<StandingChange> = ArrayDeque()
}

data class DispositionParts(
  val declared: Int,
  val factionToActor: Int,
  val factionToFaction: Int,
  val personal: Int,
) {
  val total: Int
    get() = (declared + factionToActor + factionToFaction + personal).coerceIn(-100, 100)
}

data class StandingTotals(val gained: Long, val lost: Long)

const val STANDING_MIN = -100
const val STANDING_MAX = 100
const val STANDING_HISTORY_MAX = 1024

/** A holder of standing: a mob's personal opinion, or a faction's. */
sealed interface StandingHolder {
  data class Personal(val mob: Mob) : StandingHolder

  data class Faction(val label: Label) : StandingHolder
}

data class StandingDelta(val holder: StandingHolder, val subject: StandingSubject, val amount: Int)
