package gups.harness

import gups.core.Label
import gups.core.model.WorldSchema
import gups.core.world.CommandAliases
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.script.LoadResult
import gups.script.PackageKind
import gups.script.ScriptLimits
import gups.script.StarlarkHost
import gups.script.WorldPluginWiring
import gups.script.callerPackage
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** Marks an assertion failure, as opposed to an error in the test's own code. */
internal class AssertionFailed(message: String) : RuntimeException(message)

private fun assertionFail(message: String): Nothing =
  throw EvalException(message, AssertionFailed(message))

/** The `t` passed to each test function. */
@StarlarkBuiltin(name = "t", doc = "The test context: makes worlds and checks results.")
class TestCtx
internal constructor(
  /** The test file's package. */
  private val pkg: String,
  private val loadWorld: (List<String>) -> LoadResult,
  private val defaultLoad: List<String>,
  private val plugins: WorldPluginWiring = WorldPluginWiring.NONE,
  /** The world's command aliases from `MODULE.gups`. */
  private val aliases: CommandAliases = CommandAliases.NONE,
  /** The names the world's actors are measured by, from `MODULE.gups`. */
  private val schema: WorldSchema = WorldSchema.EMPTY,
) : StarlarkValue {
  internal val worlds = mutableListOf<WorldHandle>()

  @StarlarkMethod(
    name = "world",
    doc =
      "A fresh headless world with the given packages (default: this test's package) and the rules package. " +
        "The spec's `load` argument is spelled `packages`, since load is a Starlark keyword (D-072). " +
        "rules names another rules package to use instead of //rules. bubble=True keeps a reality bubble, " +
        "so only rooms near players are simulated; by default every room is (D-116).",
    parameters =
      [
        Param(name = "seed", named = true, defaultValue = "0", doc = "Random seed for the world."),
        Param(
          name = "packages",
          named = true,
          defaultValue = "None",
          doc = "Packages to load, or None for this test's package.",
        ),
        Param(
          name = "rules",
          named = true,
          defaultValue = "None",
          doc = "Rules package label such as //rules_alt, or None for //rules.",
        ),
        Param(
          name = "bubble",
          named = true,
          defaultValue = "False",
          doc = "True to simulate only rooms near players; by default every room is simulated.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun world(seed: Any, packages: Any, rules: Any, bubble: Any, thread: StarlarkThread): Any {
    val rulesPkg =
      if (isNone(rules)) {
        "rules"
      } else {
        val text = str(rules, "world(rules)")
        if (!text.startsWith("//") || ':' in text || text.length < 3)
          evalFail("world(rules): '$text' should be a package label such as //rules")
        text.removePrefix("//")
      }
    val from = callerPackage(thread) ?: pkg
    val pkgs =
      if (isNone(packages)) {
        defaultLoad
      } else {
        (packages as? Sequence<*> ?: evalFail("world(packages): expected a list of package labels"))
          .map { p ->
            val text = str(p, "world(packages)")
            if (!text.startsWith("//") || ':' in text)
              evalFail("world(packages): '$text' should be a package label such as //zones/forest")
            text.removePrefix("//").also {
              if (it.isEmpty()) evalFail("world(packages): '$text' is not a package")
            }
          }
      }
    val result = loadWorld(if (isNone(rules)) pkgs else pkgs + rulesPkg)
    if (!result.ok) evalFail("world(): packages failed to load:\n" + result.report())
    if (!isNone(rules) && result.packages[rulesPkg]?.kind != PackageKind.RULES)
      evalFail("world(rules): //$rulesPkg is not a rules package")
    val host = StarlarkHost(result, ScriptLimits.HARNESS)
    val world =
      World(
        result.prototypes(),
        schema,
        host.rules(rulesPkg),
        host,
        WorldConfig(
          seed = int(seed, "world(seed)").toLong(),
          checkInvariants = true,
          strict = true,
          bubble = (bubble as? Boolean) ?: evalFail("world(bubble): expected True or False"),
          aliases = aliases,
        ),
      )
    plugins.install(world)
    host.install(world)
    world.fireOnLoad()
    plugins.started(world)
    return WorldHandle(world, host, from).also { worlds += it }
  }

  private fun same(a: Any?, b: Any?, thread: StarlarkThread): Boolean {
    val pkg = callerPackage(thread) ?: pkg
    labelOf(a)?.let { la ->
      if (b is String) return la == runCatching { Label.parse(b, pkg) }.getOrNull()
    }
    labelOf(b)?.let { lb ->
      if (a is String) return lb == runCatching { Label.parse(a, pkg) }.getOrNull()
    }
    return a == b
  }

  private fun shown(v: Any?): String {
    val r = Starlark.repr(v)
    return labelOf(v)?.let { "$r ($it)" } ?: r
  }

  private fun prefix(msg: Any): String = if (isNone(msg)) "" else "${str(msg, "msg")}: "

  @StarlarkMethod(
    name = "eq",
    doc =
      "Fails unless actual == expected. An entity view equals its label, so t.eq(p.room, ':hall') works.",
    parameters =
      [
        Param(name = "actual", named = true, doc = "Value under test."),
        Param(name = "expected", named = true, doc = "Expected value."),
        Param(
          name = "msg",
          named = true,
          defaultValue = "None",
          doc = "Extra text for the failure message.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun eq(actual: Any, expected: Any, msg: Any, thread: StarlarkThread): Any {
    if (same(actual, expected, thread)) return Starlark.NONE
    assertionFail(prefix(msg) + "values differ\n" + diff(actual, expected))
  }

  private fun diff(actual: Any?, expected: Any?): String {
    if (actual is String && expected is String && ('\n' in actual || '\n' in expected)) {
      return "  --- expected\n  +++ actual\n" +
        lineDiff(expected.lines(), actual.lines()).joinToString("\n") { "  $it" }
    }
    return "  actual:   ${shown(actual)}\n  expected: ${shown(expected)}"
  }

  @StarlarkMethod(
    name = "ne",
    doc = "Fails if actual == unexpected.",
    parameters =
      [
        Param(name = "actual", named = true, doc = "Value under test."),
        Param(name = "unexpected", named = true, doc = "Value it must not equal."),
        Param(
          name = "msg",
          named = true,
          defaultValue = "None",
          doc = "Extra text for the failure message.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun ne(actual: Any, unexpected: Any, msg: Any, thread: StarlarkThread): Any {
    if (!same(actual, unexpected, thread)) return Starlark.NONE
    assertionFail(prefix(msg) + "expected a value other than ${shown(unexpected)}")
  }

  @StarlarkMethod(
    name = "true",
    doc = "Fails unless the value is truthy.",
    parameters =
      [
        Param(name = "value", named = true, doc = "Value under test."),
        Param(
          name = "msg",
          named = true,
          defaultValue = "None",
          doc = "Extra text for the failure message.",
        ),
      ],
  )
  fun isTrue(value: Any, msg: Any): Any {
    if (Starlark.truth(value)) return Starlark.NONE
    assertionFail(prefix(msg) + "expected a true value, got ${shown(value)}")
  }

  @StarlarkMethod(
    name = "false",
    doc = "Fails unless the value is falsy.",
    parameters =
      [
        Param(name = "value", named = true, doc = "Value under test."),
        Param(
          name = "msg",
          named = true,
          defaultValue = "None",
          doc = "Extra text for the failure message.",
        ),
      ],
  )
  fun isFalse(value: Any, msg: Any): Any {
    if (!Starlark.truth(value)) return Starlark.NONE
    assertionFail(prefix(msg) + "expected a false value, got ${shown(value)}")
  }

  private fun holds(container: Any, item: Any): Boolean =
    when (container) {
      is String -> container.contains(str(item, "contains(item)"))
      is Sequence<*> -> container.any { it == item }
      is Map<*, *> -> container.containsKey(item)
      else -> evalFail("contains(): expected a string, list or dict, got ${typeName(container)}")
    }

  @StarlarkMethod(
    name = "contains",
    doc = "Fails unless a string contains a substring, or a list or dict contains an item.",
    parameters =
      [
        Param(name = "container", named = true, doc = "String, list or dict to search."),
        Param(name = "item", named = true, doc = "Substring, element or key to find."),
        Param(
          name = "msg",
          named = true,
          defaultValue = "None",
          doc = "Extra text for the failure message.",
        ),
      ],
  )
  fun contains(container: Any, item: Any, msg: Any): Any {
    if (holds(container, item)) return Starlark.NONE
    assertionFail(prefix(msg) + "${shown(item)} not found in:\n" + block(container))
  }

  @StarlarkMethod(
    name = "not_contains",
    doc = "Fails if a string contains a substring, or a list or dict contains an item.",
    parameters =
      [
        Param(name = "container", named = true, doc = "String, list or dict to search."),
        Param(name = "item", named = true, doc = "Substring, element or key that must be absent."),
        Param(
          name = "msg",
          named = true,
          defaultValue = "None",
          doc = "Extra text for the failure message.",
        ),
      ],
  )
  fun notContains(container: Any, item: Any, msg: Any): Any {
    if (!holds(container, item)) return Starlark.NONE
    assertionFail(prefix(msg) + "${shown(item)} unexpectedly found in:\n" + block(container))
  }

  private fun block(v: Any): String =
    if (v is String) v.lines().joinToString("\n") { "  | $it" } else "  ${Starlark.repr(v)}"

  @StarlarkMethod(
    name = "fail",
    doc = "Fails the test.",
    parameters = [Param(name = "msg", named = true, doc = "Failure message.")],
  )
  fun fail(msg: Any): Any = assertionFail(str(msg, "fail(msg)"))

  override fun isImmutable(): Boolean = true
}

/** A minimal line diff: unchanged lines with two spaces, removed with '-', added with '+'. */
internal fun lineDiff(a: List<String>, b: List<String>): List<String> {
  val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
  for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
    lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
  }
  val out = mutableListOf<String>()
  var i = 0
  var j = 0
  while (i < a.size || j < b.size) {
    when {
      i < a.size && j < b.size && a[i] == b[j] -> out += "  ${a[i++]}".also { j++ }
      j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> out += "+ ${b[j++]}"
      else -> out += "- ${a[i++]}"
    }
  }
  return out
}
