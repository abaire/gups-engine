package gups.script

import com.google.common.collect.ImmutableMap
import gups.core.EngineInfo
import gups.core.Label
import gups.core.PULSE_MS
import gups.core.model.DamageTypeInfo
import gups.core.model.SchemaName
import gups.core.model.WorldSchema
import gups.core.world.CommandAliases
import gups.core.world.CommandRegistry
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.LevelMode
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Module
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.eval.Tuple
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.SyntaxError

/**
 * The world's metadata and configuration declared by `module(...)` in `MODULE.gups` at the world
 * root.
 *
 * This configuration is parsed only at startup; hot reload and deployment swaps never re-read
 * `MODULE.gups`, so any changes require a full server restart.
 *
 * ## Settings
 * - `engine`: Optional engine version string the world was written for.
 * - `api_version`: Pinned script API version (defaults to [EngineInfo.API_VERSION]). Must match the
 *   running engine's supported API version; mismatched versions prevent startup.
 * - `checkpoint_minutes`: Minutes between routine periodic database checkpoints (default 30
 *   minutes).
 * - `start_room`: Target room label where newly created characters spawn (or `null` to use the
 *   tutorial gate or first room).
 * - `plugins`: Map of plugin IDs to `sha256:<hex>` checksums of `plugins/<id>.jar`.
 * - `features`: List of engine feature module IDs to enable (`combat`, `track`, `skills`,
 *   `leveling`). If omitted, all features are enabled by default.
 * - `stats`, `vitals`, `damage_types`, `unarmed_damage_type`, `channels`, `prompt`: The world's
 *   schema. The declared names are recorded in the database on first run and cannot be altered
 *   without a `--wipe`.
 * - `aliases`: Extra words for engine commands, such as `{"l": "look"}`. Several aliases may name
 *   the same command.
 * - `prefixes`: Single characters that stand for a command at the start of a line, such as `{"'":
 *   "say"}`.
 * - `rename`: Engine command renames, mapping canonical names to custom alternatives.
 * - `level_mode`: How earned levels apply: `"auto"` (the default) applies a level as soon as the
 *   actor has the XP; `"deferred"` only adds XP and waits for `ctx.level_up`. Needs the `leveling`
 *   feature.
 *
 * ## Validation Rules
 * - **API version:** Pinned `api_version` must equal [EngineInfo.API_VERSION].
 * - **Plugin IDs:** Must match `[a-z][a-z0-9_]*`, must not collide with reserved built-in keywords,
 *   and must have a checksum formatted as `sha256:` followed by 64 lowercase hexadecimal digits.
 * - **Features:** Every ID must be a known feature, and each feature's requirements must also be
 *   on: `track` requires `combat`; `combat`, `skills` and `leveling` require nothing.
 * - **Commands and aliases:** Command renames must not form cycles or collide with other command
 *   names. An alias must match `[a-z][a-z0-9_]*`, must not be an engine command or a standard
 *   direction, and must name a non-`@` engine command. A prefix must be one character other than a
 *   letter, digit, space or `@`, and must name such a command. Names are those after `rename`, and
 *   naming a command of a disabled feature is an error.
 * - **No built-in aliases:** The engine hardcodes no aliases or prefixes; the template world's
 *   `MODULE.gups` ships the usual defaults.
 * - **Schema integrity:** Stat, vital, damage type, and sensory channel names must be valid
 *   identifiers, distinct across categories, and meet minimum required declarations.
 */
data class WorldModule(
  /** The engine version the world was written for, if pinned. */
  val engine: String? = null,
  val apiVersion: Int = EngineInfo.API_VERSION,
  /** Minutes between routine checkpoints. */
  val checkpointMinutes: Int = DEFAULT_CHECKPOINT_MINUTES,
  /** Where new characters start; null means the tutorial gate, or else the first room. */
  val startRoom: Label? = null,
  /** Plugin ids → `sha256:<hex>` checksums of `plugins/<id>.jar`. */
  val plugins: Map<String, String> = emptyMap(),
  /** Shorthands for engine commands, from `aliases` and `prefixes`. */
  val aliases: CommandAliases = CommandAliases.NONE,
  /** The names the world's actors are measured by. */
  val schema: WorldSchema = WorldSchema.EMPTY,
  /** The feature ids as written; null when `features` is left out, which turns all of them on. */
  val featureIds: List<String>? = null,
  /** Engine command renames, old name to new. */
  val rename: Map<String, String> = emptyMap(),
  /**
   * How earned levels apply: automatically upon eligibility or deferred until explicitly advanced.
   */
  val levelMode: LevelMode = LevelMode.AUTO,
  /** How long the engine keeps the history of standing changes, in pulses. */
  val standingHistoryPulses: Long = DEFAULT_STANDING_HISTORY_PULSES,
  /** How long a mob keeps a combat memory after it last happened, in pulses; 0 keeps none. */
  val memoryPulses: Long = 0,
) {
  /** The features the world has on. Unknown ids are left out here and reported by [problems]. */
  val features: Features = featuresOf(featureIds)

  /** The world's non-`@` engine command names, renames applied. */
  val commandNames: Set<String>
    get() = CommandRegistry.names(features, rename)

  /** [base] with this world's aliases, features, renames and message overrides. */
  fun config(
    base: gups.core.world.WorldConfig = gups.core.world.WorldConfig(),
    messages: WorldMessages = WorldMessages(),
  ): gups.core.world.WorldConfig =
    base.copy(aliases = aliases, features = features, rename = rename, messages = messages.texts)

  /** The checkpoint interval in pulses. */
  val checkpointPulses: Long
    get() = checkpointMinutes * 60_000L / PULSE_MS

  /** Problems that should stop the server, such as a pinned `api_version` this engine lacks. */
  fun problems(): List<String> = buildList {
    if (apiVersion != EngineInfo.API_VERSION) {
      add(
        "MODULE.gups pins api_version $apiVersion, but this engine supports only api_version ${EngineInfo.API_VERSION}. See CHANGELOG.md."
      )
    }
    for ((id, checksum) in plugins.toSortedMap()) {
      when {
        !PLUGIN_ID.matches(id) -> add("MODULE.gups: plugin id '$id' must match [a-z][a-z0-9_]*")
        id in reservedPluginIds -> add("MODULE.gups: plugin id '$id' is a reserved name")
      }
      if (!CHECKSUM.matches(checksum))
        add(
          "MODULE.gups: plugin '$id' checksum must be 'sha256:' and 64 lowercase hex digits, not '$checksum'"
        )
    }
    for (id in featureIds.orEmpty().distinct()) {
      if (Feature.byId(id) == null)
        add(
          "MODULE.gups: unknown feature '$id'" + schema.suggest(id, Feature.entries.map { it.id })
        )
    }
    features.problems().forEach { add("MODULE.gups: $it") }
    CommandRegistry.renameProblems(features, rename, aliases).forEach { add("MODULE.gups: $it") }
    aliases.problems(commandNames).forEach { add("MODULE.gups: $it") }
    addAll(schemaProblems(schema))
  }

  /** Things worth a warning, such as a different pinned engine version. */
  fun warnings(): List<String> = buildList {
    if (engine != null && engine != EngineInfo.version)
      add("MODULE.gups pins engine $engine; this is engine ${EngineInfo.version}.")
  }

  companion object {
    const val FILE = "MODULE.gups"
    const val DEFAULT_CHECKPOINT_MINUTES = 30
    const val DEFAULT_STANDING_HISTORY_PULSES = 14_400L
    val PLUGIN_ID = Regex("[a-z][a-z0-9_]*")
    val CHECKSUM = Regex("sha256:[0-9a-f]{64}")

    /**
     * The features named by [ids], or every feature when [ids] is null; unknown ids are dropped.
     */
    private fun featuresOf(ids: List<String>?): Features =
      ids?.let { Features(it.mapNotNull(Feature::byId).toSet()) } ?: Features.ALL

    /** Every field name of the actor view, which no stat or vital may take. */
    val reservedViewNames: Set<String> by lazy {
      Starlark.getMethodAnnotations(ActorView::class.java).values.mapTo(HashSet()) { it.name } +
        setOf("base", "resists", "condition", "below_zero_since")
    }

    val STAT_SPEC =
      DeclSpec(
        name = "stat",
        doc = "Declares a stat with a label, for MODULE.gups.",
        params =
          listOf(
            DeclParam("name", "str", "The stat's name.", positional = true, required = true),
            DeclParam("label", "str", "The name players see.", default = "name capitalized"),
          ),
      )

    val VITAL_SPEC =
      DeclSpec(
        name = "vital",
        doc = "Declares a vital with a label, for MODULE.gups.",
        params =
          listOf(
            DeclParam("name", "str", "The vital's name.", positional = true, required = true),
            DeclParam("label", "str", "The name players see.", default = "name capitalized"),
          ),
      )

    val DAMAGE_TYPE_SPEC =
      DeclSpec(
        name = "damage_type",
        doc = "Declares a damage type with a label and an adjective, for MODULE.gups.",
        params =
          listOf(
            DeclParam("name", "str", "The damage type's name.", positional = true, required = true),
            DeclParam("label", "str", "The name players see.", default = "name capitalized"),
            DeclParam(
              "adjective",
              "str",
              "How a hit of this type is described in anonymous lines; a non-empty single line.",
              default = "None",
            ),
          ),
      )

    /** Names a plugin id may not take. */
    val reservedPluginIds: Set<String> by lazy {
      PACKAGE_GLOBALS.keys +
        Starlark.UNIVERSE.keys +
        setOf("module", "server", "ctx", "self", "room", "rules_ctx", "t", "w", "p", "diagnostic")
    }

    val SPEC =
      DeclSpec(
        name = "module",
        doc = "Declares the world's metadata in MODULE.gups.",
        params =
          listOf(
            DeclParam(
              "engine",
              "str",
              "Engine version the world was written for.",
              default = "None",
            ),
            DeclParam(
              "api_version",
              "int",
              "Script API version the world pins.",
              default = "${EngineInfo.API_VERSION}",
            ),
            DeclParam(
              "checkpoint_minutes",
              "int",
              "Minutes between routine database checkpoints.",
              default = "$DEFAULT_CHECKPOINT_MINUTES",
            ),
            DeclParam(
              "start_room",
              "label",
              "Room label where new characters start.",
              default = "None",
            ),
            DeclParam(
              "stats",
              "list[str | stat]",
              "The stats actors have, each a name or stat(name, label).",
              required = true,
            ),
            DeclParam(
              "vitals",
              "list[str | vital]",
              "The vitals actors have, each a name or vital(name, label).",
              required = true,
            ),
            DeclParam(
              "damage_types",
              "list[str | damage_type]",
              "The damage types of the world, each a name or damage_type(name, label, adjective).",
              required = true,
            ),
            DeclParam(
              "unarmed_damage_type",
              "str",
              "The damage type of an unarmed auto-attack; one of damage_types.",
              required = true,
            ),
            DeclParam(
              "channels",
              "list[str]",
              "Perception channels beyond sight and sound.",
              default = "[]",
            ),
            DeclParam(
              "channel_priority",
              "list[str]",
              "Channels from strongest to weakest: a player is told the text of the first one they perceive. Channels left out follow, built-in ones first.",
              default = "[]",
            ),
            DeclParam(
              "prompt",
              "str",
              "The telnet prompt, with {vital} and {max_vital} placeholders.",
              default = "'<' + '{v}v ' per vital + '> '",
            ),
            DeclParam(
              "plugins",
              "dict[str, str]",
              "Plugin jars in plugins/<id>.jar, each pinned by its 'sha256:<hex>' checksum.",
              default = "{}",
            ),
            DeclParam(
              "aliases",
              "dict[str, str]",
              "Alias words for engine commands, such as {\"l\": \"look\"}.",
              default = "{}",
            ),
            DeclParam(
              "prefixes",
              "dict[str, str]",
              "Single characters that stand for an engine command, such as {\"'\": \"say\"}.",
              default = "{}",
            ),
            DeclParam(
              "features",
              "list[str]",
              "The engine features the world uses: combat, track, skills, leveling and standing. Fixed per database; changing it needs --wipe.",
              default = "['combat', 'track', 'skills', 'leveling', 'standing']",
            ),
            DeclParam(
              "rename",
              "dict[str, str]",
              "Renames engine commands, such as {\"cast\": \"invoke\"}. Aliases, prefixes and docs use the new names.",
              default = "{}",
            ),
            DeclParam(
              "level_mode",
              "str",
              "How earned levels apply: 'auto' (immediately) or 'deferred' (waiting for ctx.level_up). Not fixed per database; changing it needs only a restart.",
              default = "'auto'",
              allowedValues = listOf("auto", "deferred"),
            ),
            DeclParam(
              "standing_history_pulses",
              "int",
              "How long the engine keeps the history of standing changes, in pulses; at least 1. Not fixed per database; changing it needs only a restart.",
              default = "$DEFAULT_STANDING_HISTORY_PULSES",
            ),
            DeclParam(
              "memory_pulses",
              "int",
              "How long a mob keeps a combat memory after it last happened, in pulses; 0 keeps none. Not fixed per database; changing it needs only a restart.",
              default = "0",
            ),
          ),
      )

    /** Reads `MODULE.gups`, which every world needs. Errors are [LoadError]s. */
    fun read(dir: WorldDir): Pair<WorldModule, LoadError?> {
      val path = dir.root.resolve(FILE)
      if (!path.isRegularFile())
        return WorldModule() to LoadError("", "module", "$FILE is required at the world root", FILE)
      val reader = Reader()
      execSettings(path, FILE, "module", reader)?.let {
        return WorldModule() to it
      }
      val m =
        reader.result
          ?: return WorldModule() to LoadError("", "module", "$FILE must call module(...)", FILE)
      return m to null
    }
  }

  /** A `stat(...)` or `vital(...)` value inside the `stats` and `vitals` lists. */
  private class NameDecl(val name: String, val label: String?) : StarlarkValue

  /** A `damage_type(...)` value inside the `damage_types` list. */
  private class DamageTypeDecl(val name: String, val label: String?, val adjective: String?) :
    StarlarkValue

  /** The `module()` builtin: called exactly once. */
  private class Reader {
    var result: WorldModule? = null

    @StarlarkMethod(
      name = "stat",
      doc = "Declares a stat with a label.",
      extraPositionals = Param(name = "args"),
      extraKeywords = Param(name = "kwargs"),
    )
    fun stat(args: Tuple, kwargs: Dict<String, Any>): Any = named(STAT_SPEC, args, kwargs)

    @StarlarkMethod(
      name = "vital",
      doc = "Declares a vital with a label.",
      extraPositionals = Param(name = "args"),
      extraKeywords = Param(name = "kwargs"),
    )
    fun vital(args: Tuple, kwargs: Dict<String, Any>): Any = named(VITAL_SPEC, args, kwargs)

    @StarlarkMethod(
      name = "damage_type",
      doc = "Declares a damage type with a label and an adjective.",
      extraPositionals = Param(name = "args"),
      extraKeywords = Param(name = "kwargs"),
    )
    fun damageType(args: Tuple, kwargs: Dict<String, Any>): Any =
      DAMAGE_TYPE_SPEC.bind(args, kwargs) { d ->
        val adjective = d.str("adjective")
        if (adjective != null && (adjective.isEmpty() || adjective.lineSequence().count() > 1))
          fail("damage_type(adjective): must be a non-empty single line")
        DamageTypeDecl(d.reqStr("name"), d.str("label"), adjective)
      }

    private fun named(spec: DeclSpec, args: Tuple, kwargs: Dict<String, Any>): Any =
      spec.bind(args, kwargs) { d -> NameDecl(d.reqStr("name"), d.str("label")) }

    private fun names(d: ParsedDecl, param: String): List<SchemaName> =
      d.list(param).map {
        when (it) {
          is NameDecl -> SchemaName(it.name, it.label ?: label(it.name))
          is String -> SchemaName(it, label(it))
          else ->
            fail(
              "module($param): expected a name or ${param.removeSuffix("s")}(...), got ${typeName(it)}"
            )
        }
      }

    private fun damageTypes(d: ParsedDecl): List<DamageTypeInfo> =
      d.list("damage_types").map {
        when (it) {
          is DamageTypeDecl -> DamageTypeInfo(it.name, it.label ?: label(it.name), it.adjective)
          is String -> DamageTypeInfo(it, label(it))
          else ->
            fail("module(damage_types): expected a name or damage_type(...), got ${typeName(it)}")
        }
      }

    private fun label(name: String): String = name.replaceFirstChar { it.uppercase() }

    private fun startRoom(d: ParsedDecl): Label? =
      d.str("start_room")?.let { text ->
        runCatching { Label(text) }
          .getOrElse {
            fail("module(start_room): '$text' is not a label such as //zones/tutorial:gate")
          }
      }

    private fun stringMap(d: ParsedDecl, param: String, what: String): Map<String, String> =
      d.dict(param).mapValues { (key, v) ->
        v as? String ?: fail("module($param): the $what of '$key' must be a string")
      }

    private fun prefixes(d: ParsedDecl): Map<Char, String> =
      stringMap(d, "prefixes", "command").entries.associate { (prefix, command) ->
        if (prefix.length != 1) fail("module(prefixes): '$prefix' must be a single character")
        prefix[0] to command
      }

    /** Fails when [param] is set although the feature that owns [key] is off. */
    private fun requireFeature(d: ParsedDecl, param: String, key: String, features: Features) {
      if (d[param] == null) return
      FeatureOwnership.refused(key, "module($param)", features)?.let { fail(it) }
    }

    private fun schema(d: ParsedDecl): WorldSchema {
      val types = damageTypes(d)
      return WorldSchema(
        stats = names(d, "stats"),
        vitals = names(d, "vitals"),
        damageTypes = types.map { it.name },
        damageTypeInfo = types.associateBy { it.name },
        unarmedDamageType = d.reqStr("unarmed_damage_type"),
        channels = d.strList("channels"),
        prompt = d.str("prompt"),
        channelPriority = d.strList("channel_priority"),
      )
    }

    @StarlarkMethod(
      name = "module",
      doc = "Declares the world's metadata.",
      extraKeywords = Param(name = "kwargs"),
    )
    fun module(kwargs: Dict<String, Any>): Any {
      if (result != null) fail("module() is called more than once")
      return SPEC.bind(kwargs = kwargs) { d ->
        result = build(d)
        Starlark.NONE
      }
    }

    private fun build(d: ParsedDecl): WorldModule {
      val minutes = d.int("checkpoint_minutes") ?: DEFAULT_CHECKPOINT_MINUTES
      if (minutes < 1) fail("module(checkpoint_minutes): must be at least 1")
      // Listing all four features is the same as leaving the line out.
      val featureIds =
        d["features"]
          ?.let { d.strList("features") }
          ?.takeUnless { ids -> ids.toSet() == Feature.entries.map { it.id }.toSet() }
      val features = featuresOf(featureIds)
      requireFeature(d, "level_mode", "module.level_mode", features)
      requireFeature(d, "standing_history_pulses", "module.standing_history_pulses", features)
      requireFeature(d, "memory_pulses", "module.memory_pulses", features)
      val standingHistoryPulses =
        d.int("standing_history_pulses")?.toLong() ?: DEFAULT_STANDING_HISTORY_PULSES
      if (standingHistoryPulses < 1) fail("module(standing_history_pulses): must be at least 1")
      val memoryPulses = d.int("memory_pulses")?.toLong() ?: 0L
      if (memoryPulses < 0) fail("module(memory_pulses): must be at least 0")
      return WorldModule(
        engine = d.str("engine"),
        apiVersion = d.int("api_version") ?: EngineInfo.API_VERSION,
        checkpointMinutes = minutes,
        startRoom = startRoom(d),
        plugins = stringMap(d, "plugins", "checksum"),
        aliases = CommandAliases(stringMap(d, "aliases", "command"), prefixes(d)),
        schema = schema(d),
        featureIds = featureIds,
        rename = stringMap(d, "rename", "new name"),
        levelMode =
          if (d.choice("level_mode") == "deferred") LevelMode.DEFERRED else LevelMode.AUTO,
        standingHistoryPulses = standingHistoryPulses,
        memoryPulses = memoryPulses,
      )
    }
  }
}

/**
 * Runs a settings file such as `MODULE.gups` with [reader]'s `@StarlarkMethod`s predeclared.
 * Returns the error, if any, as a [LoadError] of [kind].
 */
internal fun execSettings(
  path: java.nio.file.Path,
  name: String,
  kind: String,
  reader: Any,
): LoadError? =
  try {
    Mutability.create(name).use { mu ->
      val thread = StarlarkThread(mu, SEMANTICS)
      thread.setMaxExecutionSteps(100_000)
      val module =
        Module.withPredeclared(
          SEMANTICS,
          ImmutableMap.builder<String, Any>().also { Starlark.addMethods(it, reader) }.build(),
        )
      Starlark.execFile(ParserInput.fromString(path.readText(), name), FILE_OPTIONS, module, thread)
    }
    null
  } catch (e: SyntaxError.Exception) {
    val err = e.errors().first()
    LoadError("", "syntax", err.message(), name, err.location().line(), err.location().column())
  } catch (e: EvalException) {
    val loc = innermost(e)
    LoadError("", kind, e.message ?: "error", name, loc?.line(), loc?.column())
  }
