package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.core.EngineInfo
import gups.script.testing.TestWorlds
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DescribeWorldCommandTest {
  private fun golden(name: String, world: String) {
    val result = gupsCommand().test(listOf("describe-world", "--world", world))
    assertEquals(0, result.statusCode, result.output)
    val actual =
      result.stdout.replace(
        "\"engine_version\": \"${EngineInfo.version}\"",
        "\"engine_version\": \"\"",
      )
    val file = File(System.getProperty("gups.describeWorldGolden")).resolve("$name.golden.json")
    if (System.getProperty("gups.updateGolden") == "true")
      file.also { it.parentFile.mkdirs() }.writeText(actual)
    assertTrue(file.isFile, "missing $file; run with -Dgups.updateGolden=true to create it")
    assertEquals(
      file.readText(),
      actual,
      "$name changed; if that is intended, run with -Dgups.updateGolden=true",
    )
  }

  @Test
  fun `template world matches golden`() =
    golden("template-world", System.getProperty("gups.templateWorld"))

  @Test
  fun `robot world matches golden`() = golden("robot-world", System.getProperty("gups.robotWorld"))

  private fun smallWorld(extra: Map<String, String> = emptyMap()) =
    tempWorld(
      mapOf(
        "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")\n",
        "zones/a/rooms.star" to "room(\"hall\", name = \"The Hall\")\n",
      ) + extra
    )

  @Test
  fun `stdout holds only json`() {
    val result = gupsCommand().test(listOf("describe-world", "--world", smallWorld().toString()))
    assertEquals(0, result.statusCode, result.output)
    val doc = Json.parseToJsonElement(result.stdout).jsonObject
    assertEquals(1, doc.getValue("format").jsonPrimitive.int)
    assertEquals(JsonArray(emptyList()), doc.getValue("errors"))
    assertEquals("", result.stderr)
  }

  @Test
  fun `out writes the file and creates directories`() {
    val world = smallWorld()
    val out = tempWorld(emptyMap()).resolve("deep/er/world.json")
    val result =
      gupsCommand().test(listOf("describe-world", "--world", world.toString(), "--out", "$out"))
    assertEquals(0, result.statusCode, result.output)
    assertEquals("", result.stdout)
    assertTrue(Json.parseToJsonElement(out.toFile().readText()).jsonObject.containsKey("rooms"))
  }

  @Test
  fun `out that is a directory is a usage error`() {
    val dir = tempWorld(emptyMap())
    val result =
      gupsCommand()
        .test(listOf("describe-world", "--world", smallWorld().toString(), "--out", "$dir"))
    assertEquals(1, result.statusCode)
    assertTrue(result.stderr.contains("is a directory"), result.stderr)
  }

  @Test
  fun `bad module exits 1`() {
    val world =
      tempWorld(
        mapOf("MODULE.gups" to TestWorlds.MODULE.replace("api_version = 1", "api_version = 99"))
      )
    val result = gupsCommand().test(listOf("describe-world", "--world", world.toString()))
    assertEquals(1, result.statusCode)
    assertEquals("", result.stdout)
    assertTrue(result.stderr.contains("error: "), result.stderr)
  }

  @Test
  fun `broken zone still exits 0`() {
    val world =
      smallWorld(mapOf("zones/b/package.star" to "package(api_version = 1, kind = \"zone\"\n"))
    val result = gupsCommand().test(listOf("describe-world", "--world", world.toString()))
    assertEquals(0, result.statusCode, result.output)
    val doc = Json.parseToJsonElement(result.stdout).jsonObject
    val packages =
      doc.getValue("packages").jsonArray.associate {
        it.jsonObject.getValue("path").jsonPrimitive.content to it.jsonObject
      }
    assertEquals(true, packages.getValue("zones/a").getValue("ok").jsonPrimitive.boolean)
    assertEquals(false, packages.getValue("zones/b").getValue("ok").jsonPrimitive.boolean)
    assertEquals(JsonNull, packages.getValue("zones/b").getValue("kind"))
    assertEquals(
      listOf("//zones/a:hall"),
      doc.getValue("rooms").jsonArray.map { it.jsonObject.getValue("label").jsonPrimitive.content },
    )
    val errors = doc.getValue("errors").jsonArray
    assertTrue(errors.isNotEmpty())
    assertTrue(
      errors.all { it.jsonObject.getValue("package").jsonPrimitive.content == "zones/b" },
      errors.toString(),
    )
  }
}
