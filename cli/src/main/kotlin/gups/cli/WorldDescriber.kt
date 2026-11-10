package gups.cli

import gups.core.EngineInfo
import gups.core.Label
import gups.core.model.ExitProto
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.SkillProto
import gups.core.model.WorldSchema
import gups.pluginapi.host.PluginInfo
import gups.script.LoadError
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.WorldDir
import gups.script.WorldModule
import gups.script.WorldPluginWiring
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `format` of the JSON [describeWorld] writes. It is the version of the document's shape,
 * separate from the script `api_version`: it changes only when a field is removed or changes
 * meaning, never when one is added.
 */
const val DESCRIBE_WORLD_FORMAT = 1

private val PRETTY = Json { prettyPrint = true }

/**
 * Loads [dir] with [plugins] and describes it as the `gups describe-world` JSON document. The
 * plugins' section comes from [describe], kept apart from [plugins] so callers without plugins can
 * pass none. Returns null and adds to [problems] when MODULE.gups can't be read or has problems; a
 * package that fails to load never makes it return null, it is listed with its errors instead.
 */
fun describeWorld(
  dir: WorldDir,
  plugins: WorldPluginWiring,
  describe: List<PluginInfo>,
  problems: MutableList<String>,
): String? {
  val (module, readError) = WorldModule.read(dir)
  readError?.let { problems += it.message }
  problems += module.problems()
  if (problems.isNotEmpty()) return null

  val paths = dir.packages()
  val result = ScriptLoader(dir, ScriptLimits(), plugins = plugins).load(paths)
  val protos = result.prototypes()

  val doc = buildJsonObject {
    put("format", DESCRIBE_WORLD_FORMAT)
    put("api_version", module.apiVersion)
    put("engine_version", EngineInfo.version)
    put("schema", schemaJson(module.schema))
    put("features", strings(module.features.ids.sorted()))
    put("commands", sortedMap(module.rename) { JsonPrimitive(it) })
    put(
      "packages",
      JsonArray(
        paths.sorted().map { path ->
          val loaded = result.packages[path]
          buildJsonObject {
            put("path", path)
            put("kind", loaded?.kind?.word)
            put("ok", loaded != null)
          }
        }
      ),
    )
    put("rooms", sortedByLabel(protos.rooms.values, { it.label }, ::roomJson))
    put("mobs", sortedByLabel(protos.mobs.values, { it.label }, ::mobJson))
    put(
      "items",
      sortedByLabel(
        protos.items.values.filter { it.label != Prototypes.CORPSE.label },
        { it.label },
        ::itemJson,
      ),
    )
    put("skills", sortedByLabel(protos.skills.values, { it.label }, ::skillJson))
    put("factions", strings(protos.factions.keys.map { it.v }.sorted()))
    put("regions", strings(protos.regions.keys.sorted()))
    put("plugins", JsonArray(describe.sortedBy { it.id }.map(::pluginJson)))
    put("errors", JsonArray(result.errors.map(::errorJson)))
  }
  return PRETTY.encodeToString(JsonElement.serializer(), doc) + "\n"
}

private fun strings(values: Iterable<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

private fun intMap(values: Map<String, Int>): JsonObject = sortedMap(values) { JsonPrimitive(it) }

private fun <V> sortedMap(values: Map<String, V>, value: (V) -> JsonElement): JsonObject =
  JsonObject(values.entries.sortedBy { it.key }.associate { it.key to value(it.value) })

private fun <T> sortedByLabel(
  protos: Collection<T>,
  label: (T) -> Label,
  json: (T) -> JsonObject,
): JsonArray = JsonArray(protos.sortedBy { label(it).v }.map(json))

private fun schemaJson(schema: WorldSchema): JsonObject = buildJsonObject {
  put(
    "stats",
    JsonArray(
      schema.stats.map {
        buildJsonObject {
          put("name", it.name)
          put("label", it.label)
        }
      }
    ),
  )
  put(
    "vitals",
    JsonArray(
      schema.vitals.map {
        buildJsonObject {
          put("name", it.name)
          put("label", it.label)
        }
      }
    ),
  )
  put(
    "damage_types",
    JsonArray(
      schema.damageTypes.map {
        buildJsonObject {
          put("name", it)
          put("label", schema.damageTypeLabel(it))
          put("adjective", schema.damageTypeAdjective(it))
        }
      }
    ),
  )
  put("unarmed_damage_type", schema.unarmedDamageType)
  put("channels", strings(schema.allChannels.map { it.name }.distinct().sorted()))
}

private fun roomJson(room: RoomProto): JsonObject = buildJsonObject {
  put("label", room.label.v)
  put("name", room.name)
  put("size", room.size.name.lowercase())
  put("regions", strings(room.regions.sorted()))
  put("tags", strings(room.tags.sorted()))
  put("flags", strings(room.flags.sorted()))
  put("exits", JsonArray(room.exits.map(::exitJson)))
}

private fun exitJson(exit: ExitProto): JsonObject = buildJsonObject {
  put("dir", exit.direction.name)
  put("to", exit.to.v)
  put("one_way", exit.oneWay)
  put("tags", strings(exit.tags.sorted()))
  val door = exit.door
  put(
    "door",
    if (door == null) JsonNull
    else
      buildJsonObject {
        put("state", door.state.name.lowercase())
        put("key", door.key?.v)
        put("tags", strings(door.tags.sorted()))
      },
  )
}

private fun mobJson(mob: MobProto): JsonObject = buildJsonObject {
  put("label", mob.label.v)
  put("name", mob.name)
  put("keywords", strings(mob.keywords))
  put("level", mob.level)
  put("stats", intMap(mob.stats))
  put("vitals", intMap(mob.vitals))
  put("resists", intMap(mob.resists.mapKeys { it.key.name }))
  put("skills", intMap(mob.skills.mapKeys { it.key.v }))
  put("traits", strings(mob.traits.sorted()))
  put("factions", mob.factions?.let { list -> strings(list.map { it.v }) } ?: JsonNull)
}

private fun itemJson(item: ItemProto): JsonObject = buildJsonObject {
  put("label", item.label.v)
  put("name", item.name)
  put("keywords", strings(item.keywords))
  put("kind", item.kind)
  put("wear", item.wear)
  put("weight", item.weight)
  put("value", item.value)
}

private fun skillJson(skill: SkillProto): JsonObject = buildJsonObject {
  put("label", skill.label.v)
  put("kind", skill.kind)
  put("target", skill.target.name.lowercase())
  put("range", skill.range.name.lowercase())
  put("cost", intMap(skill.cost))
  put("warmup", skill.warmup)
  put("lag", skill.lag)
  put("channels", strings(skill.channels.map { it.name }.sorted()))
  put("tags", strings(skill.tags.sorted()))
}

private fun pluginJson(plugin: PluginInfo): JsonObject = buildJsonObject {
  put("id", plugin.id)
  put("builtins", strings(plugin.builtins.map { it.name }.sorted()))
  put("effects", strings(plugin.effects.map { it.name }.sorted()))
  put("traits", strings(plugin.traits.map { it.name }.sorted()))
}

private fun errorJson(error: LoadError): JsonObject = buildJsonObject {
  put("package", error.pkg)
  put("file", error.file)
  put("line", error.line)
  put("message", error.message)
}
