package gups.cli

import gups.core.world.Feature
import gups.core.world.FeatureRequirement
import gups.script.FeatureOwnership
import gups.script.MethodSpec

/** The `<section>` of one loaded plugin, its subsections in the reference's order. */
internal fun StringBuilder.pluginEntry(p: PluginDocSpec) {
  val badge = pluginBadge(p.id)
  append("<section id=\"plugin-").append(esc(p.id)).append("\">\n")
  append("<h2>").append(esc(p.id)).append(' ').append(badge).append("</h2>\n")
  doc(p.doc)
  pluginMethods(p, "Builtins", p.builtins)
  pluginMethods(p, "Effect primitives", p.effects)
  pluginCommands(p)
  pluginGmcp(p)
  pluginHookHandlers(p)
  pluginRulesExports(p)
  pluginTraits(p)
  pluginRestrictions(p)
  pluginContainmentFilters(p)
  pluginOwnedBehaviors(p)
  pluginDispositionModifiers(p)
  pluginFactions(p)
  pluginChoices(p)
  pluginSteps(p)
  append("</section>\n")
}

/** The `plugin: <id>` badge shown beside every entry of a plugin. */
private fun pluginBadge(id: String): String =
  "<span class=\"badge plugin\">plugin: ${esc(id)}</span>"

/** The `builtins` and `effects` entries of a plugin. */
internal fun StringBuilder.pluginMethods(
  p: PluginDocSpec,
  heading: String,
  list: List<MethodSpec>,
) {
  if (list.isEmpty()) return
  val badge = pluginBadge(p.id)
  append("<h3>").append(heading).append("</h3>\n")
  for (m in list.sortedBy { it.name }) {
    append("<article id=\"plugin-")
      .append(esc(p.id))
      .append('.')
      .append(esc(m.name))
      .append("\">\n")
    append("<h4><code>").append(esc(m.name)).append("</code> ").append(badge).append("</h4>\n")
    doc(m.doc)
    methodParams(m, null)
    append("</article>\n")
  }
}

/** A `<h3>` section heading and a table of rows, each addressed by an element id. */
private fun StringBuilder.pluginTable(
  heading: String,
  columns: List<String>,
  rows: List<Pair<String, List<String>>>,
) {
  append("<h3>").append(esc(heading)).append("</h3>\n")
  append("<table>\n<thead><tr>")
  for (c in columns) append("<th>").append(esc(c)).append("</th>")
  append("</tr></thead>\n<tbody>\n")
  for ((id, cells) in rows) {
    append("<tr id=\"").append(esc(id)).append("\">")
    for (cell in cells) append("<td>").append(cell).append("</td>")
    append("</tr>\n")
  }
  append("</tbody>\n</table>\n")
}

/** The commands a plugin adds. */
internal fun StringBuilder.pluginCommands(p: PluginDocSpec) {
  if (p.commands.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.commands
      .sortedBy { it.name }
      .map { c ->
        "plugin-${p.id}.${c.name}" to
          listOf("<code>${esc(c.name)}</code> $badge", esc(c.lane), esc(c.role), esc(c.doc))
      }
  pluginTable("Commands", listOf("Command", "Lane", "Role", "Doc"), rows)
}

/** The GMCP packages a plugin adds. */
internal fun StringBuilder.pluginGmcp(p: PluginDocSpec) {
  if (p.gmcp.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.gmcp.sortedWith(compareBy({ it.pkg }, { it.kind })).map { g ->
      "plugin-${p.id}.${g.pkg}.${g.kind}" to
        listOf("<code>${esc(g.pkg)}</code> $badge", esc(g.kind), esc(g.doc))
    }
  pluginTable("GMCP packages", listOf("Package", "Kind", "Doc"), rows)
}

/** The custom hooks a plugin registers a handler for. */
internal fun StringBuilder.pluginHookHandlers(p: PluginDocSpec) {
  if (p.hooks.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.hooks
      .sortedBy { it.name }
      .map { h ->
        "plugin-${p.id}.hook-${h.name}" to listOf("<code>${esc(h.name)}</code> $badge", esc(h.doc))
      }
  pluginTable("Custom hook handlers", listOf("Hook", "Doc"), rows)
}

/** The optional rules exports a plugin declares. */
internal fun StringBuilder.pluginRulesExports(p: PluginDocSpec) {
  if (p.rulesExports.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.rulesExports
      .sortedBy { it.name }
      .map { e ->
        "plugin-${p.id}.rules-${e.name}" to
          listOf("<code>${esc(e.name)}</code> $badge", esc(e.type), esc(e.doc))
      }
  pluginTable("Rules exports", listOf("Export", "Type", "Doc"), rows)
}

/** The traits a plugin adds. */
internal fun StringBuilder.pluginTraits(p: PluginDocSpec) {
  if (p.traits.isEmpty()) return
  val badge = pluginBadge(p.id)
  append("<h3>Traits</h3>\n")
  for (t in p.traits.sortedBy { it.name }) {
    append("<article id=\"plugin-")
      .append(esc(p.id))
      .append(".trait-")
      .append(esc(t.name))
      .append("\">\n")
    append("<h4><code>").append(esc(t.name)).append("</code> ").append(badge).append("</h4>\n")
    append("<p><strong>").append(esc(t.label)).append("</strong></p>\n")
    if (t.doc.isNotEmpty()) doc(t.doc)
    val effects = traitEffects(t)
    if (effects.isNotEmpty()) {
      append("<p>Effects: ").append(esc(effects.joinToString(", "))).append("</p>\n")
    }
    append("</article>\n")
  }
}

/** The `Effects:` line of a trait, its deltas in the reference's order. */
private fun traitEffects(t: PluginTraitDocSpec): List<String> {
  val effects = mutableListOf<String>()
  for ((k, v) in t.statDeltas.toSortedMap()) effects += "$k: ${if (v >= 0) "+$v" else "$v"}"
  for ((k, v) in t.maxVitalDeltas.toSortedMap()) effects += "max $k: ${if (v >= 0) "+$v" else "$v"}"
  for ((k, v) in t.resistDeltas.toSortedMap()) effects +=
    "resist $k: ${if (v >= 0) "+$v" else "$v"}"
  for (f in t.flags.sorted()) effects += "flag: $f"
  for (m in t.modifiers.sorted()) effects += "modifier: $m"
  return effects
}

/** The restrictions a plugin registers, numbered per point. */
internal fun StringBuilder.pluginRestrictions(p: PluginDocSpec) {
  if (p.restrictions.isEmpty()) return
  val badge = pluginBadge(p.id)
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

/** The containment filters a plugin registers. */
internal fun StringBuilder.pluginContainmentFilters(p: PluginDocSpec) {
  if (p.containmentFilters.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.containmentFilters.withIndex().map { (i, f) ->
      "plugin-${p.id}.filter-${i + 1}" to listOf("<code>filter</code> $badge", esc(f.doc))
    }
  pluginTable("Containment filters", listOf("Filter", "Doc"), rows)
}

/** The owned-behaviors adjusters a plugin registers. */
internal fun StringBuilder.pluginOwnedBehaviors(p: PluginDocSpec) {
  if (p.ownedBehaviors.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.ownedBehaviors.withIndex().map { (i, b) ->
      "plugin-${p.id}.owned-behaviors-${i + 1}" to
        listOf("<code>owned_behaviors</code> $badge", esc(b.doc))
    }
  pluginTable("Owned behaviors", listOf("Adjuster", "Doc"), rows)
}

/** The disposition modifiers a plugin registers. */
internal fun StringBuilder.pluginDispositionModifiers(p: PluginDocSpec) {
  if (p.dispositionModifiers.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.dispositionModifiers.withIndex().map { (i, m) ->
      "plugin-${p.id}.disposition-${i + 1}" to listOf("<code>disposition</code> $badge", esc(m.doc))
    }
  pluginTable("Disposition modifiers", listOf("Modifier", "Doc"), rows)
}

/** The faction adjusters a plugin registers. */
internal fun StringBuilder.pluginFactions(p: PluginDocSpec) {
  if (p.factions.isEmpty()) return
  val badge = pluginBadge(p.id)
  val rows =
    p.factions.withIndex().map { (i, f) ->
      "plugin-${p.id}.factions-${i + 1}" to listOf("<code>factions</code> $badge", esc(f.doc))
    }
  pluginTable("Faction adjusters", listOf("Adjuster", "Doc"), rows)
}

/** The character-creation choices a plugin declares. */
internal fun StringBuilder.pluginChoices(p: PluginDocSpec) {
  if (p.choices.isEmpty()) return
  val badge = pluginBadge(p.id)
  append("<h3>Character creation choices</h3>\n")
  for (c in p.choices) {
    append("<article id=\"plugin-")
      .append(esc(p.id))
      .append(".choice-")
      .append(esc(c.id))
      .append("\">\n")
    append("<h4><code>").append(esc(c.id)).append("</code> ").append(badge).append("</h4>\n")
    append("<p><strong>").append(esc(c.title)).append("</strong>")
    if (c.prompt.isNotEmpty()) {
      append(" &mdash; <em>").append(esc(c.prompt)).append("</em>")
    }
    append("</p>\n")
    choiceOptions(p, c)
    append("</article>\n")
  }
}

/** The options of a creation choice. */
internal fun StringBuilder.choiceOptions(p: PluginDocSpec, c: PluginChoiceDocSpec) {
  if (c.options.isEmpty()) return
  val badge = pluginBadge(p.id)
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

/** The character-creation steps a plugin declares. */
internal fun StringBuilder.pluginSteps(p: PluginDocSpec) {
  if (p.steps.isEmpty()) return
  val badge = pluginBadge(p.id)
  append("<h3>Character creation steps</h3>\n")
  for (s in p.steps) {
    append("<article id=\"plugin-")
      .append(esc(p.id))
      .append(".step-")
      .append(esc(s.id))
      .append("\">\n")
    append("<h4><code>").append(esc(s.id)).append("</code> ").append(badge).append("</h4>\n")
    append("<p><strong>").append(esc(s.title)).append("</strong></p>\n")
    append("</article>\n")
  }
}

/** The `feature: <id>` badge of the entry named by [symbol], if a feature owns it. */
internal fun featureBadge(symbol: String): String =
  FeatureOwnership.of(symbol)?.let { badge(it) } ?: ""

/** The `feature: <id>` badge of a requirement. */
internal fun badge(req: FeatureRequirement): String =
  " <span class=\"badge feature\">${req.noun}: ${esc(req.text)}</span>"

/** The `feature: <id>` badge of a feature. */
internal fun badge(feature: Feature): String = badge(FeatureRequirement.of(feature))

internal fun StringBuilder.doc(text: String) {
  if (text.isNotBlank()) append("<p>").append(esc(text)).append("</p>\n")
}

internal fun StringBuilder.methodParams(m: MethodSpec, path: String?) {
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
    if (path != null)
      append(" id=\"").append(esc(DocsRenderer.anchor("$path.${p.name}"))).append('"')
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

internal fun paramName(name: String, varargs: Boolean, kwargs: Boolean) =
  when {
    varargs -> "*$name"
    kwargs -> "**$name"
    else -> name
  }
