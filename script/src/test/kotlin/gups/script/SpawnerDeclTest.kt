package gups.script

import gups.core.Label
import gups.core.model.Mob
import gups.core.model.Respawn
import gups.core.model.Roll
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpawnerDeclTest {
  private fun load(
    files: Map<String, String>,
    vararg pkgs: String = arrayOf("zones/z"),
  ): LoadResult = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  private fun only(r: LoadResult, pkg: String = "zones/z"): LoadError {
    val errs = r.errors.filter { it.pkg == pkg }
    assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
    return errs.first()
  }

  @Test
  fun `spawner outside a zone package is a load error`() {
    val r =
      load(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to
            """
            mob("rat")
            room("r")
            spawner("s", ":rat", rooms = [":r"])
            """,
        ),
        "lib",
      )
    assertContains(only(r, "lib").message, "spawner(): only zone packages declare spawners")
  }

  private fun expectZoneError(star: String, message: String) {
    val r = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to star))
    assertContains(only(r).message, message)
  }

  @Test
  fun `spawner rejects empty rooms list or dict`() {
    expectZoneError("mob(\"rat\")\nspawner(\"s\", \":rat\", rooms = [])", "spawner(rooms):")
    expectZoneError("mob(\"rat\")\nspawner(\"s\", \":rat\", rooms = {})", "spawner(rooms):")
  }

  @Test
  fun `spawner rejects duplicate room label`() {
    expectZoneError(
      "mob(\"rat\")\nroom(\"r\")\nspawner(\"s\", \":rat\", rooms = [\":r\", \":r\"])",
      "spawner(rooms): //zones/z:r is listed twice",
    )
  }

  @Test
  fun `spawner rejects unresolved room label`() {
    expectZoneError(
      "mob(\"rat\")\nspawner(\"s\", \":rat\", rooms = [\":missing\"])",
      "//zones/z:missing",
    )
  }

  @Test
  fun `spawner id clashes with spawn id in the same package`() {
    expectZoneError(
      """
      mob("rat")
      room("r", spawns = [spawn(":rat", id = "shared")])
      spawner("shared", ":rat", rooms = [":r"])
      """,
      "//zones/z:shared is already a spawn rule id",
    )
  }

  @Test
  fun `spawner loads rooms weights and options into SpawnerProto`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            item("rare_sword", name = "a rare sword")
            room("vault")
            room("hollow")
            spawner("rare_sword", ":rare_sword",
                    rooms = {":vault": 1, ":hollow": 3},
                    max = 2,
                    batch = (1, 2),
                    respawn = (100, 200))
            """,
        )
      )
    assertTrue(r.ok, r.report())
    val proto = r.prototypes().spawners.single()
    assertEquals(Label("//zones/z:rare_sword"), proto.id)
    assertEquals(Label("//zones/z:rare_sword"), proto.rule.label)
    assertEquals(Label("//zones/z:rare_sword"), proto.rule.id)
    assertEquals(2, proto.rule.max)
    assertEquals(Roll(1, 2), proto.rule.batch)
    assertEquals(Respawn.Timed(Roll(100, 200)), proto.rule.respawn)
    assertEquals(
      linkedMapOf(Label("//zones/z:vault") to 1, Label("//zones/z:hollow") to 3),
      proto.rooms,
    )
  }

  @Test
  fun `spawner pick chooses candidate, returns None to roll, or reports bad answer and rolls`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def _pick_b(ctx):
                return ":b"

            def _pick_none(ctx):
                return None

            def _pick_bad(ctx):
                return ":nowhere"

            mob("m_chosen", name = "chosen")
            mob("m_rolled", name = "rolled")
            mob("m_bad", name = "bad")
            room("a")
            room("b")
            spawner("s_chosen", ":m_chosen", rooms = [":a", ":b"], pick = _pick_b)
            spawner("s_rolled", ":m_rolled", rooms = [":a", ":b"], pick = _pick_none)
            spawner("s_bad", ":m_bad", rooms = [":a", ":b"], pick = _pick_bad)
            """,
        )
      )
    val candidates = setOf(Label("//zones/z:a"), Label("//zones/z:b"))
    val mobs = f.w.actors.filterIsInstance<Mob>()

    val chosen = mobs.single { it.protoLabel == Label("//zones/z:m_chosen") }
    assertEquals(Label("//zones/z:b"), chosen.room.label)

    val rolled = mobs.single { it.protoLabel == Label("//zones/z:m_rolled") }
    assertTrue(rolled.room.label in candidates, rolled.room.label.v)

    val bad = mobs.single { it.protoLabel == Label("//zones/z:m_bad") }
    assertTrue(bad.room.label in candidates, bad.room.label.v)

    val errs = f.diagnostics("script_error")
    assertEquals(1, errs.size, errs.toString())
    assertContains(errs.single().message, "not one of the spawner's rooms")
  }
}
