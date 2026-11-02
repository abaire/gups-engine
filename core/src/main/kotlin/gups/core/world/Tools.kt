package gups.core.world

import gups.core.AttrValue
import gups.core.Capabilities
import gups.core.EntityId
import gups.core.Label
import gups.core.PULSE_MS
import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room

/** A debug mob despawns this long after `@spawn`, if its spawner hasn't logged out first. */
const val DEBUG_MOB_PULSES = 30L * 60 * 1000 / PULSE_MS

/** At most this many entities per `@spawn`. */
const val MAX_SPAWN = 10

private const val NOT_YOURS = "That isn't one of your packages."

/**
 * The builder and instructor `@` commands that need only the world. Builders are confined to their
 * packages; instructors may use them anywhere. `@trace` and `@eval` come from the script host, and
 * the account commands from `auth`.
 */
internal object Tools {
  fun all(): List<CommandDef> =
    listOf(
      // Builders.
      FastCommand(
        "@goto",
        role = Role.BUILDER,
        category = CommandCategory.SPECIAL,
        help = "Move to a room in your packages or to a player.\nUsage: @goto <label|player>",
      ) {
        goto(it)
      },
      FastCommand(
        "@stat",
        role = Role.BUILDER,
        category = CommandCategory.SPECIAL,
        help = "Show an entity's full state.\nUsage: @stat <target|here|#id>",
      ) {
        stat(it)
      },
      FastCommand(
        "@spawn",
        role = Role.BUILDER,
        category = CommandCategory.SPECIAL,
        help = "Spawn debug mobs or items from a prototype.\nUsage: @spawn <label> [n]",
      ) {
        spawn(it)
      },
      FastCommand(
        "@errors",
        role = Role.BUILDER,
        category = CommandCategory.SPECIAL,
        help = "Show the last 10 diagnostics for a package.\nUsage: @errors [package]",
      ) {
        errors(it)
      },
      // Instructors.
      FastCommand(
        "@reload",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Reload world scripts and definitions from disk.\nUsage: @reload",
      ) {
        reload(it)
      },
      FastCommand(
        "@deploy",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Fetch and checkout latest world commits.\nUsage: @deploy",
      ) {
        deploy(it)
      },
      FastCommand(
        "@quarantine",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Quarantine a package to disable its execution.\nUsage: @quarantine <package>",
      ) {
        quarantine(it, on = true)
      },
      FastCommand(
        "@unquarantine",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Lift quarantine from a package.\nUsage: @unquarantine <package>",
      ) {
        quarantine(it, on = false)
      },
      FastCommand(
        "@bubble",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Toggle simulation bubble on or off.\nUsage: @bubble",
      ) {
        bubble(it)
      },
      FastCommand(
        "@stats",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Show engine performance and scheduler stats.\nUsage: @stats",
      ) {
        stats(it)
      },
      FastCommand(
        "@who",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Show staff view of connected players.\nUsage: @who",
      ) {
        who(it)
      },
      FastCommand(
        "@snapshot",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Trigger an immediate persistence checkpoint.\nUsage: @snapshot",
      ) {
        snapshot(it)
      },
      FastCommand(
        "@shutdown",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Gracefully shut down the server.\nUsage: @shutdown [delay]",
      ) {
        shutdown(it)
      },
      FastCommand(
        "@audit",
        role = Role.INSTRUCTOR,
        category = CommandCategory.SPECIAL,
        help = "Show recent security audit records.\nUsage: @audit [n]",
      ) {
        audit(it)
      },
    )

  // ---- Builders ----------------------------------------------------------------------------

  private fun goto(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "goto")) return
    val arg = c.args.trim()
    if (arg.isEmpty())
      return w.send(
        p,
        "Usage: @goto <room label, such as //zones/forest:clearing or :clearing> or <player>",
      )
    val target = w.players.firstOrNull { it.name.equals(arg, ignoreCase = true) }
    if (target != null) {
      if (target === p) return w.send(p, "You're already there.")
      // Any player, unless taken away from this account.
      if (w.refuseRestricted(p, Capabilities.GOTO_PLAYER)) return
      return w.teleport(p, target.room, target.trackIndex)
    }
    val label = labelArg(arg, p) ?: return w.send(p, "No player or room called '$arg'.")
    val room = w.roomOrNull(label) ?: return w.send(p, "There is no room $label.")
    if (!p.owns(room.label.pkg)) return w.send(p, NOT_YOURS)
    w.teleport(p, room)
  }

  private fun stat(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "stat")) return
    val arg = c.args.trim()
    if (arg.isEmpty()) return w.send(p, "Usage: @stat <keyword>, here, or #id")
    val e: Entity? =
      when {
        arg == "here" -> p.room
        arg.startsWith("#") -> arg.drop(1).toLongOrNull()?.let { w.entity(EntityId(it)) }
        else -> w.findActor(p, arg) ?: w.findItem(p, arg, inventory = true, ground = true)
      }
    if (e == null) {
      val skill =
        if (Feature.SKILLS in w.features) {
          val lbl = labelArg(if (arg.startsWith(":") || arg.startsWith("//")) arg else ":$arg", p)
          (if (lbl != null) w.protos.skills[lbl] else null)
            ?: w.protos.skills.values.firstOrNull { it.name.equals(arg, ignoreCase = true) }
        } else null
      if (skill != null) {
        if (!p.owns(skill.label.pkg)) return w.send(p, NOT_YOURS)
        val lines = buildList {
          add("${skill.label} \"${skill.name}\" (skill)")
          add("Kind: ${skill.kind}")
          if (skill.tags.isNotEmpty()) add("Tags: " + skill.tags.sorted().joinToString(", "))
        }
        return w.send(p, lines.joinToString("\n"))
      }
      return w.send(p, "You don't see that here.")
    }
    val pkg = w.packageOf(e) ?: return w.send(p, "${e.name} is nowhere.")
    if (!p.owns(pkg)) return w.send(p, NOT_YOURS)
    w.send(p, w.statLines(e).joinToString("\n"))
  }

  private fun spawn(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "spawn")) return
    val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val label =
      words.firstOrNull()?.let { labelArg(it, p) }
        ?: return w.send(p, "Usage: @spawn <mob or item label, such as :goblin> [count]")
    val count =
      words.getOrNull(1)?.toIntOrNull()
        ?: if (words.size > 1) return w.send(p, "The count must be a number.") else 1
    if (count !in 1..MAX_SPAWN) return w.send(p, "You can spawn 1 to $MAX_SPAWN at a time.")
    if (!p.owns(label.pkg)) return w.send(p, NOT_YOURS)
    when {
      label in w.protos.mobs ->
        repeat(count) {
          val m = w.spawnMob(label, p.room, p.trackIndex)
          m.debug = true
          m.debugOwner = p.id
          m.debugUntil = w.pulse + DEBUG_MOB_PULSES
          w.send(p, "You spawn ${m.name} (#${m.id.v}, debug).")
          w.echo(p.room, "${m.name.cap()} appears.", p)
        }
      label in w.protos.items -> {
        val stack = w.protos.item(label).stackable
        repeat(if (stack) 1 else count) {
          val item =
            w.spawnItem(label, ItemLocation.Carried(p.id), if (stack) count else 1, debug = true)
          w.send(p, "You spawn ${item.name} (#${item.id.v}, debug).")
        }
      }
      else -> w.send(p, "There is no mob or item $label.")
    }
  }

  internal fun where(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "where")) return
    val room = p.room
    if (!p.owns(room.label.pkg)) return w.send(p, NOT_YOURS)
    val lines =
      mutableListOf(
        "${room.label}: ${room.trackSize} slots (0-${room.trackSize - 1}). You are at ${p.trackIndex}."
      )
    for (a in room.actors) {
      if (a === p) continue
      lines +=
        "  ${a.name} #${a.id.v}: index ${a.trackIndex}, ${Band.between(p.trackIndex, a.trackIndex).name.lowercase()}"
    }
    for (item in room.items) {
      val at = item.groundIndex ?: 0
      lines +=
        "  ${item.name} #${item.id.v}: index $at, ${Band.between(p.trackIndex, at).name.lowercase()}"
    }
    w.send(p, lines.joinToString("\n"))
  }

  /** `@errors [package]`: recent diagnostics, only of a builder's own packages. */
  private fun errors(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "errors")) return
    val pkg = c.args.trim().removePrefix("//").substringBefore(':').ifEmpty { null }
    if (pkg != null && !p.owns(pkg)) return w.send(p, NOT_YOURS)
    val mine = { d: Diagnostic ->
      p.role.atLeast(Role.INSTRUCTOR) || (d.pkg != null && p.owns(d.pkg))
    }
    val recent =
      w.diagnostics.filter { (pkg == null || it.pkg == pkg) && mine(it) }.takeLast(ERRORS_SHOWN)
    if (recent.isEmpty())
      return w.send(p, if (pkg == null) "No diagnostics." else "No diagnostics for //$pkg.")
    val lines =
      recent.map { d ->
        val where =
          listOfNotNull(d.file, d.line?.toString()).joinToString(":").ifEmpty {
            d.pkg?.let { "//$it" } ?: "engine"
          }
        "[${d.pulse}] ${d.kind} $where: ${d.message}"
      }
    w.send(p, lines.joinToString("\n"))
  }

  private const val ERRORS_SHOWN = 10

  // ---- Instructors -------------------------------------------------------------------------

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
      w.post?.invoke(gups.core.WorldControlEvent.Task { it.send(p, "[deploy] $line") }) ?: Unit
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
    val active = w.activeRooms
    val lines = mutableListOf("Active rooms: ${active.size} of ${w.rooms.size}.")
    for ((pkg, rooms) in active.groupBy { it.label.pkg }.toSortedMap()) {
      lines += "  //$pkg: " + rooms.sortedBy { it.label.v }.joinToString(", ") { it.label.name }
    }
    val anchors =
      w.allEntities.filter { it is Player || it.anchorReasons.isNotEmpty() }.sortedBy { it.id }
    lines += "Anchors:" + if (anchors.isEmpty()) " none." else ""
    for (e in anchors) {
      val why = if (e is Player) "player" else e.anchorReasons.joinToString(", ")
      lines += "  ${e.name} #${e.id.v} ($why) in ${w.roomOfEntity(e)?.label ?: "nowhere"}"
    }
    val outside =
      w.allEntities
        .filter { it.activeReasons.isNotEmpty() && w.roomOfEntity(it)?.isDormant != false }
        .sortedBy { it.id }
    if (outside.isNotEmpty()) {
      lines += "Active outside the bubble:"
      for (e in outside) lines +=
        "  ${e.name} #${e.id.v} (${e.activeReasons.joinToString(", ")}) in ${w.roomOfEntity(e)?.label ?: "nowhere"}"
    }
    val dormant =
      w.rooms.filter { it.isDormant }.groupingBy { it.label.pkg }.eachCount().toSortedMap()
    lines +=
      "Dormant rooms: " +
        if (dormant.isEmpty()) "none."
        else dormant.entries.joinToString(", ") { "//${it.key} ${it.value}" } + "."
    w.send(c.actor, lines.joinToString("\n"))
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
    val lines =
      w.players
        .sortedBy { it.name }
        .map { p ->
          val s = p.session?.let(w.sessions::get)
          val idle = seconds((w.pulse - p.lastInputPulse) * PULSE_MS / 1000)
          val state =
            p.linkDeadSince?.let { "link-dead ${seconds((w.pulse - it) * PULSE_MS / 1000)}" }
              ?: "${s?.transport ?: "?"} ${s?.remote ?: "?"}"
          "  ${p.name.padEnd(16)} ${p.role.name.lowercase().padEnd(10)} $state, idle $idle"
        }
    w.send(
      c.actor,
      if (lines.isEmpty()) "Nobody is playing." else "Players:\n" + lines.joinToString("\n"),
    )
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

  // ---- Helpers -----------------------------------------------------------------------------

  /** `//pkg:name`, or `:name` in the package of [p]'s room. */
  private fun labelArg(text: String, p: Player): Label? =
    runCatching { Label.parse(text, p.room.label.pkg) }.getOrNull()

  private fun seconds(s: Long): String =
    when {
      s < 60 -> "${s}s"
      s < 3600 -> "${s / 60}m"
      else -> "${s / 3600}h${(s % 3600) / 60}m"
    }
}

/**
 * The package a builder needs to own to inspect [e]: a room's own, a mob's or item's prototype
 * package, and for players and engine items (corpses) the package of their room.
 */
fun World.packageOf(e: Entity): String? =
  when {
    e is Room -> e.label.pkg
    e is Player || e.protoLabel.pkg == "engine" -> roomOfEntity(e)?.label?.pkg
    else -> e.protoLabel.pkg
  }

/** Where an item is, in words. */
internal fun World.describeLocation(loc: ItemLocation): String =
  when (loc) {
    is ItemLocation.InRoom ->
      "in ${(entity(loc.room) as? Room)?.label ?: "room #${loc.room.v}"} at ${loc.trackIndex}"
    is ItemLocation.Carried -> "carried by ${actor(loc.actor)?.name ?: "#${loc.actor.v}"}"
    is ItemLocation.Equipped ->
      "worn by ${actor(loc.actor)?.name ?: "#${loc.actor.v}"} (${loc.slot})"
    is ItemLocation.Contained -> "inside ${item(loc.container)?.name ?: "#${loc.container.v}"}"
  }

/** `@stat`: an entity's whole state. */
internal fun World.statLines(e: Entity): List<String> = buildList {
  val kind =
    when (e) {
      is Room -> "room"
      is Player -> "player"
      is Mob -> "mob"
      is Item -> "item"
    }
  add(
    "#${e.id.v} ${e.protoLabel} \"${e.name}\" ($kind, version ${e.protoVersion}${if (e.debug) ", debug" else ""})"
  )
  when (e) {
    is Actor -> {
      add(
        "Location: ${e.room.label}" +
          if (Feature.TRACK in features) " index ${e.trackIndex}" else ""
      )
      add(
        "Vitals: " +
          e.vitalMap.entries.joinToString(", ") { (name, v) ->
            val effMax = e.max(name)
            "$name ${v.current}/$effMax" +
              (if (effMax != v.max) " (base ${v.max})" else "") +
              (v.belowZeroSince?.let { " (below 0 since pulse $it)" } ?: "")
          } +
          (if (Feature.LEVELING in features) "; level ${e.level}, xp ${e.xp}" else "") +
          "; condition ${e.condition.word}"
      )
      add(
        "Stats: " +
          e.baseStats.keys.joinToString(", ") { s ->
            "$s ${e.stat(s)}" + if (e.stat(s) != e.baseStats[s]) " (base ${e.baseStats[s]})" else ""
          }
      )
      if (schema.damageTypes.isNotEmpty())
        add(
          "Resists: " +
            schema.damageTypes.joinToString(", ") { type ->
              val eff = e.effectiveResist(type)
              val base = e.baseResist(type)
              "$type $eff" + if (eff != base) " (base $base)" else ""
            }
        )
      if (e is Player && e.choices.isNotEmpty()) {
        add(
          "Answers: " +
            e.choices.entries.joinToString(", ") { (c, opt) ->
              val known =
                creation?.choices?.firstOrNull { it.id == c }?.options?.any { it.id == opt } == true
              "$c=$opt" + if (known) "" else " (unknown)"
            }
        )
      }
      if (e.grantedTraits.isNotEmpty()) {
        add(
          "Granted traits: " +
            e.grantedTraits.sorted().joinToString(", ") { t ->
              t + if (t in traits) "" else " (unknown)"
            }
        )
      }
      if (e.traits.isNotEmpty()) {
        add(
          "Traits: " +
            e.traits.joinToString(", ") { t ->
              val sources = mutableListOf<String>()
              if (e is Player) {
                creation?.choices?.forEach { choice ->
                  val optId = e.choices[choice.id]
                  val opt = choice.options.firstOrNull { it.id == optId }
                  if (opt?.traits?.contains(t.name) == true) {
                    sources += "${choice.id}=${opt.id}"
                  }
                }
              } else if (e is Mob) {
                if (t.name in e.proto.traits) sources += "prototype"
              }
              if (t.name in e.grantedTraits) sources += "granted"
              "${t.name} (${sources.joinToString()})"
            }
        )
      }
      if (e.skills.isNotEmpty())
        add("Skills: " + e.skills.entries.joinToString(", ") { "${it.key} ${it.value}" })
      add(
        "Affects: " +
          if (e.affects.isEmpty()) "none"
          else
            e.affects.joinToString(", ") { a ->
              (if (a.wornFrom != null) "${a.name} (while worn"
              else "${a.name} (${(a.expiresAt - pulse).coerceAtLeast(0) * PULSE_MS / 1000}s left") +
                (if (a.flags.isEmpty()) "" else "; ${a.flags.joinToString()}") +
                ")"
            }
      )
      val doing =
        listOfNotNull(
          e.casting?.let { "casting until pulse ${it.endsAt}" },
          e.waitState.takeIf { it > 0 }?.let { "recovering $it pulses" },
          e.actionQueue.takeIf { it.isNotEmpty() }?.let { q -> "queued: " + q.joinToString("; ") },
          e.fighting
            .takeIf { it.isNotEmpty() }
            ?.let { f -> "fighting " + f.joinToString(", ") { actor(it)?.name ?: "#${it.v}" } },
        )
      if (doing.isNotEmpty()) add("Doing: " + doing.joinToString("; "))
      if (e.inventory.isNotEmpty())
        add("Carrying: " + e.inventory.joinToString(", ") { "${it.name} #${it.id.v}" })
      if (e.equipment.isNotEmpty())
        add(
          "Wearing: " +
            e.equipment.entries.joinToString(", ") {
              "${it.key}: ${it.value.name} #${it.value.id.v}"
            }
        )
      if (e is Mob && e.debug)
        add(
          "Debug: spawned by ${e.debugOwner?.let(::actor)?.name ?: "someone gone"}, despawns in ${((e.debugUntil ?: pulse) - pulse) * PULSE_MS / 1000}s"
        )
      if (e is Player)
        add(
          "Account: ${e.accountId ?: "none"}, role ${e.role.name.lowercase()}" +
            if (e.role == Role.BUILDER)
              ", packages ${e.scope.sorted().joinToString().ifEmpty { "none" }}"
            else ""
        )
    }
    is Item -> {
      add("Location: ${describeLocation(e.location)}")
      add(
        "Count ${e.count}, weight ${e.weight}" + (e.decayAt?.let { ", decays at pulse $it" } ?: "")
      )
      if (e.tags.isNotEmpty()) add("Tags: " + e.tags.sorted().joinToString(", "))
      val held = e.adminAffectsList()
      add(
        "Affects: " +
          if (held.isEmpty()) "none"
          else
            held.joinToString(", ") { ha ->
              val timing =
                when {
                  ha.instance == null -> "prototype"
                  ha.instance.expiresAt == Long.MAX_VALUE -> "permanent"
                  else ->
                    "${(ha.instance.expiresAt - pulse).coerceAtLeast(0) * PULSE_MS / 1000}s left"
                }
              val flags = if (ha.flags.isEmpty()) "" else "; " + ha.flags.sorted().joinToString()
              "${ha.name} (${ha.applies.name.lowercase()}, $timing$flags)"
            }
      )
      val tagChanges = e.addedTags.map { "+$it" } + e.removedTags.map { "-$it" }
      if (tagChanges.isNotEmpty()) add("Tag changes: " + tagChanges.joinToString(", "))
      if (e.contents.isNotEmpty())
        add("Contains: " + e.contents.joinToString(", ") { "${it.name} #${it.id.v}" })
    }
    is Room -> {
      add(
        "Size ${e.trackSize}; exits: " +
          e.exits.values
            .joinToString(", ") { x ->
              x.direction.name +
                (x.door?.let { " (${it.state.name.lowercase()})" } ?: "") +
                " -> ${x.to}"
            }
            .ifEmpty { "none" }
      )
      if (e.tags.isNotEmpty()) add("Tags: " + e.tags.sorted().joinToString(", "))
      add("Here: ${e.actors.size} actors, ${e.items.size} items on the ground")
    }
  }
  add(
    "Timers: " +
      if (e.timers.isEmpty()) "none"
      else
        e.timers.joinToString(", ") { t ->
          "${t.fn.file}:${t.fn.name} " +
            (t.due?.let { "in ${it - pulse} pulses" } ?: "paused, ${t.remaining} left")
        }
  )
  val attrs = e.customAttributes.entries.groupBy { it.key.substringBefore(':', "") }
  if (attrs.isEmpty()) add("Attributes: none")
  for ((ns, entries) in attrs.toSortedMap()) {
    add(
      "Attributes ${ns.ifEmpty { "(none)" }}: " +
        entries.joinToString(", ") { "${it.key.substringAfter(':')}=${render(it.value)}" }
    )
  }
  val room = roomOfEntity(e)
  add(
    "Simulation: " +
      (if (isSimulated(e)) "simulated" else "dormant") +
      (if (e.activeReasons.isNotEmpty()) "; active: ${e.activeReasons.joinToString()}" else "") +
      (if (e.anchorReasons.isNotEmpty()) "; anchor: ${e.anchorReasons.joinToString()}" else "") +
      (room?.dormantSince?.let { "; ${room.label} dormant since pulse $it" } ?: "")
  )
}

private fun render(v: AttrValue): String =
  when (v) {
    is AttrValue.I -> v.v.toString()
    is AttrValue.S -> "\"${v.v}\""
    is AttrValue.B -> if (v.v) "True" else "False"
    is AttrValue.L -> v.v.joinToString(", ", "[", "]") { render(it) }
    is AttrValue.M ->
      v.v.entries.joinToString(", ", "{", "}") { "\"${it.key}\": ${render(it.value)}" }
  }

// ---- Debug spawns -----------------------------------------------------------------------

/** Marks [item] and everything inside it as debug, so none of it is saved. */
internal fun markDebug(item: Item) {
  item.debug = true
  for (inner in item.contentsIndex.values) markDebug(inner)
}

/** Debug mobs go when their spawner logs out. */
internal fun World.despawnDebugMobs(spawner: Player) {
  for (m in
    actorIndex.values.filterIsInstance<Mob>().filter {
      it.debug && it.debugOwner == spawner.id
    }) despawnDebug(m)
}

/** Debug mobs past their 30 minutes, or whose spawner is gone, despawn at the world tick. */
internal fun World.expireDebugMobs() {
  for (m in actorIndex.values.filterIsInstance<Mob>()) {
    if (!m.debug) continue
    val owner = m.debugOwner?.let(::actor)
    if ((m.debugUntil ?: Long.MAX_VALUE) <= pulse || owner == null) despawnDebug(m)
  }
}

private fun World.despawnDebug(m: Mob) {
  if (!m.inWorld) return
  disengageAll(m)
  val room = m.room
  for (item in m.inventory + m.equipment.values) markDebug(item)
  removeActor(m)
  echo(room, "${m.name.cap()} fades away.")
}
