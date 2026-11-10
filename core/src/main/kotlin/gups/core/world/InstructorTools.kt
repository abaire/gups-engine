package gups.core.world

import gups.core.EntityId
import gups.core.PULSE_MS
import gups.core.Role
import gups.core.WorldControlEvent
import gups.core.model.Entity
import gups.core.model.Player

/** The instructor `@` commands, usable anywhere. */
internal object InstructorTools {
  fun all(): List<CommandDef> =
    listOf(
      staffCommand(
        "@reload",
        Role.INSTRUCTOR,
        "Reload world scripts and definitions from disk.\nUsage: @reload",
        ::reload,
      ),
      staffCommand(
        "@deploy",
        Role.INSTRUCTOR,
        "Fetch and checkout latest world commits.\nUsage: @deploy",
        ::deploy,
      ),
      staffCommand(
        "@quarantine",
        Role.INSTRUCTOR,
        "Quarantine a package to disable its execution.\nUsage: @quarantine <package>",
      ) {
        quarantine(it, on = true)
      },
      staffCommand(
        "@unquarantine",
        Role.INSTRUCTOR,
        "Lift quarantine from a package.\nUsage: @unquarantine <package>",
      ) {
        quarantine(it, on = false)
      },
      staffCommand(
        "@bubble",
        Role.INSTRUCTOR,
        "Toggle simulation bubble on or off.\nUsage: @bubble",
        ::bubble,
      ),
      staffCommand(
        "@stats",
        Role.INSTRUCTOR,
        "Show engine performance and scheduler stats.\nUsage: @stats",
        ::stats,
      ),
      staffCommand(
        "@who",
        Role.INSTRUCTOR,
        "Show staff view of connected players.\nUsage: @who",
        ::who,
      ),
      staffCommand(
        "@snapshot",
        Role.INSTRUCTOR,
        "Trigger an immediate persistence checkpoint.\nUsage: @snapshot",
        ::snapshot,
      ),
      staffCommand(
        "@shutdown",
        Role.INSTRUCTOR,
        "Gracefully shut down the server.\nUsage: @shutdown [delay]",
        ::shutdown,
      ),
      staffCommand(
        "@audit",
        Role.INSTRUCTOR,
        "Show recent security audit records.\nUsage: @audit [n]",
        ::audit,
      ),
    )

  private fun reload(c: CommandCall) {
    val w = c.world
    if (w.config.production)
      return w.send(c.actor, "@reload is off on this server: changes go live with @deploy.")
    val packages =
      c.args
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { it.removePrefix("//").substringBefore(':') }
    if (packages.isEmpty())
      return w.send(c.actor, "Usage: @reload <package...>, such as @reload //zones/forest")
    val known = w.scripts.packages
    packages
      .firstOrNull { known.isNotEmpty() && it !in known }
      ?.let {
        return w.send(c.actor, "There is no package //$it.")
      }
    w.send(c.actor, "Reloading ${packages.joinToString(", ") { "//$it" }}.")
    w.reload(packages)
  }

  /** `@deploy [commit]`: progress is reported in game as it happens. */
  private fun deploy(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    val d =
      w.deploys
        ?: return w.send(
          p,
          "This server can't deploy: it needs --prod, git, and a world that is a git working tree.",
        )
    val commit = c.args.trim().ifEmpty { null }
    val tell = { line: String ->
      w.post?.invoke(WorldControlEvent.Task { it.send(p, "[deploy] $line") }) ?: Unit
    }
    w.send(p, "Deploying ${commit ?: "the tip of the deploy branch"}...")
    d.start(commit, tell) { o ->
      tell(
        if (o.ok)
          "Deployed ${o.commit}: ${o.packages.joinToString().ifEmpty { "no packages changed" }}."
        else "The deploy failed; nothing changed.\n" + o.errors.joinToString("\n")
      )
    }
  }

  private fun quarantine(c: CommandCall, on: Boolean) {
    val w = c.world
    val pkg = c.args.trim().removePrefix("//").substringBefore(':')
    if (pkg.isEmpty())
      return w.send(c.actor, "Usage: ${if (on) "@quarantine" else "@unquarantine"} <package>")
    val known = w.scripts.packages
    if (known.isNotEmpty() && pkg !in known) return w.send(c.actor, "There is no package //$pkg.")
    val already = pkg in w.scripts.quarantinedPackages
    if (on == already)
      return w.send(c.actor, "//$pkg is ${if (on) "already" else "not"} quarantined.")
    if (on) w.scripts.quarantine(w, pkg, "by ${c.actor.name}") else w.scripts.unquarantine(w, pkg)
    // The state persists.
    w.checkpointNow(if (on) "quarantine" else "unquarantine")
    w.send(c.actor, if (on) "//$pkg is quarantined." else "//$pkg is running again.")
  }

  private fun bubble(c: CommandCall) {
    val w = c.world
    if (!w.config.bubble)
      return w.send(c.actor, "The reality bubble is off: every room is simulated.")
    val lines = buildList {
      addAll(activeRoomLines(w))
      addAll(anchorLines(w))
      addAll(outsideBubbleLines(w))
      add(dormantLine(w))
    }
    w.send(c.actor, lines.joinToString("\n"))
  }

  private fun activeRoomLines(w: World): List<String> {
    val active = w.activeRooms
    return listOf("Active rooms: ${active.size} of ${w.rooms.size}.") +
      active
        .groupBy { it.label.pkg }
        .toSortedMap()
        .map { (pkg, rooms) ->
          "  //$pkg: " + rooms.sortedBy { it.label.v }.joinToString(", ") { it.label.name }
        }
  }

  private fun anchorLines(w: World): List<String> {
    val anchors =
      w.allEntities.filter { it is Player || it.anchorReasons.isNotEmpty() }.sortedBy { it.id }
    return listOf("Anchors:" + if (anchors.isEmpty()) " none." else "") +
      anchors.map { e ->
        val why = if (e is Player) "player" else e.anchorReasons.joinToString(", ")
        entityLine(w, e, why)
      }
  }

  private fun outsideBubbleLines(w: World): List<String> {
    val outside =
      w.allEntities
        .filter { it.activeReasons.isNotEmpty() && w.roomOfEntity(it)?.isDormant != false }
        .sortedBy { it.id }
    if (outside.isEmpty()) return emptyList()
    return listOf("Active outside the bubble:") +
      outside.map { entityLine(w, it, it.activeReasons.joinToString(", ")) }
  }

  private fun entityLine(w: World, e: Entity, why: String): String =
    "  ${e.name} #${e.id.v} ($why) in ${w.roomOfEntity(e)?.label ?: "nowhere"}"

  private fun dormantLine(w: World): String {
    val dormant =
      w.rooms.filter { it.isDormant }.groupingBy { it.label.pkg }.eachCount().toSortedMap()
    return "Dormant rooms: " +
      if (dormant.isEmpty()) "none."
      else dormant.entries.joinToString(", ") { "//${it.key} ${it.value}" } + "."
  }

  private fun stats(c: CommandCall) {
    val w = c.world
    val s = w.pulseStats
    val sessions = w.sessions.values.groupingBy { it.transport }.eachCount().toSortedMap()
    val lines =
      listOf(
        "Pulses: p50 ${"%.1f".format(s.quantileMs(0.5))} ms, p99 ${"%.1f".format(s.quantileMs(0.99))} ms, " +
          "${s.overruns} overruns in the last ${s.count} pulses.",
        "Input queue: ${w.queueDepth()} lines.",
        "Bubble: ${if (w.config.bubble) "${w.activeRooms.size} of ${w.rooms.size} rooms active" else "off"}.",
        "Sessions: " +
          (if (sessions.isEmpty()) "none"
          else sessions.entries.joinToString(", ") { "${it.key} ${it.value}" }) +
          ".",
        "Checkpoint backlog: ${w.persistence.backlog} batches.",
      ) +
        listOfNotNull(
          w.busiestPackage()?.let { (pkg, n) -> "Most script steps this minute: //$pkg ($n)." }
        )
    w.send(c.actor, lines.joinToString("\n"))
  }

  private fun who(c: CommandCall) {
    val w = c.world
    val lines = w.players.sortedBy { it.name }.map { whoLine(w, it) }
    w.send(
      c.actor,
      if (lines.isEmpty()) "Nobody is playing." else "Players:\n" + lines.joinToString("\n"),
    )
  }

  private fun whoLine(w: World, p: Player): String {
    val s = p.session?.let(w.sessions::get)
    val idle = seconds((w.pulse - p.lastInputPulse) * PULSE_MS / 1000)
    val state =
      p.linkDeadSince?.let { "link-dead ${seconds((w.pulse - it) * PULSE_MS / 1000)}" }
        ?: "${s?.transport ?: "?"} ${s?.remote ?: "?"}"
    return "  ${p.name.padEnd(16)} ${p.role.name.lowercase().padEnd(10)} $state, idle $idle"
  }

  private fun snapshot(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    w.checkpointNow("snapshot")
    w.send(p, "Taking a snapshot...")
    w.later({ w.persistence.backup() }) { r ->
      send(p, r.fold({ "Snapshot saved: $it" }) { "Snapshot failed: ${it.message}" })
      flushPlayer(p)
    }
  }

  private fun shutdown(c: CommandCall) {
    val w = c.world
    val (first, rest) =
      c.args.trim().let { a -> a.substringBefore(' ') to a.substringAfter(' ', "") }
    val delay = first.toLongOrNull()
    val reason =
      (if (delay == null) c.args.trim() else rest.trim()).ifEmpty { "shut down by ${c.actor.name}" }
    if (delay != null && delay < 0) return w.send(c.actor, "The delay is in seconds, 0 or more.")
    w.scheduleShutdown((delay ?: 0) * 1000 / PULSE_MS, reason)
  }

  private fun audit(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    val words = c.args.trim().split(Regex("\\s+"))
    val id = words.getOrNull(1)?.removePrefix("#")?.toLongOrNull()
    if (words.firstOrNull() != "item" || id == null) return w.send(p, "Usage: @audit item <id>")
    val live =
      w.item(EntityId(id))?.let { "#$id is ${it.name}, now ${w.describeLocation(it.location)}." }
    w.later({ w.persistence.itemHistory(id) }) { r ->
      val rows =
        r.getOrElse {
          send(p, "The audit failed: ${it.message}")
          return@later flushPlayer(p)
        }
      val lines =
        listOfNotNull(live) +
          if (rows.isEmpty()) {
            listOf("No saved custody changes for item #$id.")
          } else {
            rows.map {
              "  ${wallTime(it.wallTime)} pulse ${it.pulse} ${it.cause}: ${it.from ?: "-"} -> ${it.to ?: "-"}"
            }
          }
      send(p, lines.joinToString("\n"))
      flushPlayer(p)
    }
  }

  private fun seconds(s: Long): String =
    when {
      s < 60 -> "${s}s"
      s < 3600 -> "${s / 60}m"
      else -> "${s / 3600}h${(s % 3600) / 60}m"
    }
}
