package gups.harness

import gups.core.model.Player

/**
 * What a scenario's players typed and received, and what it printed, in the order it happened. Each
 * command is tagged with the player and the room it was typed in, so output can't be read as coming
 * from the wrong place.
 */
class Transcript {
  private val text = StringBuilder()

  internal fun command(player: Player, room: String, line: String, output: String) {
    text.append(player.name).append('@').append(room).append("> ").append(line).append('\n')
    body(output)
    val now = roomOf(player)
    if (now != room)
      text.append("  (").append(player.name).append(" is now in ").append(now).append(")\n")
  }

  internal fun output(player: Player, output: String) {
    if (output.isEmpty()) return
    text.append(player.name).append('@').append(roomOf(player)).append(" (output)\n")
    body(output)
  }

  internal fun printed(line: String) {
    text.append(line).append('\n')
  }

  private fun body(output: String) {
    if (output.isEmpty()) return
    for (l in output.trimEnd('\n').lines()) text.append("  ").append(l).append('\n')
  }

  override fun toString(): String = text.toString()

  companion object {
    internal fun roomOf(player: Player): String =
      if (player.inWorld) player.room.protoLabel.v else "(not in the world)"
  }
}
