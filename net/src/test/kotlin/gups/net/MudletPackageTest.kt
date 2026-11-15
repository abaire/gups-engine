package gups.net

import java.io.ByteArrayInputStream
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MudletPackageTest {
  private val sample = "-- a < b & c > d \"e\"\nprint(1)\n".toByteArray()

  private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break
        entries += entry.name to zis.readBytes()
        zis.closeEntry()
      }
    }
    return entries
  }

  @Test
  fun `zip has config lua and gups-mapper xml in order`() {
    val pkg = MudletPackage(sample)
    val entries = unzip(pkg.bytes)
    assertEquals(listOf("config.lua", "gups-mapper.xml"), entries.map { it.first })

    val config = String(entries[0].second)
    assertContains(config, "mpackage = [[gups-mapper]]")
    assertContains(config, "version = [[${pkg.version}]]")

    val doc =
      DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(entries[1].second))
    val scriptNode = doc.getElementsByTagName("script").item(0)
    assertEquals(String(sample), scriptNode.textContent)
  }

  @Test
  fun `version is 16 lowercase hex chars and changes when script changes`() {
    val pkg = MudletPackage(sample)
    assertTrue(pkg.version.matches(Regex("[0-9a-f]{16}")), "version was ${pkg.version}")

    val altered = sample.copyOf().also { it[0] = 'x'.code.toByte() }
    assertNotEquals(pkg.version, MudletPackage(altered).version)
  }

  @Test
  fun `building twice gives identical bytes across timezones`() {
    val original = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      val first = MudletPackage(sample)
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
      val second = MudletPackage(sample)
      assertEquals(first.version, second.version)
      assertContentEquals(first.bytes, second.bytes)
    } finally {
      TimeZone.setDefault(original)
    }
  }

  @Test
  fun `fromClasspath returns null when mapper lua is absent`() {
    val emptyLoader = object : ClassLoader(null) {}
    assertNull(MudletPackage.fromClasspath(emptyLoader))
  }
}
