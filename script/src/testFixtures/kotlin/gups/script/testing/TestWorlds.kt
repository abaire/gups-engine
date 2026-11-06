package gups.script.testing

import gups.script.DocExamples
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `MODULE.gups` and rules package that test worlds get unless they bring their own: the
 * template world's fantasy schema and rules, so tests of scripts need not write a whole ruleset.
 */
object TestWorlds {
  /** The template world's schema arguments of `module(...)`. */
  private val SCHEMA_ARGS: String =
    """
    stats = [
        stat("str", label = "Strength"), stat("dex", label = "Dexterity"),
        stat("con", label = "Constitution"), stat("int", label = "Intelligence"),
        stat("wis", label = "Wisdom"), stat("cha", label = "Charisma"), "armor",
    ],
    vitals = [vital("hp", label = "Hit points"), "mana", vital("move", label = "Movement")],
    damage_types = ["bludgeon", "slash", "pierce", "fire"],
    unarmed_damage_type = "bludgeon",
    channels = ["spell"],
    prompt = "<{hp}hp {mana}m {move}mv> ",
    """
      .trimIndent()

  /** A `MODULE.gups` with the template world's schema and the [extra] arguments. */
  fun module(extra: String = ""): String = "module(api_version = 1, $SCHEMA_ARGS $extra)\n"

  /** A `MODULE.gups` with the template world's schema. */
  val MODULE: String = module()

  const val RULES_PACKAGE: String = "package(api_version = 1, kind = \"rules\")\n"

  /**
   * The deferred-mode trainer example of `ctx.level_up` as a world: its package is `zones/ex` and
   * the trainer is in `//zones/ex:hall`.
   */
  val DEFERRED_TRAINER: Map<String, String> by lazy {
    val e = DocExamples.ALL.getValue("ctx.level_up")
    withDefaults(
      mapOf(
        "MODULE.gups" to module(e.module),
        "zones/ex/package.star" to "package(api_version = 1, kind = \"zone\")\n",
        "zones/ex/fixture.star" to DocExamples.FIXTURE,
        "zones/ex/example.star" to e.pkg,
      )
    )
  }

  /** The template world's rules files, which test worlds keep together in `combat.star`. */
  private val base: String by lazy {
    listOf("combat.star", "perception.star", "manipulation.star").joinToString("\n") {
      TestWorlds::class
        .java
        .getResourceAsStream("/template-rules/$it")!!
        .readBytes()
        .toString(Charsets.UTF_8)
    }
  }

  /**
   * Replaces template functions in every test world, so that tests see no level-up gains unless
   * they override `on_level_up` themselves.
   */
  private const val DEFAULTS: String =
    "def on_level_up(ctx):\n    return None\ndef _spell_hit(ctx):\n    return 100\ndef skill_check(ctx):\n    return 100\ndef skill_power(ctx):\n    return 100\ndef item_power(ctx):\n    return 100\n"

  /**
   * The template's blocks with [DEFAULTS] and then [overrides] replacing or adding functions. An
   * override's preamble is added after the template's rather than replacing it, since the
   * template's functions read the constants it declares.
   */
  private fun merged(overrides: Array<out String>): List<Pair<String, String>> {
    val over = LinkedHashMap<String, String>()
    val preambles = mutableListOf<Pair<String, String>>()
    for (src in listOf(DEFAULTS) + overrides) {
      for ((name, text) in blocks(src.trimIndent() + "\n")) {
        if (name.isEmpty()) preambles += name to text else over[name] = text
      }
    }
    return blocks(base).filter { it.first !in over } + preambles + over.toList()
  }

  /**
   * The template's `combat.star` with each function that [overrides] define replaced by the
   * override; an override whose name the template lacks is added. `on_level_up` returns `None`
   * unless overridden.
   */
  fun rules(vararg overrides: String): String = merged(overrides).joinToString("\n") { it.second }

  /** The rules exports each feature owns; the others belong to the kernel. */
  private val OWNED: Map<String, Set<String>> =
    mapOf(
      "to_hit" to setOf("combat", "skills"),
      "damage" to setOf("combat"),
      "initiative" to setOf("combat"),
      "flee_chance" to setOf("combat"),
      "cast_allowed" to setOf("skills"),
      "interrupt_check" to setOf("skills"),
      "skill_check" to setOf("skills"),
      "skill_power" to setOf("skills"),
      "retreat_check" to setOf("track"),
      "get_lag" to setOf("track"),
      "advance_lag" to setOf("track"),
      "retreat_lag" to setOf("track"),
      "retreat_fail_lag" to setOf("track"),
      "flee_fail_lag" to setOf("combat"),
      "xp_for_kill" to setOf("leveling"),
      "xp_share" to setOf("leveling"),
      "xp_to_level" to setOf("leveling"),
      "on_level_up" to setOf("leveling"),
    )

  /**
   * Default overrides when the `leveling` feature is off: `on_player_death` has no `xp_loss` or
   * `level_loss` and does not call `xp_to_level`.
   */
  private const val LEVELING_OFF_DEATH: String =
    "def on_player_death(ctx):\n    return {\"respawn\": ctx.actor.recall_room, \"vitals\": vitals(hp = 1),}\n"

  /** The template's rules without the exports of the features not in [enabled] (feature ids). */
  fun rulesFor(enabled: Collection<String>, vararg overrides: String): String {
    val allOverrides =
      if ("leveling" in enabled) overrides
      else (listOf(LEVELING_OFF_DEATH) + overrides).toTypedArray()
    return merged(allOverrides)
      .filter { (name, _) -> OWNED[name].let { it == null || it.any { f -> f in enabled } } }
      .joinToString("\n") { it.second }
      .let { text ->
        if ("track" in enabled) text
        else
          text.replace(
            "    if ctx.room.size == \"large\" and ctx.channel == \"sound\":\n        cost += 1\n",
            "",
          )
      }
      .let { text ->
        if ("leveling" in enabled) text
        else
          text
            .replace(" + (a.level - d.level) * 3", "")
            .replace("ctx.actor.dex + ctx.actor.level", "ctx.actor.dex")
            .replace(" + a.level // 2", "")
      }
  }

  /**
   * Splits Starlark source into its top-level `def`s, each with the comments before the next,
   * preceded by the preamble (comments and constants before the first `def`) under the empty name
   * when it is not blank.
   */
  private fun blocks(source: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var name: String? = null
    var preamble = ""
    var text = ""
    for (line in source.lines()) {
      val def = Regex("def (\\w+)\\(").find(line)?.takeIf { line.startsWith("def ") }
      if (def != null) {
        if (name == null) {
          if (preamble.isNotBlank()) out += "" to preamble.trimEnd() + "\n"
        } else {
          out += name to text.trimEnd() + "\n"
        }
        name = def.groupValues[1]
        text = line + '\n'
      } else if (name == null) {
        preamble += line + '\n'
      } else {
        text += line + '\n'
      }
    }
    if (name == null) {
      if (preamble.isNotBlank()) out += "" to preamble.trimEnd() + "\n"
    } else {
      out += name to text.trimEnd() + "\n"
    }
    return out
  }

  /**
   * [files] plus a `MODULE.gups` and a rules package unless they hold one. A world that lists any
   * file of `rules/` is taken to have its own rules.
   */
  fun withDefaults(files: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap(files)
    if ("MODULE.gups" !in out) out["MODULE.gups"] = MODULE
    if (out.keys.none { it.startsWith("rules/") }) {
      out["rules/package.star"] = RULES_PACKAGE
      out["rules/combat.star"] = rules()
    } else if ("rules/combat.star" !in out && "rules/package.star" in out) {
      out["rules/combat.star"] = rules()
    }
    return out
  }

  /** The template world's directory, from the `gups.templateWorld` system property. */
  val templateWorld: Path
    get() =
      Path.of(
        checkNotNull(System.getProperty("gups.templateWorld")) { "gups.templateWorld is unset" }
      )

  /**
   * Rewrites the `_spell_hit`, `skill_check`, and `skill_power` blocks of every `.star` file in
   * [rulesDir] that defines them so they return 100. Files without those functions are left
   * unchanged.
   */
  fun makeSpellsLand(rulesDir: Path) {
    if (!Files.isDirectory(rulesDir)) return
    Files.list(rulesDir).use { stream ->
      for (file in stream) {
        if (!file.toString().endsWith(".star") || !Files.isRegularFile(file)) continue
        val content = Files.readString(file)
        val blk = blocks(content)
        if (blk.any { it.first in setOf("_spell_hit", "skill_check", "skill_power") }) {
          val rewritten =
            blk.joinToString("\n") { (name, text) ->
              when (name) {
                "_spell_hit" -> "def _spell_hit(ctx):\n    return 100\n"
                "skill_check" -> "def skill_check(ctx):\n    return 100\n"
                "skill_power" -> "def skill_power(ctx):\n    return 100\n"
                else -> text
              }
            }
          Files.writeString(file, rewritten)
        }
      }
    }
  }

  /**
   * Copies the world at [from] into [to] and returns [to]. A developer's local server data
   * (`.gups`, where `gups serve` keeps the database) is left out, so a test starts with no database
   * whatever has been run in the source world. By default, [spellsAlwaysLand] rewrites `_spell_hit`
   * in the copied rules to return 100, keeping plugin and integration tests independent of spell
   * resistance; pass `false` to test it.
   */
  fun copyWorld(from: Path = templateWorld, to: Path, spellsAlwaysLand: Boolean = true): Path {
    val local = from.resolve(".gups")
    Files.walk(from).use { paths ->
      for (p in paths) {
        if (p.startsWith(local)) continue
        val target = to.resolve(from.relativize(p).toString())
        if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
      }
    }
    if (spellsAlwaysLand) {
      makeSpellsLand(to.resolve("rules"))
    }
    return to
  }
}
