package gups.script.testing

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
        stat("wis", label = "Wisdom"), "armor",
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
   * The template's `combat.star` with each function that [overrides] define replaced by the
   * override; an override whose name the template lacks is added.
   */
  fun rules(vararg overrides: String): String {
    val over = overrides.flatMap { blocks(it.trimIndent() + "\n") }
    val names = over.map { it.first }.toSet()
    return (blocks(base).filter { it.first !in names } + over).joinToString("\n") { it.second }
  }

  /** The rules exports each feature owns; the others belong to the kernel. */
  private val OWNED: Map<String, String> =
    mapOf(
      "to_hit" to "combat",
      "damage" to "combat",
      "initiative" to "combat",
      "flee_chance" to "combat",
      "cast_allowed" to "skills",
      "interrupt_check" to "skills",
      "retreat_check" to "track",
      "get_lag" to "track",
      "advance_lag" to "track",
      "retreat_lag" to "track",
      "retreat_fail_lag" to "track",
      "flee_fail_lag" to "combat",
      "xp_for_kill" to "leveling",
      "xp_to_level" to "leveling",
    )

  /** The template's rules without the exports of the features not in [enabled] (feature ids). */
  fun rulesFor(enabled: Collection<String>, vararg overrides: String): String {
    val over = overrides.flatMap { blocks(it.trimIndent() + "\n") }
    val names = over.map { it.first }.toSet()
    return (blocks(base).filter { it.first !in names } + over)
      .filter { (name, _) -> OWNED[name].let { it == null || it in enabled } }
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
            .replace("        \"xp_loss\": ctx.actor.xp // 10,\n", "")
      }
  }

  /** Splits Starlark source into its top-level `def`s, each with the comments before the next. */
  private fun blocks(source: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var name: String? = null
    val text = StringBuilder()
    for (line in source.lines()) {
      val def = Regex("def (\\w+)\\(").find(line)?.takeIf { line.startsWith("def ") }
      if (def != null) {
        name?.let { out += it to text.toString().trimEnd() + "\n" }
        name = def.groupValues[1]
        text.clear()
      }
      if (name != null) text.append(line).append('\n')
    }
    name?.let { out += it to text.toString().trimEnd() + "\n" }
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
}
