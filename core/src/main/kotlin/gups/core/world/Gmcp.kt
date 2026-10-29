package gups.core.world

import gups.core.PULSE_MS
import gups.core.model.Band
import gups.core.model.DoorState
import gups.core.model.Player

// GMCP payloads. `core` builds them as plain values (String,
// Long/Int, Boolean, List, Map, null) and the listener encodes them. Field
// names are lowercase.

/** Packages recomputed every phase 7 and sent only when they changed. */
private val STATE_PACKAGES =
  listOf(
    "Char.Schema",
    "Char.Vitals",
    "Char.Status",
    "Char.Affects",
    "Char.Traits",
    "Room.Info",
    "Room.Bands",
  )

/** Every package the engine sends; plugin packages may not overlap them. */
val ENGINE_GMCP_PACKAGES: Set<String> =
  (STATE_PACKAGES + listOf("Comm.Channel.Text", "Gups.Trace", "Char.Creation")).toSortedSet()

/**
 * Queues the GMCP event [pkg] for [p], to go out with the session's next flush after its text.
 * Returns false, sending nothing, when the client doesn't support [pkg].
 */
fun World.gmcp(p: Player, pkg: String, data: Any?): Boolean {
  val s = p.session?.let(sessions::get) ?: return false
  if (!s.sink.wantsGmcp(pkg)) return false
  s.gmcpOut += pkg to data
  return true
}

/** `Comm.Channel.Text` to each of [to] that is a player. */
internal fun World.channel(
  channel: String,
  talker: gups.core.model.Actor,
  text: String,
  to: Collection<gups.core.model.Actor>,
) {
  val data = mapOf("channel" to channel, "talker" to talker.name, "text" to text)
  for (a in to) if (a is Player) gmcp(a, "Comm.Channel.Text", data)
}

/**
 * Phase 7's GMCP for one session: each state package the client supports, when its payload differs
 * from the last one this session was sent. The per-session cache is what makes the messages deltas.
 */
internal fun World.gmcpDeltas(s: Session) {
  val p = s.player ?: return
  for (pkg in STATE_PACKAGES) {
    if (pkg == "Room.Bands" && Feature.TRACK !in features) continue
    if (pkg == "Char.Traits" && creation == null && traits.isEmpty()) continue
    if (!s.sink.wantsGmcp(pkg)) continue
    val data = gmcpPayload(p, pkg)
    if (s.gmcpLast.containsKey(pkg) && s.gmcpLast[pkg] == data) continue
    s.gmcpLast[pkg] = data
    s.gmcpOut += pkg to data
  }
  for ((pkg, produce) in pluginGmcp) {
    if (!s.sink.wantsGmcp(pkg)) continue
    val data =
      runCatching { produce(p) }
        .getOrElse { e ->
          record(Diagnostic(pulse, "plugin", "GMCP producer $pkg failed: $e", e))
          continue
        }
    if (s.gmcpLast.containsKey(pkg) && s.gmcpLast[pkg] == data) continue
    s.gmcpLast[pkg] = data
    s.gmcpOut += pkg to data
  }
}

/** The current value of state package [pkg] for [p]. */
fun World.gmcpPayload(p: Player, pkg: String): Any? =
  when (pkg) {
    "Char.Schema" ->
      mapOf(
        "stats" to schema.stats.map { mapOf("name" to it.name, "label" to it.label) },
        "vitals" to schema.vitals.map { mapOf("name" to it.name, "label" to it.label) },
        "damage_types" to schema.damageTypes,
        "features" to features.ids,
      )
    "Char.Vitals" ->
      LinkedHashMap<String, Any?>().also { out ->
        for (name in schema.vitalNames) {
          out[name] = p.current(name)
          out["max$name"] = p.max(name)
        }
      }
    "Char.Status" ->
      LinkedHashMap<String, Any?>().also { out ->
        out["name"] = p.name
        if (Feature.LEVELING in features) {
          out["level"] = p.level
          out["xp"] = p.xp
          out["xp_next"] = rules.xpToLevel(p.level)
        }
        out["wait"] = p.waitState
        if (Feature.SKILLS in features)
          out["casting"] =
            p.casting?.let { c ->
              val total = (c.endsAt - c.startedAt).coerceAtLeast(1)
              mapOf(
                "skill" to ((c.action as? Castable)?.skill?.name ?: "action"),
                "progress" to ((pulse - c.startedAt) * 100 / total).coerceIn(0, 100),
              )
            }
        if (Feature.COMBAT in features) {
          out["initiative"] = p.initiative
          out["fighting"] = p.fighting.mapNotNull { actor(it)?.name }
        }
      }
    "Char.Affects" ->
      p.affects.map { a ->
        mapOf(
          "name" to a.name,
          "remaining" to
            if (a.wornFrom != null) null
            else (a.expiresAt - pulse).coerceAtLeast(0) * PULSE_MS / 1000,
          "flags" to a.flags.sorted(),
        )
      }
    "Char.Traits" -> {
      val choicesList =
        if (creation == null) emptyList()
        else
          creation!!.choices.mapNotNull { choice ->
            val optId = p.choices[choice.id] ?: return@mapNotNull null
            val opt = choice.options.firstOrNull { it.id == optId } ?: return@mapNotNull null
            mapOf(
              "choice" to choice.id,
              "title" to choice.title,
              "option" to opt.id,
              "label" to opt.label,
            )
          }
      val traitsList =
        p.traits.map { t -> mapOf("name" to t.name, "label" to t.label, "doc" to t.doc) }
      mapOf("choices" to choicesList, "traits" to traitsList)
    }
    "Room.Info" -> {
      val room = p.room
      mapOf(
        "label" to room.label.v,
        "name" to room.name,
        "exits" to
          room.exits.values.associate { x -> x.direction.name to x.door?.state?.let(::doorWord) },
        "regions" to room.regions.sorted(),
      )
    }
    "Room.Bands" -> {
      // Only the viewer's own room, from the viewer's own index.
      val bands = Band.entries.associateWith { mutableListOf<Map<String, Any?>>() }
      for (a in p.room.actors) {
        if (a === p) continue
        bands.getValue(Band.between(p.trackIndex, a.trackIndex)) +=
          mapOf("id" to a.id.v, "name" to a.name, "kind" to "actor")
      }
      for (item in p.room.items) {
        bands.getValue(Band.between(p.trackIndex, item.groundIndex ?: 0)) +=
          mapOf("id" to item.id.v, "name" to item.name, "kind" to "item")
      }
      bands.entries.associate { (band, list) -> band.name.lowercase() to list.toList() }
    }
    else -> null
  }

private fun doorWord(state: DoorState): String =
  when (state) {
    DoorState.OPEN -> "open"
    DoorState.CLOSED -> "closed"
    DoorState.LOCKED -> "locked"
  }
