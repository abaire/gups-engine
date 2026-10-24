package gups.harness

import gups.script.WorldDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** The tutorial zone's tests are worked examples and must pass on every engine commit (SPEC §7.2). */
class TutorialTest {
    @Test
    fun `the tutorial zone's tests pass`() {
        val world = WorldDir(Path.of(System.getProperty("gups.templateWorld")))
        val report = TestRunner(world).run(listOf("//zones/tutorial/..."))
        val text = report.results.joinToString("\n") { it.render() } + "\n" + report.problems.joinToString("\n")
        assertTrue(report.ok, text)
        assertTrue(report.passed >= 7, text)
    }
}
