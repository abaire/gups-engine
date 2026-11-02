package gups.cli

import gups.harness.HarnessApi
import gups.script.DocExample
import gups.script.DocExamples
import gups.script.MethodSpec
import gups.script.ParamSpec
import gups.script.ScriptApi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocsRendererTest {
  private val full = ScriptApi.model(HarnessApi.specs())
  private val docs = DocsRenderer.render(full)

  private val ids = Regex("""\sid="([^"]*)"""")
  private val links = Regex("""\s(?:href|src)="([^"]*)"""")

  private fun idsOf(page: String): Set<String> = ids.findAll(page).map { it.groupValues[1] }.toSet()

  @Test
  fun `render writes every file, deterministically`() {
    assertEquals(DocsRenderer.FILES, docs.keys.toList())
    for ((file, text) in docs) assertTrue(text.isNotBlank(), "$file is blank")
    assertEquals(docs, DocsRenderer.render(full))
    assertEquals(full.toJson(), docs["api.json"])
    for ((file, text) in docs) assertFalse('\r' in text, "$file has a CR")
  }

  @Test
  fun `the changelog page renders free-form Markdown and no changes json`() {
    val page =
      DocsRenderer.render(
          full,
          changelogMarkdown = "# Changelog\n\n## Unreleased\n\n- Rooms can be **dark**.\n",
        )
        .getValue("changelog.html")
    assertContains(page, "<h2 id=\"unreleased\">Unreleased</h2>")
    assertContains(page, "<li>Rooms can be <strong>dark</strong>.</li>")
    assertFalse("changes.json" in DocsRenderer.FILES)
  }

  @Test
  fun `every symbol has an anchor on its page`() {
    for (symbol in full.symbols()) {
      val href = DocsRenderer.symbolHref(full, symbol)
      val page = href.substringBefore('#')
      assertTrue(page in DocsRenderer.FILES, "$symbol -> $href")
      assertContains(
        docs.getValue(page),
        "id=\"${href.substringAfter('#')}\"",
        message = "$symbol -> $href",
      )
    }
    assertEquals("effects.html#damage", DocsRenderer.symbolHref(full, "damage"))
    assertEquals("declarations.html#room", DocsRenderer.symbolHref(full, "room"))
    assertEquals("hooks.html#hook-on_enter", DocsRenderer.symbolHref(full, "hook:on_enter"))
  }

  @Test
  fun `links are relative, resolve, and pages have no scripts`() {
    for ((file, text) in docs) {
      if (!file.endsWith(".html")) continue
      assertFalse("<script" in text, "$file has a <script>")
      assertTrue(text.startsWith("<!DOCTYPE html>\n<html lang=\"en\">\n"), file)
      assertContains(text, "<link rel=\"stylesheet\" href=\"style.css\">")
      assertContains(text, "API Reference (api_version ${full.apiVersion})</title>")
      for (m in links.findAll(text)) {
        val link = m.groupValues[1]
        val target = link.substringBefore('#').ifEmpty { file }
        assertTrue(target in docs, "$file links to $link")
        if ('#' in link) {
          val fragment = link.substringAfter('#')
          assertTrue(
            fragment in idsOf(docs.getValue(target)),
            "$file links to $link, which has no such id",
          )
        }
      }
    }
  }

  @Test
  fun `text from the model is escaped`() {
    val nasty = "<b>Tom & \"Jerry\"</b>"
    val decl = full.declarations.first()
    val model =
      full.copy(
        declarations =
          listOf(
            decl.copy(
              doc = nasty,
              params = decl.params.mapIndexed { i, p -> if (i == 0) p.copy(default = nasty) else p },
            )
          )
      )
    val page =
      DocsRenderer.render(
        model,
        changelogMarkdown = "# $nasty\n\n## api_version 1\n\n$nasty\n",
        examples = emptyMap(),
      )
    val escaped = "&lt;b&gt;Tom &amp; &quot;Jerry&quot;&lt;/b&gt;"
    assertContains(page.getValue("declarations.html"), "<p>$escaped</p>")
    assertContains(page.getValue("declarations.html"), "<code>$escaped</code>")
    assertFalse("<b>Tom" in page.getValue("declarations.html"))
    assertContains(page.getValue("changelog.html"), "<p>$escaped</p>")
    assertFalse("<b>Tom" in page.getValue("changelog.html"))
  }

  @Test
  fun `an example with module arguments shows them as a MODULE gups block`() {
    val page = docs.getValue(DocsRenderer.symbolHref(full, "ctx.level_up").substringBefore('#'))
    val example = page.substringAfter("id=\"ctx.level_up\"").substringBefore("</article>")
    assertContains(example, "<p class=\"caption\">MODULE.gups</p>")
    assertContains(example, "level_mode = &quot;deferred&quot;,\n)")
  }

  @Test
  fun `the bundled changelog has the current version's anchor`() {
    assertContains(docs.getValue("changelog.html"), "id=\"api_version-${full.apiVersion}\"")
    assertContains(docs.getValue("plugins.html"), "No plugins are loaded.")
  }

  @Test
  fun `plugins html starts with the guide, which index links, and its Kotlin matches the sample`() {
    val page = docs.getValue("plugins.html")
    assertTrue(page.indexOf("<section id=\"guide\"") < page.indexOf("This world's plugins"))
    assertContains(page, "<h1 id=\"writing-plugins\">Writing Plugins</h1>")
    assertContains(
      docs.getValue("index.html"),
      "<a href=\"plugins.html#guide\">Writing plugins</a>",
    )
    val docsDir = File(System.getProperty("gups.docsDir"))
    val weatherSource =
      docsDir
        .resolve("../samples/weather-plugin/src/main/kotlin/gups/sample/weather/WeatherPlugin.kt")
        .readText()
    val heritageSource =
      docsDir
        .resolve(
          "../samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt"
        )
        .readText()
    val curseSource =
      docsDir
        .resolve("../samples/curse-plugin/src/main/kotlin/gups/sample/curse/CursePlugin.kt")
        .readText()
    val guide = docsDir.resolve("PLUGINS.md").readText()
    val blocks =
      Regex("```kotlin\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL)
        .findAll(guide)
        .map { it.groupValues[1] }
        .toList()
    val fromSample = blocks.filter { "tasks.jar" !in it && "dependencies {" !in it }
    assertTrue(fromSample.size >= 10, "${fromSample.size} sample blocks")
    val weatherDedented = weatherSource.lines().joinToString("\n") { it.trimStart() }
    val heritageDedented = heritageSource.lines().joinToString("\n") { it.trimStart() }
    val curseDedented = curseSource.lines().joinToString("\n") { it.trimStart() }
    for (b in fromSample) {
      val target = b.lines().joinToString("\n") { it.trimStart() }
      assertTrue(
        target in weatherDedented || target in heritageDedented || target in curseDedented,
        "PLUGINS.md block isn't in WeatherPlugin.kt, HeritagePlugin.kt, or CursePlugin.kt:\n$b",
      )
    }
  }

  @Test
  fun `plugins html renders traits choices and restrictions with anchors`() {
    val heritage =
      PluginDocSpec(
        id = "heritage",
        doc = "Fantasy race and class choices, traits and restrictions.",
        traits =
          listOf(
            PluginTraitDocSpec(
              name = "elf",
              label = "Elf",
              doc = "Nimble and magical.",
              statDeltas = mapOf("dex" to 2),
              maxVitalDeltas = mapOf("mana" to 10),
            )
          ),
        restrictions =
          listOf(
            PluginRestrictionDocSpec("wear", "Mages cannot wear heavy armor."),
            PluginRestrictionDocSpec("wear", "Only monks may wear cloth."),
          ),
        choices =
          listOf(
            PluginChoiceDocSpec(
              id = "race",
              title = "Race",
              prompt = "Choose your race:",
              options =
                listOf(
                  PluginOptionDocSpec(
                    id = "elf",
                    label = "Elf",
                    summary = "Graceful.",
                    help = "Elves receive +2 dex.",
                    traits = listOf("elf"),
                  )
                ),
            )
          ),
      )
    val pages = DocsRenderer.render(full, "", listOf(heritage), emptyMap(), "# Guide\n")
    val html = pages.getValue("plugins.html")
    assertContains(html, "<article id=\"plugin-heritage.trait-elf\">")
    assertContains(html, "<tr id=\"plugin-heritage.restrict-wear-1\">")
    assertContains(html, "<tr id=\"plugin-heritage.restrict-wear-2\">")
    assertContains(html, "<article id=\"plugin-heritage.choice-race\">")
    assertContains(html, "<tr id=\"plugin-heritage.choice-race.elf\">")
  }

  @Test
  fun `plugins html renders containment filters with anchors`() {
    val plugin =
      PluginDocSpec(
        id = "ward",
        doc = "Warding and containment.",
        containmentFilters =
          listOf(
            PluginFilterDocSpec("Blocks cursed items behind warded containers."),
            PluginFilterDocSpec("Blocks heat behind insulated containers."),
          ),
      )
    val pages = DocsRenderer.render(full, "", listOf(plugin), emptyMap(), "# Guide\n")
    val html = pages.getValue("plugins.html")
    assertContains(html, "<h3>Containment filters</h3>")
    assertContains(html, "<tr id=\"plugin-ward.filter-1\">")
    assertContains(html, "Blocks cursed items behind warded containers.")
    assertContains(html, "<tr id=\"plugin-ward.filter-2\">")
    assertContains(html, "Blocks heat behind insulated containers.")
  }

  @Test
  fun `markdown renders CommonMark with raw HTML escaped and links as text`() {
    val html =
      markdownToHtml(
        """
        # Changelog

        ## api_version 2 (unreleased)

        ### Renamed `ctx.act_move` to `ctx.walk`

        Some **bold** text and a [link](https://example.com).
        <i>raw</i>

        - one
        - two `x<y`

        1. first, *emphasised*
        2. second ![logo](https://example.com/logo.png)

        <div onclick="x()">block</div>

        ```starlark
        ctx.walk(ctx.self, "north")  # <here>
        ```

        ### Renamed `ctx.act_move` to `ctx.walk`
        """
          .trimIndent()
      )
    assertEquals(
      """
      <h1 id="changelog">Changelog</h1>
      <h2 id="api_version-2">api_version 2 (unreleased)</h2>
      <h3 id="renamed-ctx-act-move-to-ctx-walk">Renamed <code>ctx.act_move</code> to <code>ctx.walk</code></h3>
      <p>Some <strong>bold</strong> text and a link.
      &lt;i&gt;raw&lt;/i&gt;</p>
      <ul>
      <li>one</li>
      <li>two <code>x&lt;y</code></li>
      </ul>
      <ol>
      <li>first, <em>emphasised</em></li>
      <li>second logo</li>
      </ol>
      <p>&lt;div onclick=&quot;x()&quot;&gt;block&lt;/div&gt;</p>
      <pre><code class="language-starlark">ctx.walk(ctx.self, &quot;north&quot;)  # &lt;here&gt;
      </code></pre>
      <h3 id="renamed-ctx-act-move-to-ctx-walk-2">Renamed <code>ctx.act_move</code> to <code>ctx.walk</code></h3>
      """
        .trimIndent() + "\n",
      html,
    )
  }

  /**
   * A small model's reference byte for byte against `docs-golden/`. After a deliberate change,
   * regenerate it with `./gradlew :cli:test --tests '*DocsRendererTest*' -Dgups.updateGolden=true`.
   */
  @Test
  fun `a small model matches the golden files`() {
    val small =
      full.copy(
        engineVersion = "<engine>",
        declarations = full.declarations.filter { it.name == "exit" },
        settings = full.settings.filter { it.name == "module" },
        constants = full.constants.filter { it.name == "SENSE" },
        hooks = full.hooks.filter { it.name == "on_enter" },
        views = full.views.filter { it.name == "exit" },
        effects = full.effects.filter { it.name == "heal" },
        behaviors = full.behaviors.filter { it.name == "wander" },
        rules =
          full.rules.copy(
            exports = full.rules.exports.take(1),
            modifierPoints = full.rules.modifierPoints.take(1),
          ),
        harness = full.harness.filter { it.name == "t" },
      )
    val changelog =
      """
      # Changelog

      ## api_version 2 (unreleased)

      ### Added `room.lighting`

      Rooms take a `lighting` keyword.

      ### Renamed `exit.dest`

      Exits name their target with `to`.

      ```python
      exit("north", dest = ":hall")
      ```

      ```python
      exit("north", to = ":hall")
      ```

      ## api_version 1

      Initial (unstable) script API.
      """
        .trimIndent() + "\n"
    val plugin =
      PluginDocSpec(
        id = "weather",
        doc = "Weather for outdoor rooms.",
        builtins =
          listOf(
            MethodSpec(
              "forecast",
              "Today's weather.",
              listOf(ParamSpec("zone", positional = true, named = true, doc = "Zone label.")),
            )
          ),
        effects = listOf(MethodSpec("drench", "Soak the target.", emptyList())),
        commands = listOf(PluginCommandDocSpec("weather", "fast", "player", "Shows the weather.")),
        gmcp = listOf(PluginGmcpDocSpec("Weather.Now", "The current weather.")),
      )
    val examples: Map<String, DocExample> = mapOf("heal" to DocExamples.ALL.getValue("heal"))
    val guide = "# Writing Plugins\n\nA plugin is a `.jar`.\n"
    val actual = DocsRenderer.render(small, changelog, listOf(plugin), examples, guide)

    val dir = File(System.getProperty("gups.docsGolden"))
    if (System.getProperty("gups.updateGolden") == "true") {
      dir.deleteRecursively()
      dir.mkdirs()
      for ((file, text) in actual) dir.resolve(file).writeText(text)
    }
    for ((file, text) in actual) {
      val golden = dir.resolve(file)
      assertTrue(golden.isFile, "missing $golden; run with -Dgups.updateGolden=true to create it")
      assertEquals(
        golden.readText(),
        text,
        "$file changed; if that is intended, run with -Dgups.updateGolden=true",
      )
    }
  }
}
