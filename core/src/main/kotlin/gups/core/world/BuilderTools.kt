package gups.core.world

import gups.core.Capabilities
import gups.core.EntityId
import gups.core.Label
import gups.core.PULSE_MS
import gups.core.Role
import gups.core.model.Band
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.SkillProto

/** A debug mob despawns this long after `@spawn`, if its spawner hasn't logged out first. */
const val DEBUG_MOB_PULSES = 30L * 60 * 1000 / PULSE_MS

/** At most this many entities per `@spawn`. */
const val MAX_SPAWN = 10

private const val ERRORS_SHOWN = 10

/** The builder `@` commands, confined to the builder's own packages. */
internal object BuilderTools {
  fun all(): List<CommandDef> =
    listOf(
      staffCommand(
        "@goto",
        Role.BUILDER,
        "Move to a room in your packages or to a player.\nUsage: @goto <label|player>",
        ::goto,
      ),
      staffCommand(
        "@stat",
        Role.BUILDER,
        "Show an entity's full state.\nUsage: @stat <target|here|#id>",
        ::stat,
      ),
      staffCommand(
        "@spawn",
        Role.BUILDER,
        "Spawn debug mobs or items from a prototype.\nUsage: @spawn <label> [n]",
        ::spawn,
      ),
      staffCommand(
        "@errors",
        Role.BUILDER,
        "Show the last 10 diagnostics for a package.\nUsage: @errors [package]",
        ::errors,
      ),
    )

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
    if (target != null) gotoPlayer(w, p, target) else gotoRoom(w, p, arg)
  }

  private fun gotoPlayer(w: World, p: Player, target: Player) {
    if (target === p) return w.send(p, "You're already there.")
    // Any player, unless taken away from this account.
    if (w.refuseRestricted(p, Capabilities.GOTO_PLAYER)) return
    w.teleport(p, target.room, target.trackIndex)?.let { w.send(p, it) }
  }

  private fun gotoRoom(w: World, p: Player, arg: String) {
    val label = labelArg(arg, p) ?: return w.send(p, "No player or room called '$arg'.")
    val room = w.roomOrNull(label) ?: return w.send(p, "There is no room $label.")
    if (!p.owns(room.label.pkg)) return w.send(p, NOT_YOURS)
    w.teleport(p, room)?.let { w.send(p, it) }
  }

  private fun stat(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "stat")) return
    val arg = c.args.trim()
    if (arg.isEmpty()) return w.send(p, "Usage: @stat <keyword>, here, or #id")
    val e = statTarget(w, p, arg) ?: return statSkill(w, p, arg)
    val pkg = w.packageOf(e) ?: return w.send(p, "${e.name} is nowhere.")
    if (!p.owns(pkg)) return w.send(p, NOT_YOURS)
    w.send(p, w.statLines(e).joinToString("\n"))
  }

  private fun statTarget(w: World, p: Player, arg: String): Entity? =
    when {
      arg == "here" -> p.room
      arg.startsWith("#") -> arg.drop(1).toLongOrNull()?.let { w.entity(EntityId(it)) }
      else -> w.findActor(p, arg) ?: w.findItem(p, arg, inventory = true, ground = true)
    }

  private fun statSkill(w: World, p: Player, arg: String) {
    val skill = findSkill(w, p, arg) ?: return w.send(p, "You don't see that here.")
    if (!p.owns(skill.label.pkg)) return w.send(p, NOT_YOURS)
    val lines = buildList {
      add("${skill.label} \"${skill.name}\" (skill)")
      add("Kind: ${skill.kind}")
      if (skill.tags.isNotEmpty()) add("Tags: " + skill.tags.sorted().joinToString(", "))
    }
    w.send(p, lines.joinToString("\n"))
  }

  private fun findSkill(w: World, p: Player, arg: String): SkillProto? {
    if (Feature.SKILLS !in w.features) return null
    val lbl = labelArg(if (arg.startsWith(":") || arg.startsWith("//")) arg else ":$arg", p)
    return (if (lbl != null) w.protos.skills[lbl] else null)
      ?: w.protos.skills.values.firstOrNull { it.name.equals(arg, ignoreCase = true) }
  }

  private fun spawn(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "spawn")) return
    val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val label =
      words.firstOrNull()?.let { labelArg(it, p) }
        ?: return w.send(p, "Usage: @spawn <mob or item label, such as :goblin> [count]")
    val count = spawnCount(words) ?: return w.send(p, "The count must be a number.")
    if (count !in 1..MAX_SPAWN) return w.send(p, "You can spawn 1 to $MAX_SPAWN at a time.")
    if (!p.owns(label.pkg)) return w.send(p, NOT_YOURS)
    when {
      label in w.protos.mobs -> spawnMobs(w, p, label, count)
      label in w.protos.items -> spawnItems(w, p, label, count)
      else -> w.send(p, "There is no mob or item $label.")
    }
  }

  private fun spawnCount(words: List<String>): Int? =
    if (words.size > 1) words[1].toIntOrNull() else 1

  private fun spawnMobs(w: World, p: Player, label: Label, count: Int) {
    repeat(count) {
      val m = w.spawnMob(label, p.room, p.trackIndex)
      m.debug = true
      m.debugOwner = p.id
      m.debugUntil = w.pulse + DEBUG_MOB_PULSES
      w.send(p, "You spawn ${m.name} (#${m.id.v}, debug).")
      w.echo(p.room, "${m.name.cap()} appears.", p)
    }
  }

  private fun spawnItems(w: World, p: Player, label: Label, count: Int) {
    val stack = w.protos.item(label).stackable
    repeat(if (stack) 1 else count) {
      val item =
        w.spawnItem(label, ItemLocation.Carried(p.id), if (stack) count else 1, debug = true)
      w.send(p, "You spawn ${item.name} (#${item.id.v}, debug).")
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
    for (a in room.actors) if (a !== p) lines += whereLine(p, a.name, a.id.v, a.trackIndex)
    for (item in room.items) lines += whereLine(p, item.name, item.id.v, item.groundIndex ?: 0)
    w.send(p, lines.joinToString("\n"))
  }

  private fun whereLine(p: Player, name: String, id: Long, index: Int): String =
    "  $name #$id: index $index, ${Band.between(p.trackIndex, index).name.lowercase()}"

  /** `@errors [package]`: recent diagnostics, only of a builder's own packages. */
  private fun errors(c: CommandCall) {
    val w = c.world
    val p = c.actor as? Player ?: return
    if (w.refuseRestricted(p, "errors")) return
    val pkg = c.args.trim().removePrefix("//").substringBefore(':').ifEmpty { null }
    if (pkg != null && !p.owns(pkg)) return w.send(p, NOT_YOURS)
    val recent = recentDiagnostics(w, p, pkg)
    if (recent.isEmpty()) return w.send(p, noDiagnostics(pkg))
    w.send(p, recent.joinToString("\n") { diagnosticLine(it) })
  }

  private fun recentDiagnostics(w: World, p: Player, pkg: String?): List<Diagnostic> =
    w.diagnostics.filter { (pkg == null || it.pkg == pkg) && canSee(p, it) }.takeLast(ERRORS_SHOWN)

  private fun noDiagnostics(pkg: String?): String =
    if (pkg == null) "No diagnostics." else "No diagnostics for //$pkg."

  private fun canSee(p: Player, d: Diagnostic): Boolean =
    p.role.atLeast(Role.INSTRUCTOR) || (d.pkg != null && p.owns(d.pkg))

  private fun diagnosticLine(d: Diagnostic): String {
    val where =
      listOfNotNull(d.file, d.line?.toString()).joinToString(":").ifEmpty {
        d.pkg?.let { "//$it" } ?: "engine"
      }
    return "[${d.pulse}] ${d.kind} $where: ${d.message}"
  }
}
