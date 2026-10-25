package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrateInfoTest {
    private val model = ScriptApi.model(harness = emptyList())

    private fun entry(title: String, kind: String, symbol: String, replacement: String? = null, snippet: Boolean = false) = buildString {
        append("### ").append(title).append("\n- kind: ").append(kind).append("\n- symbol: ").append(symbol).append('\n')
        replacement?.let { append("- replacement: ").append(it).append('\n') }
        append("\n").append(title).append(".\n\n")
        if (snippet) append("```python before\nold()\n```\n\n```python after\nnew()\n```\n\n")
    }

    private val changelog = Changelog.parse(
        "# Changelog\n\n## api_version 2 (unreleased)\n\n" +
            entry("Renamed act_move", "renamed", "ctx.act_move", "ctx.walk", snippet = true) +
            entry("Removed size", "removed", "room.size") +
            entry("Renamed on_wake", "renamed", "hook:on_wake", "hook:on_rouse") +
            entry("Removed CRAMPED", "removed", "CRAMPED") +
            entry("Changed hp", "signature", "view:actor.hp") +
            entry("Changed to_hit", "signature", "rules:to_hit") +
            entry("To-hit modifiers", "behavior", "modifier:to_hit") +
            entry("Damage rolls", "behavior", "damage") +
            "## api_version 1\n\nInitial (unstable) script API.\n\n" +
            entry("Removed old", "removed", "ctx.act_flee"),
    )

    private val world = WorldDir(
        tempWorld(
            mapOf(
                // `colour` is a keyword today's package() rejects: loading would fail.
                "zones/forest/package.star" to """package(api_version = 1, kind = "zone", title = "Forest", colour = "green")""",
                "zones/forest/mobs.star" to """
                    def wake(ctx):
                        ctx.act_move(ctx.self, "north")

                    def think(c):
                        c.act_move(c.self, "south")
                        if c.self.hp < 3:
                            c.act_flee(c.self)

                    mob("wolf", name = "a wolf", on_wake = wake)
                """,
                "zones/forest/rooms.star" to """
                    room("glade", size = CRAMPED, modifiers = {"to_hit": 1})
                    room("cave")
                """,
                "zones/forest/forest_test.star" to """
                    def test_walk(t):
                        t.eq(CRAMPED, 1)
                """,
                "rules/package.star" to """package(api_version = 1, kind = "rules")""",
                "rules/combat.star" to """
                    def to_hit(ctx):
                        return 10
                """,
                "zones/broken/package.star" to """package(kind = "zone")""",
                "zones/syntax/package.star" to """package(api_version = 1, kind = "zone")""",
                "zones/syntax/x.star" to "def (:",
            ),
        ),
    )

    @Test
    fun `reports each use with its file and line`() {
        val report = MigrateInfo.inspect(world, "//zones/forest", changelog, model, currentApiVersion = 1)
        assertEquals(
            listOf(
                "//zones/forest:forest_test.star:2 CRAMPED",
                "//zones/forest:mobs.star:2 ctx.act_move",
                "//zones/forest:mobs.star:5 ctx.act_move",
                "//zones/forest:mobs.star:6 view:actor.hp",
                "//zones/forest:mobs.star:9 hook:on_wake",
                "//zones/forest:rooms.star:1 CRAMPED",
                "//zones/forest:rooms.star:1 modifier:to_hit",
                "//zones/forest:rooms.star:1 room.size",
            ),
            report.uses.map { "${it.file}:${it.line} ${it.entry.symbol}" },
        )
        assertEquals(listOf("damage"), report.generalChanges.map { it.entry.symbol })
        val text = report.renderText()
        assertContains(
            text,
            """
            |//zones/forest (package api_version 1, current api_version 1)
            |  //zones/forest:forest_test.star:2: t.eq(CRAMPED, 1)
            |    [removed] CRAMPED (api_version 2, unreleased)
            |    Removed CRAMPED.
            |  //zones/forest:mobs.star:2: ctx.act_move(ctx.self, "north")
            |    [renamed] ctx.act_move -> ctx.walk (api_version 2, unreleased)
            |    Renamed act_move.
            |    before:
            |      old()
            |    after:
            |      new()
            |
            """.trimMargin(),
        )
        assertContains(text, "  General changes (check by hand):\n    [behavior] damage (api_version 2, unreleased): Damage rolls.\n")
        val json = report.toJson()
        assertContains(
            json,
            """
            |    {
            |      "file": "//zones/forest:mobs.star",
            |      "line": 2,
            |      "source": "ctx.act_move(ctx.self, \"north\")",
            |      "api_version": 2,
            |      "unreleased": true,
            |      "title": "Renamed act_move",
            |      "kind": "renamed",
            |      "symbol": "ctx.act_move",
            |      "replacement": "ctx.walk",
            |      "summary": "Renamed act_move.",
            |      "snippets": [
            |        {
            |          "before": "old()",
            |          "after": "new()"
            |        }
            |      ]
            |    },
            """.trimMargin(),
        )
        assertContains(json, "\"package\": \"//zones/forest\",\n  \"package_api_version\": 1,\n  \"current_api_version\": 1,")
    }

    @Test
    fun `rules functions match only in a rules package, and positional parameters match`() {
        val rules = MigrateInfo.inspect(world, "rules", changelog, model, currentApiVersion = 1)
        assertEquals(listOf("//rules:combat.star:1 rules:to_hit"), rules.uses.map { "${it.file}:${it.line} ${it.entry.symbol}" })
        val byId = Changelog.parse("## api_version 2 (unreleased)\n\n" + entry("Ids", "signature", "room.id") + "## api_version 1\n\nx\n")
        val uses = MigrateInfo.inspect(world, "zones/forest", byId, model, currentApiVersion = 1).uses
        assertEquals(listOf(1, 2), uses.map { it.line })
    }

    @Test
    fun `older sections are not relevant, and bad packages are errors`() {
        val v2 = Changelog.parse("## api_version 2\n\n" + entry("Removed size", "removed", "room.size") + "## api_version 1\n\nx\n")
        val withV2 = world.root.resolve("zones/forest/package.star")
        java.nio.file.Files.writeString(withV2, "package(api_version = 2, kind = \"zone\")\n")
        val report = MigrateInfo.inspect(world, "zones/forest", v2, model, currentApiVersion = 2)
        assertEquals(emptyList(), report.uses)
        assertContains(report.renderText(), "No uses of changed symbols.")
        assertContains(assertFailsWith<MigrateInfoException> { MigrateInfo.inspect(world, "zones/broken", v2, model) }.message!!, "no integer literal api_version")
        assertContains(assertFailsWith<MigrateInfoException> { MigrateInfo.inspect(world, "zones/syntax", v2, model) }.message!!, "//zones/syntax:x.star:1: syntax error")
        assertFailsWith<IllegalArgumentException> { MigrateInfo.inspect(world, "zones/none", v2, model) }
    }
}
