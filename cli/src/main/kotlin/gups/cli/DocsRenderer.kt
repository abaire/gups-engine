package gups.cli

import gups.core.model.WorldSchema
import gups.core.world.CommandInfo
import gups.core.world.Feature
import gups.core.world.FeatureRequirement
import gups.core.world.Features
import gups.script.DeclSpec
import gups.script.DocExample
import gups.script.DocExamples
import gups.script.FeatureOwnership
import gups.script.MethodSpec
import gups.script.ScriptApiModel
import gups.script.TypeSpec
import gups.script.forFeatures
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

/** A command a plugin adds, for `plugins.html`. */
data class PluginCommandDocSpec(
  val name: String,
  val lane: String,
  val role: String,
  val doc: String,
)

/** A GMCP package a plugin adds, for `plugins.html`. */
data class PluginGmcpDocSpec(val pkg: String, val doc: String, val kind: String = "state")

/** A custom hook a plugin registers a handler for, for `plugins.html`. */
data class PluginHookDocSpec(val name: String, val doc: String)

/** A GMCP package a script package declares, for `world.html`. */
data class ScriptGmcpDocSpec(val pkg: String, val doc: String, val declaredBy: String)

/** A trait a plugin adds, for `plugins.html`. */
data class PluginTraitDocSpec(
  val name: String,
  val label: String,
  val doc: String,
  val statDeltas: Map<String, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val modifiers: Set<String> = emptySet(),
)

/** An option a plugin choice declares, for `plugins.html`. */
data class PluginOptionDocSpec(
  val id: String,
  val label: String,
  val summary: String,
  val help: String,
  val traits: List<String>,
)

/** A choice a plugin creation provider declares, for `plugins.html`. */
data class PluginChoiceDocSpec(
  val id: String,
  val title: String,
  val prompt: String,
  val options: List<PluginOptionDocSpec>,
)

/** A restriction a plugin registers, for `plugins.html`. */
data class PluginRestrictionDocSpec(val point: String, val doc: String)

/** A containment filter a plugin registers, for `plugins.html`. */
data class PluginFilterDocSpec(val doc: String)

/** What `plugins.html` shows about one plugin. */
data class PluginDocSpec(
  val id: String,
  val doc: String = "",
  val builtins: List<MethodSpec> = emptyList(),
  val effects: List<MethodSpec> = emptyList(),
  val commands: List<PluginCommandDocSpec> = emptyList(),
  val gmcp: List<PluginGmcpDocSpec> = emptyList(),
  val hooks: List<PluginHookDocSpec> = emptyList(),
  val traits: List<PluginTraitDocSpec> = emptyList(),
  val restrictions: List<PluginRestrictionDocSpec> = emptyList(),
  val choices: List<PluginChoiceDocSpec> = emptyList(),
  val containmentFilters: List<PluginFilterDocSpec> = emptyList(),
)

/** What `world.html` shows about one world, and what its pages leave out. */
data class WorldDocSpec(
  val schema: WorldSchema,
  val features: Features,
  val rename: Map<String, String>,
  val commands: List<CommandInfo>,
  /** `MESSAGES.gups` overrides by message id. */
  val messages: Map<String, String> = emptyMap(),
  /** GMCP packages the world's `lib` and `rules` packages declare. */
  val gmcp: List<ScriptGmcpDocSpec> = emptyList(),
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
  private val TITLES: Map<String, String> =
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
  fun render(
    model: ScriptApiModel,
    changelogMarkdown: String = bundledChangelog(),
    plugins: List<PluginDocSpec> = emptyList(),
    examples: Map<String, DocExample> = DocExamples.ALL,
    pluginGuide: String = bundledPluginGuide(),
    world: WorldDocSpec? = null,
  ): Map<String, String> {
    val shown = world?.let { model.forFeatures(it.features) } ?: model
    val r = Renderer(shown, examples, world)
    val pages =
      mapOf(
        "style.css" to STYLE,
        "index.html" to r.page("index.html") { with(r) { index(plugins) } },
        "declarations.html" to r.page("declarations.html") { with(r) { declarations() } },
        "hooks.html" to r.page("hooks.html") { with(r) { hooks() } },
        "ctx.html" to r.page("ctx.html") { with(r) { ctx() } },
        "effects.html" to r.page("effects.html") { with(r) { effects() } },
        "rules.html" to r.page("rules.html") { with(r) { rules() } },
        "harness.html" to r.page("harness.html") { with(r) { harness() } },
        "world.html" to r.page("world.html") { with(r) { world() } },
        "plugins.html" to r.page("plugins.html") { with(r) { plugins(plugins, pluginGuide) } },
        "changelog.html" to r.page("changelog.html") { with(r) { changelog(changelogMarkdown) } },
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

  private class Renderer(
    val model: ScriptApiModel,
    val examples: Map<String, DocExample>,
    val world: WorldDocSpec?,
  ) {
    val n = model.apiVersion

    /** The `feature: <id>` badge of the entry named by [symbol], if a feature owns it. */
    fun featureBadge(symbol: String): String = FeatureOwnership.of(symbol)?.let { badge(it) } ?: ""

    fun badge(req: FeatureRequirement): String =
      " <span class=\"badge feature\">${req.noun}: ${esc(req.text)}</span>"

    fun badge(feature: Feature): String = badge(FeatureRequirement.of(feature))

    fun page(file: String, body: StringBuilder.() -> Unit): String = buildString {
      val title = TITLES.getValue(file)
      append("<!DOCTYPE html>\n")
      append("<html lang=\"en\">\n")
      append("<head>\n")
      append("<meta charset=\"utf-8\">\n")
      append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
      append("<title>")
        .append(esc(title))
        .append(" — GUPS API Reference (api_version $n)</title>\n")
      append("<link rel=\"stylesheet\" href=\"style.css\">\n")
      append("</head>\n")
      append("<body>\n")
      append("<header>\n")
      append("<div class=\"brand\">GUPS API Reference <span class=\"engine\">engine ")
        .append(esc(model.engineVersion))
        .append("</span> <span class=\"badge\">api_version $n</span></div>\n")
      append("<nav>\n")
      for ((f, t) in TITLES) {
        append("<a href=\"").append(f).append('"')
        if (f == file) append(" class=\"current\"")
        append('>').append(esc(t)).append("</a>\n")
      }
      append("<a href=\"api.json\">api.json</a>\n")
      append("</nav>\n")
      append("</header>\n")
      append("<main>\n")
      append("<h1>").append(esc(title)).append("</h1>\n")
      body()
      append("</main>\n")
      append("</body>\n")
      append("</html>\n")
    }

    // ---- Pages --------------------------------------------------------------------------

    fun StringBuilder.index(plugins: List<PluginDocSpec>) {
      append("<p>The Starlark API of the GUPS engine ")
        .append(esc(model.engineVersion))
        .append(", <span class=\"badge\">api_version $n</span>. ")
        .append(
          "Changes to this version are in the <a href=\"changelog.html#api_version-$n\">changelog</a>.</p>\n"
        )
      append(
        "<table>\n<thead><tr><th>Page</th><th>Entries</th><th>Contents</th></tr></thead>\n<tbody>\n"
      )
      fun row(file: String, count: Int?, what: String) {
        append("<tr><td><a href=\"")
          .append(file)
          .append("\">")
          .append(esc(TITLES.getValue(file)))
          .append("</a></td><td>")
          .append(count?.toString() ?: "")
          .append("</td><td>")
          .append(esc(what))
          .append("</td></tr>\n")
      }
      val m = model
      row(
        "declarations.html",
        m.declarations.size + m.settings.size + m.constants.size,
        "Package declarations, MODULE.gups and server.gups settings, constants",
      )
      row("hooks.html", m.hooks.size, "Hooks entities can attach")
      row("ctx.html", 1 + m.views.size, "The hook ctx and the entity views")
      row(
        "effects.html",
        m.effects.size + m.behaviors.size,
        "Effect primitives and built-in mob behaviors",
      )
      row(
        "rules.html",
        1 + m.rules.exports.size + m.rules.modifierPoints.size,
        "The rules module: rules_ctx, exports and modifier points",
      )
      row("harness.html", m.harness.size, "Test harness objects")
      row("world.html", m.messages.size, "The world's schema, features and commands; the messages")
      row("plugins.html", plugins.size, "The world's plugins")
      row("changelog.html", null, "Script-facing changes per api_version")
      append("</tbody>\n</table>\n")
      append("<p>The machine-readable model is <a href=\"api.json\">api.json</a>.</p>\n")
      append("<p>Engine developers: <a href=\"plugins.html#guide\">Writing plugins</a>.</p>\n")
    }

    fun StringBuilder.declarations() {
      h2("Declarations", "Package content declared in .star files.")
      for (d in model.declarations) decl(d)
      h2("Settings", "MODULE.gups (module) and server.gups (server).")
      for (d in model.settings) decl(d)
      h2("Constants", "Predeclared values in every package.")
      for (c in model.constants) {
        entryStart("section", c.name, c.name, c.since)
        doc(c.doc)
        append("<p>Type <code>")
          .append(esc(c.type))
          .append("</code>, value <code>")
          .append(esc(c.value))
          .append("</code></p>\n")
        example(c.name)
        append("</section>\n")
      }
    }

    fun StringBuilder.hooks() {
      append(
        "<table>\n<thead><tr><th>Hook</th><th>Signature</th><th>Vetoable</th><th>Attaches to</th></tr></thead>\n<tbody>\n"
      )
      for (h in model.hooks) {
        append("<tr><td><a href=\"#")
          .append(anchor("hook:${h.name}"))
          .append("\"><code>")
          .append(esc(h.name))
          .append("</code></a>")
          .append(featureBadge("hook:${h.name}"))
          .append("</td><td><code>")
          .append(esc(h.signature))
          .append("</code></td><td>")
          .append(if (h.vetoable) "yes" else "no")
          .append("</td><td>")
          .append(esc(h.attachTo.joinToString(", ") { it.word }))
          .append("</td></tr>\n")
      }
      append("</tbody>\n</table>\n")
      for (h in model.hooks) {
        val symbol = "hook:${h.name}"
        entryStart("section", symbol, h.name, h.since)
        doc(h.doc)
        append("<p>Signature <code>").append(esc(h.signature)).append("</code></p>\n")
        if (h.vetoable) {
          append(
            "<p>Vetoable: return <a href=\"ctx.html#ctx.deny\"><code>ctx.deny(msg)</code></a> to refuse the action.</p>\n"
          )
        } else {
          append("<p>Not vetoable.</p>\n")
        }
        append("<p>Attaches to: ")
          .append(esc(h.attachTo.joinToString(", ") { it.word }))
          .append("</p>\n")
        ctxFieldList("ctx fields", "ctx.html", "ctx", h.ctxFields)
        example(symbol)
        append("</section>\n")
      }
    }

    fun StringBuilder.ctx() {
      type(model.ctx, "ctx")
      h2("Views", "Read-only views of the world that ctx and rules_ctx hand out.")
      for (v in model.views) type(v, "view:${v.name}")
    }

    fun StringBuilder.effects() {
      h2(
        "Effect primitives",
        "Declarative effects for skill(effect=...), item(use_effect=...) and hooks.",
      )
      for (e in model.effects) method(e, e.name, "section")
      h2("Behaviors", "Built-in mob behaviors for mob(behaviors=[...]).")
      for (b in model.behaviors) method(b, b.name, "section")
    }

    fun StringBuilder.rules() {
      val ctxName = model.rules.ctx.name
      type(model.rules.ctx, ctxName)
      h2("Exports", "Functions the files of //rules export for the engine to call.")
      for (e in model.rules.exports) {
        val symbol = "rules:${e.name}"
        entryStart("section", symbol, e.name, e.since)
        doc(e.doc)
        append("<p>Signature <code>")
          .append(esc(e.signature))
          .append("</code>, returns <code>")
          .append(esc(e.returnType))
          .append("</code></p>\n")
        ctxFieldList("rules_ctx fields", "rules.html", ctxName, e.ctxFields)
        example(symbol)
        append("</section>\n")
      }
      h2("Modifier points", "Values zones, regions, rooms, affects and worn items can adjust.")
      for (mp in model.rules.modifierPoints) {
        val symbol = "modifier:${mp.name}"
        entryStart("section", symbol, mp.name, mp.since)
        doc(mp.doc)
        append("<p>Signature <code>")
          .append(esc(mp.signature))
          .append("</code>, value <code>")
          .append(esc(mp.valueType))
          .append("</code></p>\n")
        ctxFieldList("rules_ctx fields", "rules.html", ctxName, mp.ctxFields)
        example(symbol)
        append("</section>\n")
      }
    }

    fun StringBuilder.harness() {
      append(
        "<p>Objects of <code>*_test.star</code> tests: <code>t</code> is the test, <code>w</code> a world, <code>p</code> a player.</p>\n"
      )
      for (t in model.harness) type(t, t.name)
    }

    fun StringBuilder.world() {
      world?.let { w ->
        h2("Schema", "The names this world's actors are measured by.")
        fun names(title: String, list: List<gups.core.model.SchemaName>) {
          if (list.isEmpty()) return
          append("<h3>").append(esc(title)).append("</h3>\n")
          append("<table>\n<thead><tr><th>Name</th><th>Label</th></tr></thead>\n<tbody>\n")
          for (n in list) append("<tr><td><code>")
            .append(esc(n.name))
            .append("</code></td><td>")
            .append(esc(n.label))
            .append("</td></tr>\n")
          append("</tbody>\n</table>\n")
        }
        names("Stats", w.schema.stats)
        names("Vitals", w.schema.vitals)
        append("<p>Damage types: ")
          .append(w.schema.damageTypes.joinToString(", ") { "<code>${esc(it)}</code>" })
          .append("; unarmed attacks do <code>")
          .append(esc(w.schema.unarmedDamageType))
          .append("</code>.</p>\n")
        if (w.schema.channels.isNotEmpty())
          append("<p>Channels: ")
            .append(w.schema.channels.joinToString(", ") { "<code>${esc(it)}</code>" })
            .append("</p>\n")
        h2("Features", "The engine features this world has on.")
        append("<ul>\n")
        for (f in Feature.entries) append("<li><code>")
          .append(esc(f.id))
          .append("</code>: ")
          .append(if (f in w.features) "on" else "off")
          .append("</li>\n")
        append("</ul>\n")
        h2("Commands", "The engine commands of this world, by the names players type.")
        append(
          "<table>\n<thead><tr><th>Command</th><th>Lane</th><th>Renamed from</th><th>Feature</th></tr></thead>\n<tbody>\n"
        )
        for (c in w.commands) append("<tr id=\"command-")
          .append(esc(c.name))
          .append("\"><td><code>")
          .append(esc(c.name))
          .append("</code></td><td>")
          .append(c.lane.name.lowercase())
          .append("</td><td>")
          .append(if (c.name != c.builtIn) "<code>${esc(c.builtIn)}</code>" else "")
          .append("</td><td>")
          .append(c.feature?.let { esc(it.id) } ?: "")
          .append("</td></tr>\n")
        append("</tbody>\n</table>\n")
        if (w.gmcp.isNotEmpty()) {
          h2("GMCP packages", "The GMCP packages this world's lib and rules packages declare.")
          append(
            "<table>\n<thead><tr><th>Package</th><th>Doc</th><th>Declared by</th></tr></thead>\n<tbody>\n"
          )
          for (g in w.gmcp.sortedBy { it.pkg }) {
            append("<tr><td><code>")
              .append(esc(g.pkg))
              .append("</code></td><td>")
              .append(esc(g.doc))
              .append("</td><td><code>")
              .append(esc(g.declaredBy))
              .append("</code></td></tr>\n")
          }
          append("</tbody>\n</table>\n")
        }
      }
      h2("Messages", "Every player-facing line of the engine; MESSAGES.gups overrides them by id.")
      append(
        "<table>\n<thead><tr><th>Id</th><th>Placeholders</th><th>Default text</th>" +
          (if (world != null) "<th>This world's text</th>" else "") +
          "</tr></thead>\n<tbody>\n"
      )
      for (m in model.messages) {
        val feature = m.feature?.let { Feature.byId(it) }
        append("<tr id=\"")
          .append(esc(anchor("message:${m.id}")))
          .append("\"><td><code>")
          .append(esc(m.id))
          .append("</code>")
          .append(feature?.let { badge(it) } ?: "")
          .append("</td><td>")
          .append(
            m.placeholders.joinToString(", ") {
              "<code>{${esc(it)}}</code>" + if (it in m.required) " (required)" else ""
            }
          )
          .append("</td><td><code>")
          .append(esc(m.default))
          .append("</code></td>")
        if (world != null)
          append("<td>")
            .append(world.messages[m.id]?.let { "<code>${esc(it)}</code>" } ?: "")
            .append("</td>")
        append("</tr>\n")
      }
      append("</tbody>\n</table>\n")
    }

    fun StringBuilder.plugins(plugins: List<PluginDocSpec>, guide: String) {
      append("<section id=\"guide\" class=\"guide\">\n")
        .append(markdownToHtml(guide))
        .append("</section>\n")
      append("<h2 id=\"loaded-plugins\">This world's plugins</h2>\n")
      if (plugins.isEmpty()) {
        append("<p>No plugins are loaded.</p>\n")
        return
      }
      for (p in plugins.sortedBy { it.id }) {
        val badge = "<span class=\"badge plugin\">plugin: ${esc(p.id)}</span>"
        append("<section id=\"plugin-").append(esc(p.id)).append("\">\n")
        append("<h2>").append(esc(p.id)).append(' ').append(badge).append("</h2>\n")
        doc(p.doc)
        fun methods(heading: String, list: List<MethodSpec>) {
          if (list.isEmpty()) return
          append("<h3>").append(heading).append("</h3>\n")
          for (m in list.sortedBy { it.name }) {
            append("<article id=\"plugin-")
              .append(esc(p.id))
              .append('.')
              .append(esc(m.name))
              .append("\">\n")
            append("<h4><code>")
              .append(esc(m.name))
              .append("</code> ")
              .append(badge)
              .append("</h4>\n")
            doc(m.doc)
            methodParams(m, null)
            append("</article>\n")
          }
        }
        methods("Builtins", p.builtins)
        methods("Effect primitives", p.effects)
        if (p.commands.isNotEmpty()) {
          append(
            "<h3>Commands</h3>\n<table>\n<thead><tr><th>Command</th><th>Lane</th><th>Role</th><th>Doc</th></tr></thead>\n<tbody>\n"
          )
          for (c in p.commands.sortedBy { it.name }) {
            append("<tr id=\"plugin-")
              .append(esc(p.id))
              .append('.')
              .append(esc(c.name))
              .append("\"><td><code>")
              .append(esc(c.name))
              .append("</code> ")
              .append(badge)
              .append("</td><td>")
              .append(esc(c.lane))
              .append("</td><td>")
              .append(esc(c.role))
              .append("</td><td>")
              .append(esc(c.doc))
              .append("</td></tr>\n")
          }
          append("</tbody>\n</table>\n")
        }
        if (p.gmcp.isNotEmpty()) {
          append(
            "<h3>GMCP packages</h3>\n<table>\n<thead><tr><th>Package</th><th>Kind</th><th>Doc</th></tr></thead>\n<tbody>\n"
          )
          for (g in p.gmcp.sortedWith(compareBy({ it.pkg }, { it.kind }))) {
            append("<tr id=\"plugin-")
              .append(esc(p.id))
              .append('.')
              .append(esc(g.pkg))
              .append('.')
              .append(esc(g.kind))
              .append("\"><td><code>")
              .append(esc(g.pkg))
              .append("</code> ")
              .append(badge)
              .append("</td><td>")
              .append(esc(g.kind))
              .append("</td><td>")
              .append(esc(g.doc))
              .append("</td></tr>\n")
          }
          append("</tbody>\n</table>\n")
        }
        if (p.hooks.isNotEmpty()) {
          append(
            "<h3>Custom hook handlers</h3>\n<table>\n<thead><tr><th>Hook</th><th>Doc</th></tr></thead>\n<tbody>\n"
          )
          for (h in p.hooks.sortedBy { it.name }) {
            append("<tr id=\"plugin-")
              .append(esc(p.id))
              .append(".hook-")
              .append(esc(h.name))
              .append("\"><td><code>")
              .append(esc(h.name))
              .append("</code> ")
              .append(badge)
              .append("</td><td>")
              .append(esc(h.doc))
              .append("</td></tr>\n")
          }
          append("</tbody>\n</table>\n")
        }
        if (p.traits.isNotEmpty()) {
          append("<h3>Traits</h3>\n")
          for (t in p.traits.sortedBy { it.name }) {
            append("<article id=\"plugin-")
              .append(esc(p.id))
              .append(".trait-")
              .append(esc(t.name))
              .append("\">\n")
            append("<h4><code>")
              .append(esc(t.name))
              .append("</code> ")
              .append(badge)
              .append("</h4>\n")
            append("<p><strong>").append(esc(t.label)).append("</strong></p>\n")
            if (t.doc.isNotEmpty()) doc(t.doc)
            val effects = mutableListOf<String>()
            for ((k, v) in t.statDeltas.toSortedMap()) effects +=
              "$k: ${if (v >= 0) "+$v" else "$v"}"
            for ((k, v) in t.maxVitalDeltas.toSortedMap()) effects +=
              "max $k: ${if (v >= 0) "+$v" else "$v"}"
            for ((k, v) in t.resistDeltas.toSortedMap()) effects +=
              "resist $k: ${if (v >= 0) "+$v" else "$v"}"
            for (f in t.flags.sorted()) effects += "flag: $f"
            for (m in t.modifiers.sorted()) effects += "modifier: $m"
            if (effects.isNotEmpty()) {
              append("<p>Effects: ").append(esc(effects.joinToString(", "))).append("</p>\n")
            }
            append("</article>\n")
          }
        }
        if (p.restrictions.isNotEmpty()) {
          append(
            "<h3>Restrictions</h3>\n<table>\n<thead><tr><th>Point</th><th>Doc</th></tr></thead>\n<tbody>\n"
          )
          val pointCounts = mutableMapOf<String, Int>()
          for (r in p.restrictions) {
            val pointLower = r.point.lowercase()
            val n = pointCounts.compute(pointLower) { _, c -> (c ?: 0) + 1 }!!
            append("<tr id=\"plugin-")
              .append(esc(p.id))
              .append(".restrict-")
              .append(esc(pointLower))
              .append('-')
              .append(n)
              .append("\"><td><code>")
              .append(esc(pointLower))
              .append("</code> ")
              .append(badge)
              .append("</td><td>")
              .append(esc(r.doc))
              .append("</td></tr>\n")
          }
          append("</tbody>\n</table>\n")
        }
        if (p.containmentFilters.isNotEmpty()) {
          append(
            "<h3>Containment filters</h3>\n<table>\n<thead><tr><th>Filter</th><th>Doc</th></tr></thead>\n<tbody>\n"
          )
          for ((i, f) in p.containmentFilters.withIndex()) {
            val n = i + 1
            append("<tr id=\"plugin-")
              .append(esc(p.id))
              .append(".filter-")
              .append(n)
              .append("\"><td><code>filter</code> ")
              .append(badge)
              .append("</td><td>")
              .append(esc(f.doc))
              .append("</td></tr>\n")
          }
          append("</tbody>\n</table>\n")
        }
        if (p.choices.isNotEmpty()) {
          append("<h3>Character creation choices</h3>\n")
          for (c in p.choices) {
            append("<article id=\"plugin-")
              .append(esc(p.id))
              .append(".choice-")
              .append(esc(c.id))
              .append("\">\n")
            append("<h4><code>")
              .append(esc(c.id))
              .append("</code> ")
              .append(badge)
              .append("</h4>\n")
            append("<p><strong>").append(esc(c.title)).append("</strong>")
            if (c.prompt.isNotEmpty()) {
              append(" &mdash; <em>").append(esc(c.prompt)).append("</em>")
            }
            append("</p>\n")
            if (c.options.isNotEmpty()) {
              append(
                "<table>\n<thead><tr><th>Option</th><th>Label</th><th>Summary</th><th>Help</th><th>Granted traits</th></tr></thead>\n<tbody>\n"
              )
              for (opt in c.options) {
                append("<tr id=\"plugin-")
                  .append(esc(p.id))
                  .append(".choice-")
                  .append(esc(c.id))
                  .append('.')
                  .append(esc(opt.id))
                  .append("\"><td><code>")
                  .append(esc(opt.id))
                  .append("</code> ")
                  .append(badge)
                  .append("</td><td>")
                  .append(esc(opt.label))
                  .append("</td><td>")
                  .append(esc(opt.summary))
                  .append("</td><td>")
                  .append(esc(opt.help))
                  .append("</td><td>")
                  .append(esc(opt.traits.joinToString(", ")))
                  .append("</td></tr>\n")
              }
              append("</tbody>\n</table>\n")
            }
            append("</article>\n")
          }
        }
        append("</section>\n")
      }
    }

    /** `CHANGELOG.md` is plain Markdown, rendered as written. */
    fun StringBuilder.changelog(markdown: String) {
      append("<section class=\"changelog\">\n")
        .append(markdownToHtml(markdown))
        .append("</section>\n")
    }

    // ---- Entries ------------------------------------------------------------------------

    fun StringBuilder.h2(title: String, intro: String) {
      append("<h2>").append(esc(title)).append("</h2>\n")
      append("<p class=\"intro\">").append(esc(intro)).append("</p>\n")
    }

    fun StringBuilder.entryStart(
      element: String,
      symbol: String,
      name: String,
      since: Int,
      tag: String = "h3",
    ) {
      append('<')
        .append(element)
        .append(" id=\"")
        .append(esc(anchor(symbol)))
        .append("\" class=\"entry\">\n")
      append('<')
        .append(tag)
        .append("><code>")
        .append(esc(name))
        .append("</code> <span class=\"since\">since api_version ")
        .append(since)
        .append("</span>")
        .append(featureBadge(symbol))
        .append("</")
        .append(tag)
        .append(">\n")
    }

    fun StringBuilder.doc(text: String) {
      if (text.isNotBlank()) append("<p>").append(esc(text)).append("</p>\n")
    }

    fun StringBuilder.example(symbol: String) {
      val e = examples[symbol] ?: return
      append("<div class=\"example\">\n<p class=\"caption\">Example</p>\n")
      if (e.module.isNotBlank()) {
        append("<p class=\"caption\">MODULE.gups</p>\n")
        append("<pre><code>")
          .append(esc("module(\n    ...\n    ${e.module},\n)"))
          .append("</code></pre>\n")
      }
      if (e.pkg.isNotBlank())
        append("<pre><code>").append(esc(e.pkg.trimEnd())).append("</code></pre>\n")
      if (e.test.isNotBlank()) {
        append("<p class=\"caption\">Test</p>\n")
        append("<pre><code>").append(esc(e.test.trimEnd())).append("</code></pre>\n")
      }
      append("</div>\n")
    }

    fun StringBuilder.ctxFieldList(
      label: String,
      page: String,
      prefix: String,
      fields: List<String>,
    ) {
      if (fields.isEmpty()) return
      append("<p>").append(esc(label)).append(": ")
      append(
        fields.joinToString(", ") { f ->
          "<a href=\"$page#${esc(anchor("$prefix.$f"))}\"><code>${esc(f)}</code></a>"
        }
      )
      append("</p>\n")
    }

    fun StringBuilder.decl(d: DeclSpec) {
      entryStart("section", d.name, d.name, d.since)
      doc(d.doc)
      d.hookOwner?.let {
        append("<p>Accepts the <a href=\"hooks.html\">hooks</a> of a ")
          .append(esc(it.word))
          .append(" as keyword arguments.</p>\n")
      }
      if (d.extraKeywords) append("<p>Accepts any keyword arguments.</p>\n")
      if (d.params.isNotEmpty()) {
        append(
          "<table>\n<thead><tr><th>Parameter</th><th>Type</th><th>Passed</th><th>Required</th><th>Default</th><th>Allowed values</th><th>Doc</th></tr></thead>\n<tbody>\n"
        )
        for (p in d.params) {
          val passed =
            listOfNotNull("positional".takeIf { p.positional }, "keyword".takeIf { p.keyword })
              .joinToString(" or ")
          append("<tr id=\"")
            .append(esc(anchor("${d.name}.${p.name}")))
            .append("\"><td><code>")
            .append(esc(p.name))
            .append("</code>")
            .append(featureBadge("${d.name}.${p.name}"))
          if (p.since != d.since)
            append(" <span class=\"since\">since api_version ").append(p.since).append("</span>")
          append("</td><td><code>")
            .append(esc(p.type))
            .append("</code></td><td>")
            .append(passed)
            .append("</td><td>")
            .append(if (p.required) "yes" else "no")
            .append("</td><td>")
            .append(p.default?.let { "<code>${esc(it)}</code>" } ?: "")
            .append("</td><td>")
            .append(p.allowedValues.joinToString(", ") { "<code>${esc(it)}</code>" })
            .append("</td><td>")
            .append(esc(p.doc))
            .append("</td></tr>\n")
        }
        append("</tbody>\n</table>\n")
      }
      example(d.name)
      append("</section>\n")
    }

    fun StringBuilder.type(t: TypeSpec, path: String) {
      entryStart("section", path, t.name, t.since, tag = "h2")
      doc(t.doc)
      if (t.builtinType != t.name)
        append("<p>Starlark type <code>").append(esc(t.builtinType)).append("</code></p>\n")
      example(path)
      if (t.fields.isNotEmpty()) {
        append(
          "<h3>Fields</h3>\n<table>\n<thead><tr><th>Field</th><th>Since</th><th>Doc</th></tr></thead>\n<tbody>\n"
        )
        for (f in t.fields) {
          val symbol = "$path.${f.name}"
          append("<tr id=\"")
            .append(esc(anchor(symbol)))
            .append("\"><td><code>")
            .append(esc(f.name))
            .append("</code>")
            .append(featureBadge(symbol))
            .append("</td><td>")
            .append(f.since)
            .append("</td><td>")
            .append(esc(f.doc))
            .append("</td></tr>\n")
        }
        append("</tbody>\n</table>\n")
      }
      if (t.methods.isNotEmpty()) {
        append("<h3>Methods</h3>\n")
        for (m in t.methods) method(m, "$path.${m.name}", "article", tag = "h4")
      }
      append("</section>\n")
    }

    fun StringBuilder.method(m: MethodSpec, path: String, element: String, tag: String = "h3") {
      entryStart(element, path, signature(m), m.since, tag)
      doc(m.doc)
      methodParams(m, path)
      example(path)
      append("</").append(element).append(">\n")
    }

    fun StringBuilder.methodParams(m: MethodSpec, path: String?) {
      if (m.params.isEmpty()) return
      append(
        "<table>\n<thead><tr><th>Parameter</th><th>Passed</th><th>Default</th><th>Doc</th></tr></thead>\n<tbody>\n"
      )
      for (p in m.params) {
        val passed =
          when {
            p.varargs -> "extra positional"
            p.kwargs -> "extra named"
            else ->
              listOfNotNull("positional".takeIf { p.positional }, "named".takeIf { p.named })
                .joinToString(" or ")
          }
        append("<tr")
        if (path != null) append(" id=\"").append(esc(anchor("$path.${p.name}"))).append('"')
        append("><td><code>")
          .append(esc(paramName(p.name, p.varargs, p.kwargs)))
          .append("</code>")
          .append(if (path != null) featureBadge("$path.${p.name}") else "")
          .append("</td><td>")
          .append(passed)
          .append("</td><td>")
          .append(p.default?.let { "<code>${esc(it)}</code>" } ?: "")
          .append("</td><td>")
          .append(esc(p.doc))
          .append("</td></tr>\n")
      }
      append("</tbody>\n</table>\n")
    }

    fun paramName(name: String, varargs: Boolean, kwargs: Boolean) =
      when {
        varargs -> "*$name"
        kwargs -> "**$name"
        else -> name
      }

    /** `name(a, b = 1, *rest)`. */
    fun signature(m: MethodSpec): String =
      m.name +
        "(" +
        m.params.joinToString(", ") { p ->
          paramName(p.name, p.varargs, p.kwargs) + (p.default?.let { " = $it" } ?: "")
        } +
        ")"
  }

  private val STYLE =
    """
    :root {
      color-scheme: dark;
      --bg: #111418;
      --panel: #1a1f25;
      --text: #d8dee4;
      --muted: #8b949e;
      --accent: #4ea1ff;
      --border: #2a3139;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
    }
    header {
      position: sticky;
      top: 0;
      background: var(--panel);
      border-bottom: 1px solid var(--border);
      padding: 8px 16px;
    }
    .brand { font-weight: 600; }
    .engine { color: var(--muted); font-weight: 400; }
    nav { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 4px; }
    nav a.current { color: var(--text); font-weight: 600; }
    main { max-width: 1100px; margin: 0 auto; padding: 8px 16px 48px; }
    a { color: var(--accent); text-decoration: none; }
    a:hover { text-decoration: underline; }
    code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
    pre {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 4px;
      padding: 8px 12px;
      overflow-x: auto;
    }
    .entry {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 4px 16px 12px;
      margin: 16px 0;
    }
    .entry .entry { background: var(--bg); }
    .entry pre { background: var(--bg); }
    .badge {
      display: inline-block;
      border: 1px solid var(--accent);
      color: var(--accent);
      border-radius: 10px;
      padding: 0 8px;
      font-size: 12px;
      font-weight: 400;
    }
    .badge.plugin { border-color: var(--muted); color: var(--muted); }
    .badge.feature { border-color: #d29922; color: #d29922; }
    .since { color: var(--muted); font-size: 12px; font-weight: 400; }
    .intro, .caption { color: var(--muted); }
    .caption { margin-bottom: 0; font-size: 13px; }
    table { border-collapse: collapse; width: 100%; margin: 8px 0; display: block; overflow-x: auto; }
    th, td { border: 1px solid var(--border); padding: 4px 8px; text-align: left; vertical-align: top; }
    th { background: var(--bg); }
    tr:target, .entry:target { outline: 2px solid var(--accent); }
    """
      .trimIndent() + "\n"
}

private val MARKDOWN_PARSER: Parser = Parser.builder().build()

private val MARKDOWN_RENDERER: HtmlRenderer =
  HtmlRenderer.builder()
    .escapeHtml(true)
    .attributeProviderFactory { HeadingIds() }
    .nodeRendererFactory { LinksAsText(it) }
    .build()

/**
 * Renders the changelog and the plugin guide as CommonMark. Raw HTML stays escaped, links and
 * images render as their text so nothing points outside the reference, and headings get ids.
 */
internal fun markdownToHtml(md: String): String =
  MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(md))

/** Heading ids: `api_version-<N>` for a version heading, otherwise a slug; repeats get `-2`, … */
private class HeadingIds : AttributeProvider {
  // One provider per render, so repeats are counted per document.
  private val ids = HashMap<String, Int>()

  override fun setAttributes(node: Node, tagName: String, attributes: MutableMap<String, String>) {
    if (node !is Heading) return
    val base = headingId(plainText(node).trim())
    val n = (ids[base] ?: 0) + 1
    ids[base] = n
    attributes["id"] = if (n == 1) base else "$base-$n"
  }

  private fun headingId(text: String): String {
    API_VERSION_HEADING.matchEntire(text)?.let {
      return "api_version-${it.groupValues[1]}"
    }
    return text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "section" }
  }

  private fun plainText(node: Node): String {
    val sb = StringBuilder()
    var c = node.firstChild
    while (c != null) {
      when (c) {
        is Text -> sb.append(c.literal)
        is Code -> sb.append(c.literal)
        else -> sb.append(plainText(c))
      }
      c = c.next
    }
    return sb.toString()
  }

  companion object {
    private val API_VERSION_HEADING = Regex("""api_version (\d+)( \(unreleased\))?""")
  }
}

/** Renders a link or image as its content alone, keeping the reference offline. */
private class LinksAsText(private val context: HtmlNodeRendererContext) : NodeRenderer {
  override fun getNodeTypes(): Set<Class<out Node>> = setOf(Link::class.java, Image::class.java)

  override fun render(node: Node) {
    var c = node.firstChild
    while (c != null) {
      val next = c.next
      context.render(c)
      c = next
    }
  }
}

/** HTML-escapes `&`, `<`, `>` and `"`. */
internal fun esc(text: String): String {
  val sb = StringBuilder(text.length)
  for (c in text) {
    when (c) {
      '&' -> sb.append("&amp;")
      '<' -> sb.append("&lt;")
      '>' -> sb.append("&gt;")
      '"' -> sb.append("&quot;")
      else -> sb.append(c)
    }
  }
  return sb.toString()
}
