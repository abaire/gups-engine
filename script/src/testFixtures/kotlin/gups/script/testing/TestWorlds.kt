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

  private val base: String by lazy {
    TestWorlds::class
      .java
      .getResourceAsStream("/template-rules/combat.star")!!
      .readBytes()
      .toString(Charsets.UTF_8)
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
