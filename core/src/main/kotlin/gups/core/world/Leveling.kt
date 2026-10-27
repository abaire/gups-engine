package gups.core.world

object LevelingMsg : MsgGroup(Feature.LEVELING) {
  val SCORE_LEVEL = msg("leveling.score", "Level {level} ({xp} xp)", "level,xp")
  val GAIN = msg("leveling.gain", "You gain {amount} experience.", "amount", "amount")
  val LEVEL_UP = msg("leveling.level_up", "You have reached level {level}!", "level", "level")
}
