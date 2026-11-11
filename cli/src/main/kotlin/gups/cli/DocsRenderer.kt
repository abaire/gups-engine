package gups.cli

import gups.script.DeclSpec
import gups.script.DocExample
import gups.script.DocExamples
import gups.script.MethodSpec
import gups.script.ScriptApiModel
import gups.script.TypeSpec
import gups.script.forFeatures

/** The optional inputs of [DocsRenderer.render] beyond the API model itself. */
data class RenderOptions(
  val changelogMarkdown: String = DocsRenderer.bundledChangelog(),
  val plugins: List<PluginDocSpec> = emptyList(),
  val examples: Map<String, DocExample> = DocExamples.ALL,
  val pluginGuide: String = DocsRenderer.bundledPluginGuide(),
  val world: WorldDocSpec? = null,
)

/**
 * HTML documentation generator producing offline-capable API reference documentation from the
 * script API model.
 *
 * Renders static HTML pages and `api.json` for consumption by the in-game web client, `gups docs`,
 * and docs servers:
 * - Offline navigation: Generated documentation requires no client-side JavaScript or external
 *   CDNs; pages use relative hyperlinks between documents in [FILES].
 * - Sections covered: Declarations, hooks, `ctx` and views, effect primitives and behaviors, rules
 *   module exports, test harness utilities, world configuration, plugin SPI interfaces, and
 *   changelog history.
 */
object DocsRenderer {
  val FILES: List<String> =
    listOf(
      "style.css",
      "index.html",
      "declarations.html",
      "hooks.html",
      "ctx.html",
      "effects.html",
      "rules.html",
      "harness.html",
      "world.html",
      "plugins.html",
      "changelog.html",
      "api.json",
    )

  /** Each page's title, in nav order. */
  internal val TITLES: Map<String, String> =
    linkedMapOf(
      "index.html" to "Overview",
      "declarations.html" to "Declarations",
      "hooks.html" to "Hooks",
      "ctx.html" to "ctx and views",
      "effects.html" to "Effects and behaviors",
      "rules.html" to "Rules",
      "harness.html" to "Test harness",
      "world.html" to "World",
      "plugins.html" to "Plugins",
      "changelog.html" to "Changelog",
    )

  private const val CHANGELOG_RESOURCE = "gups/docs/CHANGELOG.md"
  private const val GUIDE_RESOURCE = "gups/docs/PLUGINS.md"

  /** `"<page>.html#<id>"` for a symbol of [model]. */
  fun symbolHref(model: ScriptApiModel, symbol: String): String {
    val page =
      pageSymbols(model).entries.firstOrNull { symbol in it.value }?.key
        ?: throw IllegalArgumentException("'$symbol' is not a symbol of the API model")
    return "$page#${anchor(symbol)}"
  }

  /** `docs/CHANGELOG.md`, as bundled into the CLI's resources. */
  fun bundledChangelog(): String = resource(CHANGELOG_RESOURCE)

  /** `docs/PLUGINS.md`, the engine-developer guide, as bundled into the CLI's resources. */
  fun bundledPluginGuide(): String = resource(GUIDE_RESOURCE)

  private fun resource(name: String): String {
    val stream =
      DocsRenderer::class.java.classLoader.getResourceAsStream(name)
        ?: error("resource $name is missing; the cli build bundles it from docs/")
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  /** The reference as file name → content, keyed in [FILES] order. */
  fun render(model: ScriptApiModel, options: RenderOptions = RenderOptions()): Map<String, String> {
    val shown = options.world?.let { model.forFeatures(it.features) } ?: model
    val r = DocsPageRenderer(shown, options.examples, options.world)
    val pages =
      mapOf(
        "style.css" to DOCS_STYLE,
        "index.html" to r.page("index.html") { with(r) { index(options.plugins) } },
        "declarations.html" to r.page("declarations.html") { with(r) { declarations() } },
        "hooks.html" to r.page("hooks.html") { with(r) { hooks() } },
        "ctx.html" to r.page("ctx.html") { with(r) { ctx() } },
        "effects.html" to r.page("effects.html") { with(r) { effects() } },
        "rules.html" to r.page("rules.html") { with(r) { rules() } },
        "harness.html" to r.page("harness.html") { with(r) { harness() } },
        "world.html" to r.page("world.html") { with(r) { world() } },
        "plugins.html" to
          r.page("plugins.html") { with(r) { plugins(options.plugins, options.pluginGuide) } },
        "changelog.html" to
          r.page("changelog.html") { with(r) { changelog(options.changelogMarkdown) } },
        "api.json" to model.toJson(),
      )
    return FILES.associateWith { pages.getValue(it) }
  }

  /** The `id` of a symbol's element: `hook:on_enter` → `hook-on_enter`. */
  internal fun anchor(symbol: String): String = symbol.replace(':', '-')

  /** The symbols each page holds, following `api.json`'s symbol paths. */
  internal fun pageSymbols(model: ScriptApiModel): Map<String, Set<String>> {
    fun decl(d: DeclSpec) = listOf(d.name) + d.params.map { "${d.name}.${it.name}" }
    fun method(m: MethodSpec, path: String) = listOf(path) + m.params.map { "$path.${it.name}" }
    fun type(t: TypeSpec, path: String) =
      listOf(path) +
        t.fields.map { "$path.${it.name}" } +
        t.methods.flatMap { method(it, "$path.${it.name}") }
    return linkedMapOf(
        "declarations.html" to
          (model.declarations + model.settings).flatMap(::decl) + model.constants.map { it.name },
        "hooks.html" to model.hooks.map { "hook:${it.name}" },
        "ctx.html" to type(model.ctx, "ctx") + model.views.flatMap { type(it, "view:${it.name}") },
        "effects.html" to (model.effects + model.behaviors).flatMap { method(it, it.name) },
        "rules.html" to
          type(model.rules.ctx, model.rules.ctx.name) +
            model.rules.exports.map { "rules:${it.name}" } +
            model.rules.modifierPoints.map { "modifier:${it.name}" },
        "harness.html" to model.harness.flatMap { type(it, it.name) },
        "world.html" to model.messages.map { "message:${it.id}" },
      )
      .mapValues { it.value.toSet() }
  }
}
