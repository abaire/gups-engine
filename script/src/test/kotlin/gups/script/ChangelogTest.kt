package gups.script

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangelogTest {
    private val docs = File(System.getProperty("gups.docsDir"))

    private val example1 = """
        # Changelog

        ## api_version 2 (unreleased)

        ### Renamed `ctx.act_move` to `ctx.walk`
        - kind: renamed
        - symbol: ctx.act_move
        - replacement: ctx.walk

        Mobs now walk with `ctx.walk(mob, dir)`.

        ```python before
        ctx.act_move(ctx.self, "north")
        ```

        ```python after
        ctx.walk(ctx.self, "north")
        ```

        ### Added `room.lighting`
        - kind: added
        - symbol: room.lighting

        Rooms may now declare a `lighting` level.

        ## api_version 1

        Initial (unstable) script API.
    """.trimIndent() + "\n"

    private fun error(md: String): ChangelogParseException = assertFailsWith<ChangelogParseException> { Changelog.parse(md.trimIndent() + "\n") }

    @Test
    fun `the real changelog parses, and the first changelog's json matches the spec example`() {
        val real = Changelog.parse(docs.resolve("CHANGELOG.md").readText())
        assertEquals(1, real.versions.last().apiVersion)
        assertEquals(docs.resolve("MIGRATING.md").readText(), real.toMigratingMarkdown())
        val changelog = Changelog.parse("# Changelog\n\n## api_version 1\n\nInitial (unstable) script API.\n")
        assertEquals(
            """
            {
              "api_version": 1,
              "versions": [
                {
                  "api_version": 1,
                  "unreleased": false,
                  "intro": "Initial (unstable) script API.",
                  "entries": []
                }
              ]
            }
            """.trimIndent() + "\n",
            changelog.toJson(1),
        )
    }

    @Test
    fun `every kind, an unreleased section, intro prose, multi-line summaries and two snippet pairs parse`() {
        val md = """
            # Changelog

            Preamble prose is ignored.

            ## api_version 3 (unreleased)

            Intro line one.
            Intro line two.

            ### Removed `room.size`
            - kind: removed
            - symbol: room.size

            Rooms no longer take a size.
            Use `modifiers` instead.

            ```python before
            room(id = "a", size = CRAMPED)
            ```

            ```python after
            room(id = "a")
            ```

            ```python before
            x = CRAMPED
            y = 1
            ```

            ```python after
            x = None
            ```

            ### Renamed hook
            - kind: renamed
            - symbol: hook:on_wake
            - replacement: hook:on_rouse

            Renamed.

            ### Changed `damage`
            - kind: signature
            - symbol: damage
            - replacement: hurt

            New signature.

            ### Changed `to_hit`
            - symbol: modifier:to_hit
            - kind: behavior

            Applies after rolls.

            ## api_version 2

            ### Added `ctx.walk`
            - kind: added
            - symbol: ctx.walk

            New verb.

            ## api_version 1

            Initial (unstable) script API.
        """.trimIndent() + "\n"
        val c = Changelog.parse(md)
        assertEquals(listOf(3, 2, 1), c.versions.map { it.apiVersion })
        assertEquals(listOf(true, false, false), c.versions.map { it.unreleased })
        assertEquals("Intro line one.\nIntro line two.", c.versions[0].intro)
        assertEquals(5, c.versions[0].line)
        assertEquals(
            ChangelogEntry(
                title = "Removed `room.size`",
                kind = ChangeKind.REMOVED,
                symbol = "room.size",
                replacement = null,
                summary = "Rooms no longer take a size.\nUse `modifiers` instead.",
                snippets = listOf(
                    Snippet("room(id = \"a\", size = CRAMPED)", "room(id = \"a\")"),
                    Snippet("x = CRAMPED\ny = 1", "x = None"),
                ),
                line = 10,
            ),
            c.versions[0].entries[0],
        )
        assertEquals(
            listOf(ChangeKind.REMOVED, ChangeKind.RENAMED, ChangeKind.SIGNATURE, ChangeKind.BEHAVIOR),
            c.versions[0].entries.map { it.kind },
        )
        assertEquals("hook:on_rouse", c.versions[0].entries[1].replacement)
        assertEquals("hurt", c.versions[0].entries[2].replacement)
        assertEquals(ChangelogEntry("Added `ctx.walk`", ChangeKind.ADDED, "ctx.walk", null, "New verb.", emptyList(), 56), c.versions[1].entries.single())
        assertEquals("", c.versions[1].intro)
    }

    @Test
    fun `migrating markdown of example 1`() {
        assertEquals(
            """
            # Migrating Between api_versions

            Breaking script-facing changes per `api_version`, with before/after snippets.

            ## api_version 2 (unreleased)

            ### Renamed `ctx.act_move` to `ctx.walk`
            - **kind:** `renamed`
            - **symbol:** `ctx.act_move`
            - **replacement:** `ctx.walk`

            Mobs now walk with `ctx.walk(mob, dir)`.

            ```python before
            ctx.act_move(ctx.self, "north")
            ```

            ```python after
            ctx.walk(ctx.self, "north")
            ```
            """.trimIndent() + "\n",
            Changelog.parse(example1).toMigratingMarkdown(),
        )
    }

    @Test
    fun `example 2 errors`() {
        val asWritten = """
            # Changelog

            ## api_version 2 (unreleased)

            ### Renamed `ctx.act_move` to `ctx.walk`
            - kind: renamed
            - symbol: ctx.act_move

            Mobs now walk with `ctx.walk(mob, dir)`.

            ```python before
            ctx.act_move(ctx.self, "north")
            ```
        """
        assertEquals(
            "CHANGELOG.md:5: entry 'Renamed `ctx.act_move` to `ctx.walk`' has kind 'renamed' but no '- replacement: <symbol>'",
            error(asWritten).message,
        )
        val fixed = asWritten.replace("- symbol: ctx.act_move\n", "- symbol: ctx.act_move\n            - replacement: ctx.walk\n")
        val e = error(fixed)
        assertEquals("CHANGELOG.md:12: a 'python before' block must be followed by a 'python after' block", e.message)
        assertEquals(12, e.line)
    }

    private fun entry(meta: String, body: String = "Summary.") =
        "# Changelog\n\n## api_version 2 (unreleased)\n\n### T\n$meta\n\n$body\n\n## api_version 1\n\nInitial.\n"

    @Test
    fun `each grammar error is reported at its line`() {
        fun at(line: Int, md: String, reason: String) {
            val e = assertFailsWith<ChangelogParseException> { Changelog.parse(md) }
            assertEquals(line, e.line, e.message)
            assertEquals(true, reason in e.reason, "${e.reason} lacks '$reason'")
        }
        at(3, "# Changelog\n\n### Early\n", "must follow a '## api_version N'")
        at(3, "# Changelog\n\n- kind: added\n\n## api_version 1\n\nx\n", "must follow an entry")
        at(1, "## api_version 01\n", "version heading must be")
        at(1, "## Version 1\n", "version heading must be")
        at(3, "## api_version 1\n\n## api_version 2\n", "must be lower than")
        at(3, "## api_version 2\n\n## api_version 2\n", "must be lower than")
        at(3, "## api_version 3\n\n## api_version 2 (unreleased)\n", "only the first")
        at(3, "## api_version 1\n\n###   \n", "needs a title")
        at(8, entry("- kind: renamed\n- symbol: ctx.a\n- colour: red"), "unknown key 'colour'")
        at(8, entry("- kind: added\n- symbol: ctx.a\n- kind: added"), "gives 'kind' twice")
        at(6, entry("- kind: moved"), "has kind 'moved'")
        at(7, entry("- kind: added\n- symbol: 9lives"), "not a valid symbol")
        at(8, entry("- kind: removed\n- symbol: ctx.a\n- replacement: ctx.b c"), "not a valid symbol")
        at(5, entry("- symbol: ctx.a"), "no '- kind")
        at(5, entry("- kind: added"), "no '- symbol")
        at(8, entry("- kind: renamed\n- symbol: ctx.a\n- replacement: ctx.a"), "renames 'ctx.a' to itself")
        at(8, entry("- kind: added\n- symbol: ctx.a\n- replacement: ctx.b"), "takes no replacement")
        at(5, entry("- kind: added\n- symbol: ctx.a", body = ""), "has no summary")
        at(11, entry("- kind: added\n- symbol: ctx.a", body = "S.\n\n```python before\nx"), "never closed")
        at(11, entry("- kind: added\n- symbol: ctx.a", body = "S.\n\n```starlark\nx\n```"), "not 'starlark'")
        at(11, entry("- kind: added\n- symbol: ctx.a", body = "S.\n\n```python after\nx\n```"), "must follow a 'python before'")
        at(11, entry("- kind: added\n- symbol: ctx.a", body = "S.\n\n```python before\nx\n```\n\n```python before\ny\n```"), "must be followed by a 'python after'")
        at(19, entry("- kind: added\n- symbol: ctx.a", body = "S.\n\n```python before\nx\n```\n\n```python after\ny\n```\n\nMore prose."), "prose after its first snippet")
        at(5, "## api_version 1\n\nIntro.\n\n```python before\nx\n```\n", "must belong to an entry")
    }

    @Test
    fun `an empty changelog is an error`() {
        assertEquals(1, error("# Changelog").line)
    }
}
