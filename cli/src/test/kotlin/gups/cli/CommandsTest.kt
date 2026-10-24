package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.core.EngineInfo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
        val result = gupsCommand().test("serve --dev --fresh")
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
}
