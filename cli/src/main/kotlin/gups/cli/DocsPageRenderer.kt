package gups.cli

import gups.core.model.SchemaName
import gups.core.world.Feature
import gups.script.DeclSpec
import gups.script.DocExample
import gups.script.HookSpec
import gups.script.MethodSpec
import gups.script.ScriptApiModel
import gups.script.TypeSpec

/** The element and heading tag of an entry's opening line. */
internal enum class EntryLevel(val element: String, val heading: String) {
  TYPE("section", "h2"),
  SECTION("section", "h3"),
  METHOD("article", "h4"),
}

/** Renders the reference's pages from the API model, a plugin list and a world description. */
internal class DocsPageRenderer(
  val model: ScriptApiModel,
  val examples: Map<String, DocExample>,
  val world: WorldDocSpec?,
) {
  val n = model.apiVersion

  fun page(file: String, body: StringBuilder.() -> Unit): String = buildString {
    val title = DocsRenderer.TITLES.getValue(file)
    append("<!DOCTYPE html>\n")
    append("<html lang=\"en\">\n")
    append("<head>\n")
    append("<meta charset=\"utf-8\">\n")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    append("<title>").append(esc(title)).append(" — GUPS API Reference (api_version $n)</title>\n")
    append("<link rel=\"stylesheet\" href=\"style.css\">\n")
    append("</head>\n")
    append("<body>\n")
    append("<header>\n")
    append("<div class=\"brand\">GUPS API Reference <span class=\"engine\">engine ")
      .append(esc(model.engineVersion))
      .append("</span> <span class=\"badge\">api_version $n</span></div>\n")
    append("<nav>\n")
    for ((f, t) in DocsRenderer.TITLES) {
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
        .append(esc(DocsRenderer.TITLES.getValue(file)))
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
      entryStart(EntryLevel.SECTION, c.name, c.name, c.since)
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

  /** The hook table, then one entry per hook. */
  fun StringBuilder.hooks() {
    hooksTable()
    for (h in model.hooks) hookEntry(h)
  }

  private fun StringBuilder.hooksTable() {
    append(
      "<table>\n<thead><tr><th>Hook</th><th>Signature</th><th>Vetoable</th><th>Attaches to</th></tr></thead>\n<tbody>\n"
    )
    for (h in model.hooks) {
      append("<tr><td><a href=\"#")
        .append(DocsRenderer.anchor("hook:${h.name}"))
        .append("\"><code>")
        .append(esc(h.name))
        .append("</code></a>")
        .append(featureBadge("hook:${h.name}"))
        .append("</td><td><code>")
        .append(esc(h.signature))
        .append("</code></td><td>")
        .append(if (h.vetoable) "yes" else "no")
        .append("</td><td>")
        .append(
          esc(
            if (h.attachTo.isEmpty()) "Global only" else h.attachTo.joinToString(", ") { it.word }
          )
        )
        .append("</td></tr>\n")
    }
    append("</tbody>\n</table>\n")
  }

  private fun StringBuilder.hookEntry(h: HookSpec) {
    val symbol = "hook:${h.name}"
    entryStart(EntryLevel.SECTION, symbol, h.name, h.since)
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
      .append(
        esc(if (h.attachTo.isEmpty()) "Global only" else h.attachTo.joinToString(", ") { it.word })
      )
      .append("</p>\n")
    ctxFieldList("ctx fields", "ctx.html", "ctx", h.ctxFields)
    example(symbol)
    append("</section>\n")
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
    for (e in model.effects) method(e, e.name)
    h2("Behaviors", "Built-in mob behaviors for mob(behaviors=[...]).")
    for (b in model.behaviors) method(b, b.name)
  }

  fun StringBuilder.rules() {
    val ctxName = model.rules.ctx.name
    type(model.rules.ctx, ctxName)
    h2("Exports", "Functions the files of //rules export for the engine to call.")
    for (e in model.rules.exports) {
      val symbol = "rules:${e.name}"
      entryStart(EntryLevel.SECTION, symbol, e.name, e.since)
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
      entryStart(EntryLevel.SECTION, symbol, mp.name, mp.since)
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
    world?.let { w -> worldDetails(w) }
    messagesTable()
  }

  private fun StringBuilder.worldDetails(w: WorldDocSpec) {
    h2("Schema", "The names this world's actors are measured by.")
    schemaNames("Stats", w.schema.stats)
    schemaNames("Vitals", w.schema.vitals)
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
    worldCommands(w)
    worldGmcp(w)
  }

  private fun StringBuilder.schemaNames(title: String, list: List<SchemaName>) {
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

  private fun StringBuilder.worldCommands(w: WorldDocSpec) {
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
  }

  private fun StringBuilder.worldGmcp(w: WorldDocSpec) {
    if (w.gmcp.isEmpty()) return
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

  private fun StringBuilder.messagesTable() {
    h2("Messages", "Every player-facing line of the engine; MESSAGES.gups overrides them by id.")
    append(
      "<table>\n<thead><tr><th>Id</th><th>Placeholders</th><th>Default text</th>" +
        (if (world != null) "<th>This world's text</th>" else "") +
        "</tr></thead>\n<tbody>\n"
    )
    for (m in model.messages) {
      val feature = m.feature?.let { Feature.byId(it) }
      append("<tr id=\"")
        .append(esc(DocsRenderer.anchor("message:${m.id}")))
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

  /** The guide, then one `<section>` per loaded plugin. */
  fun StringBuilder.plugins(plugins: List<PluginDocSpec>, guide: String) {
    append("<section id=\"guide\" class=\"guide\">\n")
      .append(markdownToHtml(guide))
      .append("</section>\n")
    append("<h2 id=\"loaded-plugins\">This world's plugins</h2>\n")
    if (plugins.isEmpty()) {
      append("<p>No plugins are loaded.</p>\n")
      return
    }
    for (p in plugins.sortedBy { it.id }) pluginEntry(p)
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

  fun StringBuilder.entryStart(level: EntryLevel, symbol: String, name: String, since: Int) {
    append('<')
      .append(level.element)
      .append(" id=\"")
      .append(esc(DocsRenderer.anchor(symbol)))
      .append("\" class=\"entry\">\n")
    append('<')
      .append(level.heading)
      .append("><code>")
      .append(esc(name))
      .append("</code> <span class=\"since\">since api_version ")
      .append(since)
      .append("</span>")
      .append(featureBadge(symbol))
      .append("</")
      .append(level.heading)
      .append(">\n")
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
        "<a href=\"$page#${esc(DocsRenderer.anchor("$prefix.$f"))}\"><code>${esc(f)}</code></a>"
      }
    )
    append("</p>\n")
  }

  fun StringBuilder.decl(d: DeclSpec) {
    entryStart(EntryLevel.SECTION, d.name, d.name, d.since)
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
          .append(esc(DocsRenderer.anchor("${d.name}.${p.name}")))
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
    entryStart(EntryLevel.TYPE, path, t.name, t.since)
    doc(t.doc)
    if (t.builtinType != t.name)
      append("<p>Starlark type <code>").append(esc(t.builtinType)).append("</code></p>\n")
    example(path)
    typeFields(t, path)
    typeMethods(t, path)
    append("</section>\n")
  }

  private fun StringBuilder.typeFields(t: TypeSpec, path: String) {
    if (t.fields.isEmpty()) return
    append(
      "<h3>Fields</h3>\n<table>\n<thead><tr><th>Field</th><th>Since</th><th>Doc</th></tr></thead>\n<tbody>\n"
    )
    for (f in t.fields) {
      val symbol = "$path.${f.name}"
      append("<tr id=\"")
        .append(esc(DocsRenderer.anchor(symbol)))
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

  private fun StringBuilder.typeMethods(t: TypeSpec, path: String) {
    if (t.methods.isEmpty()) return
    append("<h3>Methods</h3>\n")
    for (m in t.methods) method(m, "$path.${m.name}", EntryLevel.METHOD)
  }

  fun StringBuilder.method(m: MethodSpec, path: String, level: EntryLevel = EntryLevel.SECTION) {
    entryStart(level, path, signature(m), m.since)
    doc(m.doc)
    methodParams(m, path)
    example(path)
    append("</").append(level.element).append(">\n")
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
