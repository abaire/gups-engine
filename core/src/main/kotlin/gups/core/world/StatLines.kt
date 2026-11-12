package gups.core.world

import gups.core.AttrValue
import gups.core.PULSE_MS
import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.pulsesToSeconds

/** `@stat`: an entity's whole state. */
internal fun World.statLines(e: Entity): List<String> = buildList {
  add(headerLine(e))
  when (e) {
    is Actor -> addAll(actorLines(e))
    is Item -> addAll(itemLines(e))
    is Room -> addAll(roomLines(e))
  }
  add(timersLine(e))
  addAll(attributeLines(e))
  add(simulationLine(e))
}

private fun World.headerLine(e: Entity): String {
  val kind =
    when (e) {
      is Room -> "room"
      is Player -> "player"
      is Mob -> "mob"
      is Item -> "item"
    }
  return "#${e.id.v} ${e.protoLabel} \"${e.name}\" ($kind, version ${e.protoVersion}${if (e.debug) ", debug" else ""})"
}

// ---- Actors ------------------------------------------------------------------------------

private fun World.actorLines(e: Actor): List<String> =
  listOfNotNull(
    "Location: ${e.room.label}" + if (Feature.TRACK in features) " index ${e.trackIndex}" else "",
    vitalsLine(e),
    statsLine(e),
    resistsLine(e),
    answersLine(e),
    grantedTraitsLine(e),
    traitsLine(e),
    skillsLine(e),
    affectsLine(e),
    doingLine(e),
    carryingLine(e),
    wearingLine(e),
    debugLine(e),
    accountLine(e),
  )

private fun World.vitalsLine(e: Actor): String =
  "Vitals: " +
    e.vitalMap.entries.joinToString(", ") { (name, v) ->
      val effMax = e.max(name)
      "$name ${v.current}/$effMax" +
        (if (effMax != v.max) " (base ${v.max})" else "") +
        (v.belowZeroSince?.let { " (below 0 since pulse $it)" } ?: "")
    } +
    (if (Feature.LEVELING in features) "; level ${e.level}, xp ${e.xp}" else "") +
    "; condition ${e.condition.word}"

private fun statsLine(e: Actor): String =
  "Stats: " +
    e.baseStats.keys.joinToString(", ") { s ->
      "$s ${e.stat(s)}" + if (e.stat(s) != e.baseStats[s]) " (base ${e.baseStats[s]})" else ""
    }

private fun World.resistsLine(e: Actor): String? {
  if (schema.damageTypes.isEmpty()) return null
  return "Resists: " +
    schema.damageTypes.joinToString(", ") { type ->
      val eff = e.effectiveResist(type)
      val base = e.baseResist(type)
      "$type $eff" + if (eff != base) " (base $base)" else ""
    }
}

private fun World.answersLine(e: Actor): String? {
  if (e !is Player || e.choices.isEmpty()) return null
  return "Answers: " +
    e.choices.entries.joinToString(", ") { (c, opt) ->
      val known =
        creation?.choices?.firstOrNull { it.id == c }?.options?.any { it.id == opt } == true
      "$c=$opt" + if (known) "" else " (unknown)"
    }
}

private fun World.grantedTraitsLine(e: Actor): String? {
  if (e.grantedTraits.isEmpty()) return null
  return "Granted traits: " +
    e.grantedTraits.sorted().joinToString(", ") { t -> t + if (t in traits) "" else " (unknown)" }
}

private fun World.traitsLine(e: Actor): String? {
  if (e.traits.isEmpty()) return null
  return "Traits: " +
    e.traits.joinToString(", ") { t -> "${t.name} (${traitSources(e, t.name).joinToString()})" }
}

private fun World.traitSources(e: Actor, trait: String): List<String> = buildList {
  when (e) {
    is Player -> addAll(choiceSources(e, trait))
    is Mob -> if (trait in e.proto.traits) add("prototype")
  }
  if (trait in e.grantedTraits) add("granted")
}

private fun World.choiceSources(e: Player, trait: String): List<String> =
  creation?.choices.orEmpty().mapNotNull { choice ->
    val opt = choice.options.firstOrNull { it.id == e.choices[choice.id] }
    if (opt?.traits?.contains(trait) == true) "${choice.id}=${opt.id}" else null
  }

private fun skillsLine(e: Actor): String? =
  if (e.skills.isEmpty()) null
  else "Skills: " + e.skills.entries.joinToString(", ") { "${it.key} ${it.value}" }

private fun World.affectsLine(e: Actor): String =
  "Affects: " +
    if (e.affects.isEmpty()) "none"
    else
      e.affects.joinToString(", ") { a ->
        val timing =
          when {
            a.wornFrom != null -> "while worn"
            a.expiresAt == Long.MAX_VALUE -> "permanent"
            else -> "${pulsesToSeconds((a.expiresAt - pulse).coerceAtLeast(0))}s left"
          }
        "${a.name} ($timing${if (a.flags.isEmpty()) "" else "; ${a.flags.joinToString()}"})"
      }

private fun World.doingLine(e: Actor): String? {
  val doing =
    listOfNotNull(
      e.activity?.let { a ->
        taskInfoOf(a)?.let { "task ${it.name} until pulse ${a.endsAt}" }
          ?: "casting until pulse ${a.endsAt}"
      },
      e.waitState.takeIf { it > 0 }?.let { "recovering $it pulses" },
      e.actionQueue.takeIf { it.isNotEmpty() }?.let { q -> "queued: " + q.joinToString("; ") },
      e.fighting
        .takeIf { it.isNotEmpty() }
        ?.let { f -> "fighting " + f.joinToString(", ") { actor(it)?.name ?: "#${it.v}" } },
    )
  return if (doing.isEmpty()) null else "Doing: " + doing.joinToString("; ")
}

private fun carryingLine(e: Actor): String? =
  if (e.inventory.isEmpty()) null
  else "Carrying: " + e.inventory.joinToString(", ") { "${it.name} #${it.id.v}" }

private fun wearingLine(e: Actor): String? =
  if (e.equipment.isEmpty()) null
  else
    "Wearing: " +
      e.equipment.entries.joinToString(", ") { "${it.key}: ${it.value.name} #${it.value.id.v}" }

private fun World.debugLine(e: Actor): String? {
  if (e !is Mob || !e.debug) return null
  val owner = e.debugOwner?.let(::actor)?.name ?: "someone gone"
  return "Debug: spawned by $owner, despawns in ${((e.debugUntil ?: pulse) - pulse) * PULSE_MS / 1000}s"
}

private fun accountLine(e: Actor): String? {
  if (e !is Player) return null
  return "Account: ${e.accountId ?: "none"}, role ${e.role.name.lowercase()}" +
    if (e.role == Role.BUILDER) ", packages ${e.scope.sorted().joinToString().ifEmpty { "none" }}"
    else ""
}

// ---- Items and rooms ---------------------------------------------------------------------

private fun World.itemLines(e: Item): List<String> =
  listOfNotNull(
    "Location: ${describeLocation(e.location)}",
    "Count ${e.count}, weight ${e.weight}" + (e.decayAt?.let { ", decays at pulse $it" } ?: ""),
    if (e.tags.isEmpty()) null else "Tags: " + e.tags.sorted().joinToString(", "),
    itemAffectsLine(e),
    tagChangesLine(e),
    if (e.contents.isEmpty()) null
    else "Contains: " + e.contents.joinToString(", ") { "${it.name} #${it.id.v}" },
  )

private fun World.itemAffectsLine(e: Item): String {
  val held = e.adminAffectsList()
  return "Affects: " +
    if (held.isEmpty()) "none"
    else
      held.joinToString(", ") { ha ->
        val timing =
          when {
            ha.instance == null -> "prototype"
            ha.instance.expiresAt == Long.MAX_VALUE -> "permanent"
            else -> "${pulsesToSeconds((ha.instance.expiresAt - pulse).coerceAtLeast(0))}s left"
          }
        val flags = if (ha.flags.isEmpty()) "" else "; " + ha.flags.sorted().joinToString()
        "${ha.name} (${ha.applies.name.lowercase()}, $timing$flags)"
      }
}

private fun tagChangesLine(e: Item): String? {
  val changes = e.addedTags.map { "+$it" } + e.removedTags.map { "-$it" }
  return if (changes.isEmpty()) null else "Tag changes: " + changes.joinToString(", ")
}

private fun World.roomLines(e: Room): List<String> {
  val tags = e.tagsAt(roomClock)
  return listOfNotNull(
    "Size ${e.trackSize}; exits: " +
      e.exits.values
        .joinToString(", ") { x ->
          x.direction.name +
            (x.door?.let { " (${it.state.name.lowercase()})" } ?: "") +
            " -> ${x.to}"
        }
        .ifEmpty { "none" },
    if (tags.isEmpty()) null else "Tags: " + tags.sorted().joinToString(", "),
    "Here: ${e.actors.size} actors, ${e.items.size} items on the ground",
  )
}

// ---- Every entity ------------------------------------------------------------------------

private fun World.timersLine(e: Entity): String =
  "Timers: " +
    if (e.timers.isEmpty()) "none"
    else
      e.timers.joinToString(", ") { t ->
        "${t.fn.file}:${t.fn.name} " +
          (t.due?.let { "in ${it - pulse} pulses" } ?: "paused, ${t.remaining} left")
      }

private fun attributeLines(e: Entity): List<String> {
  val attrs = e.customAttributes.entries.groupBy { it.key.substringBefore(':', "") }
  if (attrs.isEmpty()) return listOf("Attributes: none")
  return attrs.toSortedMap().map { (ns, entries) ->
    "Attributes ${ns.ifEmpty { "(none)" }}: " +
      entries.joinToString(", ") { "${it.key.substringAfter(':')}=${render(it.value)}" }
  }
}

private fun World.simulationLine(e: Entity): String {
  val room = roomOfEntity(e)
  return "Simulation: " +
    (if (isSimulated(e)) "simulated" else "dormant") +
    (if (e.activeReasons.isNotEmpty()) "; active: ${e.activeReasons.joinToString()}" else "") +
    (if (e.anchorReasons.isNotEmpty()) "; anchor: ${e.anchorReasons.joinToString()}" else "") +
    (room?.dormantSince?.let { "; ${room.label} dormant since pulse $it" } ?: "")
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
