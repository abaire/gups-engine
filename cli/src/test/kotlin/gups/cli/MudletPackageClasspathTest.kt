package gups.cli

import gups.net.MudletPackage
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class MudletPackageClasspathTest {
  @Test
  fun `fromClasspath loads the bundled mapper script`() {
    val pkg = assertNotNull(MudletPackage.fromClasspath())
    var xml: String? = null
    ZipInputStream(ByteArrayInputStream(pkg.bytes)).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break
        if (entry.name == "gups-mapper.xml") {
          xml = String(zis.readBytes())
        }
        zis.closeEntry()
      }
    }
    assertContains(assertNotNull(xml), "gupsMapper")
  }
}
