package gups.core.model

/** What one actor has done toward a fight against a victim; held on the victim, never saved. */
class Contribution {
  /** Vital loss the contributor dealt to the victim. */
  var damage: Int = 0

  /** Effective healing the contributor gave an actor engaged with the victim. */
  var healing: Int = 0

  /** Affects the contributor applied: a bane on the victim, or help on an engaged ally. */
  var support: Int = 0

  /** The pulse of the contributor's most recent contribution of any kind. */
  var lastPulse: Long = 0
}
