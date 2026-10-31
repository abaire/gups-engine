package gups.harness

import gups.script.DocExample
import gups.script.DocExamples
import gups.script.WorldDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every example of the HTML reference as a harness test. */
class DocExamplesTest {
  @TestFactory
  fun `every doc example passes`(): List<DynamicTest> =
    DocExamples.ALL.map { (symbol, example) ->
      DynamicTest.dynamicTest(symbol) { run(symbol, example) }
    }

  private fun run(symbol: String, example: DocExample) {
    val root: Path = Files.createTempDirectory("gups-doc-example")
    try {
      File(System.getProperty("gups.templateWorld"))
        .resolve("rules")
        .copyRecursively(root.resolve("rules").toFile())
      val files =
        linkedMapOf(
          "MODULE.gups" to
            File(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText().let {
              if (example.module.isBlank()) it
              else it.substringBeforeLast(")") + "    ${example.module},\n)\n"
            },
          "zones/ex/package.star" to
            "package(api_version = 1, kind = \"${if (symbol in DocExamples.LIB_SYMBOLS) "lib" else "zone"}\")\n",
          "zones/ex/fixture.star" to DocExamples.FIXTURE,
          "zones/ex/ex_test.star" to
            "def test_example(t):\n" +
              (example.test.ifBlank { "t.world(seed = 1)\n" })
                .lines()
                .filter { it.isNotEmpty() }
                .joinToString("") { "    $it\n" },
        )
      val target =
        DocExamples.FILE_FOR_SYMBOL[symbol]?.let { if (it == "MODULE.gups") it else "zones/ex/$it" }
          ?: "zones/ex/example.star"
      files[target] = example.pkg
      for ((path, text) in files) root
        .resolve(path)
        .also { it.parent.createDirectories() }
        .writeText(text)

      val report = TestRunner(WorldDir(root)).run(listOf("//zones/ex"))
      val details =
        report.results.joinToString("\n") { r ->
          r.render() +
            (r.message?.let { "\n  $it" } ?: "") +
            r.warnings.joinToString("") { "\n  warning: $it" }
        } + report.problems.joinToString("") { "\n$it" }
      assertTrue(
        report.problems.isEmpty() && report.results.isNotEmpty(),
        "example '$symbol' failed:\n$details",
      )
      assertTrue(
        report.results.all { it.status == Status.PASS && it.warnings.isEmpty() },
        "example '$symbol' failed:\n$details",
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
