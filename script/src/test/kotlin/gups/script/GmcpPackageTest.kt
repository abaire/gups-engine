package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GmcpPackageTest {
  private fun load(
    files: Map<String, String>,
    vararg pkgs: String = arrayOf("zones/z"),
  ): LoadResult = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  private fun only(r: LoadResult, pkg: String): LoadError {
    val errs = r.errors.filter { it.pkg == pkg }
    assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
    return errs.first()
  }

  @Test
  fun `a lib package declares a GMCP package`() {
    val r =
      load(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to "gmcp_package(\"Shop.Stock\", doc = \"Items for sale\")",
        ),
        "lib",
      )
    assertTrue(r.ok, r.report())
    val decl = r.packages.getValue("lib").gmcpPackages.single()
    assertEquals("Shop.Stock", decl.name)
    assertEquals("Items for sale", decl.doc)
    assertEquals("lib", decl.pkg)
  }

  @Test
  fun `a zone may not declare a GMCP package`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "gmcp_package(\"Shop.Stock\")",
        )
      )
    assertContains(
      only(r, "zones/z").message,
      "only lib and rules packages may declare GMCP packages",
    )
  }

  @Test
  fun `a name needs two parts`() {
    val r =
      load(mapOf("lib/package.star" to libPkg(), "lib/a.star" to "gmcp_package(\"Shop\")"), "lib")
    assertContains(only(r, "lib").message, "needs at least two dot-separated parts")
  }

  @Test
  fun `reserved roots are refused`() {
    for (name in listOf("Gups.Shop", "char.shop")) {
      val r =
        load(
          mapOf("lib/package.star" to libPkg(), "lib/a.star" to "gmcp_package(\"$name\")"),
          "lib",
        )
      assertContains(only(r, "lib").message, "uses the reserved root")
    }
  }

  @Test
  fun `a name may not overlap an engine package`() {
    // Comm.Channel.Text is an engine package; the reserved root Comm is checked first.
    val r =
      load(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to "gmcp_package(\"Comm.Channel.Text.X\")",
        ),
        "lib",
      )
    assertContains(only(r, "lib").message, "reserved root Comm")
  }

  @Test
  fun `two names in one package may not nest`() {
    val r =
      load(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to "gmcp_package(\"Shop.Stock\")\ngmcp_package(\"Shop.Stock.Delta\")",
        ),
        "lib",
      )
    assertContains(only(r, "lib").message, "already declared here")
  }

  @Test
  fun `two packages declaring overlapping GMCP packages both fail`() {
    val r =
      load(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to "gmcp_package(\"Shop.Stock\")",
          "rules/package.star" to
            "package(api_version = 1, kind = \"rules\", title = \"Test rules\")",
          "rules/a.star" to "gmcp_package(\"shop.stock\")",
        ),
        "lib",
        "rules",
      )
    val gmcpErrors = r.errors.filter { it.kind == "gmcp" }
    assertTrue(
      gmcpErrors.any { it.pkg == "lib" && "overlaps" in it.message },
      "expected a gmcp overlap error for lib, got: ${r.report()}",
    )
    assertTrue(
      gmcpErrors.any { it.pkg == "rules" && "overlaps" in it.message },
      "expected a gmcp overlap error for rules, got: ${r.report()}",
    )
  }

  @Test
  fun `a declaration overlapping a plugin package fails the script package`() {
    val r =
      ScriptLoader(
          WorldDir(
            tempWorld(
              mapOf("lib/package.star" to libPkg(), "lib/a.star" to "gmcp_package(\"Shop.Buy\")")
            )
          ),
          ScriptLimits.HARNESS,
          knownGmcp = { setOf("Shop.Buy") },
        )
        .load(listOf("lib"))
    assertContains(only(r, "lib").message, "overlaps the plugin package 'Shop.Buy'")
  }
}
