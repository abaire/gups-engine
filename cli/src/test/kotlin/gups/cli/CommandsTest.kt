package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.core.EngineInfo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandsTest {
    @Test
    fun `version reports engine and api versions`() {
        val result = gupsCommand().test("--version")
        assertEquals(0, result.statusCode)
        assertEquals("gups ${EngineInfo.version} (api_version 1)", result.stdout.trim())
    }

    @Test
    fun `help lists every subcommand`() {
        val result = gupsCommand().test("--help")
        assertEquals(0, result.statusCode)
        for (name in listOf(
            "serve", "test", "lint", "docs", "repro", "backup", "restore", "deploy", "invite", "import-roster",
        )) {
            assertContains(result.stdout, name)
        }
    }

    @Test
    fun `stub commands exit with not implemented`() {
        val result = gupsCommand().test("lint //zones/...")
        assertEquals(EXIT_NOT_IMPLEMENTED, result.statusCode)
        assertContains(result.stderr, "not implemented yet")
    }

    @Test
    fun `serve requires exactly one profile`() {
        assertEquals(1, gupsCommand().test("serve").statusCode)
        assertEquals(1, gupsCommand().test("serve --dev --prod").statusCode)
    }

    @Test
    fun `test runs the tutorial zone`() {
        val world = System.getProperty("gups.templateWorld")
        val result = gupsCommand().test(listOf("test", "--world", world, "//zones/tutorial/..."))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "//zones/tutorial:tutorial_test.star")
        assertContains(result.stdout, "PASS  test_the_guide_blocks_the_arena_until_ready")
        assertContains(result.stdout, "0 failed, 0 errors")
    }

    @Test
    fun `test fails when nothing matches`() {
        val world = System.getProperty("gups.templateWorld")
        val result = gupsCommand().test(listOf("test", "--world", world, "//zones/nowhere"))
        assertEquals(EXIT_TESTS_FAILED, result.statusCode)
        assertContains(result.stderr, "no package matches //zones/nowhere")
    }

    @Test
    fun `deploy requires a commit`() {
        assertEquals(1, gupsCommand().test("deploy").statusCode)
        assertEquals(EXIT_NOT_IMPLEMENTED, gupsCommand().test("deploy --commit abc123").statusCode)
    }

    @Test
    fun `invite and import-roster work on the world's database`() {
        val root = java.nio.file.Files.createTempDirectory("gups-accounts")
        val roster = root.resolve("roster.csv").toFile().apply { writeText("login,role\nann,builder\nbob,\n") }
        val first = gupsCommand().test(listOf("import-roster", roster.path, "--world", root.toString()))
        assertEquals(0, first.statusCode, first.output)
        val lines = first.stdout.trim().lines()
        assertEquals("login,setup_code", lines[0])
        assertEquals(listOf("ann", "bob"), lines.drop(1).map { it.substringBefore(',') })
        assertContains(first.stderr, "2 accounts created, 0 updated, 0 unchanged")
        val again = gupsCommand().test(listOf("import-roster", roster.path, "--world", root.toString()))
        assertEquals("login,setup_code", again.stdout.trim())
        assertContains(again.stderr, "0 accounts created, 0 updated, 2 unchanged")

        roster.writeText("login\nX\n")
        val bad = gupsCommand().test(listOf("import-roster", roster.path, "--world", root.toString()))
        assertEquals(1, bad.statusCode)
        assertContains(bad.stderr, "line 2: 'x'")

        val invite = gupsCommand().test(listOf("invite", "--role", "Builder", "--days", "3", "--world", root.toString()))
        assertEquals(0, invite.statusCode, invite.output)
        assertTrue(Regex("[A-Z0-9]{5}-[A-Z0-9]{5}").matches(invite.stdout.trim()), invite.stdout)
        assertEquals(1, gupsCommand().test(listOf("invite", "--role", "wizard", "--world", root.toString())).statusCode)
    }

    @Test
    fun `serve --prod checks its flags and configuration`() {
        val world = System.getProperty("gups.templateWorld")
        val noTelnet = gupsCommand().test(listOf("serve", "--prod", "--world", world))
        assertEquals(1, noTelnet.statusCode)
        assertContains(noTelnet.stderr, "--telnet :4000")
        assertContains(gupsCommand().test(listOf("serve", "--prod", "--port", "4000")).stderr, "--port is for --dev")
        assertContains(gupsCommand().test(listOf("serve", "--prod", "--world", world, "--telnet", ":0", "--self-signed")).stderr, "--self-signed needs --telnet-tls")
        assertContains(gupsCommand().test(listOf("serve", "--prod", "--world", world, "--telnet-tls", ":0")).stderr, "needs --tls-cert and --tls-key")
        assertContains(gupsCommand().test(listOf("serve", "--dev", "--telnet", ":4000")).stderr, "are for --prod")
        val cfg = java.nio.file.Files.createTempFile("server", ".gups").toFile().apply { writeText("server(telnet = \":4000\", colour = 1)\n") }
        assertContains(gupsCommand().test(listOf("serve", "--prod", "--config", cfg.path)).stderr, "unexpected argument 'colour'")
        assertEquals("0.0.0.0" to 4000, parseAddress(":4000"))
        assertEquals("::1" to 4001, parseAddress("[::1]:4001"))
        assertEquals("127.0.0.1" to 0, parseAddress("127.0.0.1:0"))
    }
}
