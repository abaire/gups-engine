package gups.script

import gups.core.Label
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixtureFilesTest {
  private fun load(
    files: Map<String, String>,
    fixtures: Boolean,
    vararg pkgs: String = arrayOf("zones/z"),
    tests: List<Label> = emptyList(),
  ): LoadResult =
    ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS, fixtures = fixtures)
      .load(pkgs.toList(), tests = tests)

  private val zone =
    mapOf(
      "zones/z/package.star" to zonePkg(),
      "zones/z/rooms.star" to "room(\"start\")\nitem(\"key\")",
      "zones/z/probe_testdata.star" to "item(\"probe\", name = \"a probe\")\nmob(\"dummy\")",
    )

  @Test
  fun `fixture files load only when the loader loads fixtures`() {
    val withFixtures = load(zone, fixtures = true)
    assertTrue(withFixtures.ok, withFixtures.report())
    val p = withFixtures.prototypes()
    assertEquals("a probe", p.items.getValue(Label("//zones/z:probe")).name)
    assertTrue(Label("//zones/z:dummy") in p.mobs)

    val without = load(zone, fixtures = false)
    assertTrue(without.ok, without.report())
    assertFalse(Label("//zones/z:probe") in without.prototypes().items)
    assertFalse(Label("//zones/z:dummy") in without.prototypes().mobs)
    assertTrue(Label("//zones/z:key") in without.prototypes().items)
  }

  @Test
  fun `dependencies load their fixture files too`() {
    val files =
      zone +
        mapOf(
          "zones/a/package.star" to zonePkg(),
          "zones/a/a.star" to "room(\"start\", exits = [exit(\"east\", \"//zones/z:start\")])",
        )
    val r = load(files, fixtures = true, "zones/a")
    assertTrue(r.ok, r.report())
    assertTrue(Label("//zones/z:probe") in r.prototypes().items)
  }

  @Test
  fun `normal content naming a fixture fails a load without fixtures`() {
    val files =
      zone +
        ("zones/z/rooms.star" to "room(\"start\", spawns = [spawn(\":dummy\")])\nitem(\"key\")")
    val withFixtures = load(files, fixtures = true)
    assertTrue(withFixtures.ok, withFixtures.report())

    val without = load(files, fixtures = false)
    val err = without.errors.single { it.pkg == "zones/z" }
    assertEquals("label", err.kind)
    assertContains(
      err.message,
      "//zones/z:dummy doesn't resolve: //zones/z declares no mob named 'dummy'",
    )
    assertContains(err.message, "//zones/z:probe_testdata.star")
    assertContains(err.message, "test fixture")
    assertEquals("//zones/z:rooms.star", err.file)
  }

  @Test
  fun `an unresolved label not declared by a fixture gets no fixture note`() {
    val files = zone + ("zones/z/rooms.star" to "room(\"start\", spawns = [spawn(\":nobody\")])")
    val err = load(files, fixtures = false).errors.single { it.pkg == "zones/z" }
    assertContains(err.message, "declares no mob named 'nobody'")
    assertFalse("fixture" in err.message, err.message)
  }

  @Test
  fun `only test and fixture files may load a fixture file`() {
    val files =
      zone +
        ("zones/z/rooms.star" to "load(\":probe_testdata.star\", \"X\")\nroom(\"start\")") +
        ("zones/z/probe_testdata.star" to "X = 1\nitem(\"probe\")")
    for (fixtures in listOf(true, false)) {
      val r = load(files, fixtures = fixtures)
      val err = r.errors.first { it.pkg == "zones/z" }
      assertContains(
        err.message,
        "test fixture files can only be loaded by test files and other fixture files",
      )
    }

    val chained =
      zone +
        mapOf(
          "zones/z/a_testdata.star" to "load(\":b_testdata.star\", \"N\")\nitem(N)",
          "zones/z/b_testdata.star" to "N = \"chained\"",
          "zones/z/z_test.star" to "load(\":probe_testdata.star\", \"X\")",
          "zones/z/probe_testdata.star" to "X = 1",
        )
    val test = Label("//zones/z:z_test.star")
    val ok = load(chained, fixtures = true, tests = listOf(test))
    assertTrue(ok.ok, ok.report())
    assertTrue(ok.testErrors.isEmpty(), ok.testErrors.toString())
    assertTrue(test in ok.testModules)
    assertTrue(Label("//zones/z:chained") in ok.prototypes().items)
  }

  @Test
  fun `a fixture file is a content file, not a test file`() {
    val dir = WorldDir(tempWorld(zone + ("zones/z/z_test.star" to "def test_x(t):\n    pass")))
    assertEquals(listOf("z_test.star"), dir.files("zones/z", tests = true).map { it.name })
    assertEquals(listOf("rooms.star"), dir.files("zones/z", tests = false).map { it.name })
    assertEquals(
      listOf("probe_testdata.star", "rooms.star"),
      dir.files("zones/z", tests = false, fixtures = true).map { it.name },
    )
  }

  @Test
  fun `a fixture name that clashes with a real declaration fails the package`() {
    val files = zone + ("zones/z/probe_testdata.star" to "item(\"key\")")
    val r = load(files, fixtures = true)
    assertContains(r.errors.first { it.pkg == "zones/z" }.message, "'key' is already declared")
  }
}
