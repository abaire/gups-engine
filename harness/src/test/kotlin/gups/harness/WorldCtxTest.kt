package gups.harness

import gups.core.fixtures.TempFiles
import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class WorldCtxTest {
  private fun world(files: Map<String, String>): WorldDir {
    val root: Path = TempFiles.dir("gups-world-ctx")
    for ((path, text) in TestWorlds.withDefaults(files)) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  private val files =
    mapOf(
      "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/a/rooms.star" to
        """
            room("start", name = "Start")
            room("hall", name = "Hall")
            mob("rat", vitals = vitals(hp = 8))
            item("amulet", name = "an amulet", wear = "neck", max_vital_deltas = vitals(hp = 5))
        """,
      "zones/a/cb_testdata.star" to
        """
            def mark(ctx, n):
                ctx.set_attr(ctx.self, "marked", n)

            def done(ctx):
                ctx.set_attr(ctx.self, "done", 1)

            def tick(ctx):
                ctx.set_attr(ctx.self, "ticked", 1)
        """,
      "zones/b/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/b/rooms.star" to "room(\"vault\", name = \"Vault\")",
    )

  private fun run(testSource: String, pkg: String = "a"): Map<String, TestResult> {
    val report =
      TestRunner(world(files + ("zones/$pkg/ctx_test.star" to testSource)))
        .run(listOf("//zones/$pkg"))
    return report.results.associateBy { it.name }
  }

  private fun passes(testSource: String, pkg: String = "a") {
    val results = run(testSource, pkg)
    for (r in results.values) assertEquals(Status.PASS, r.status, r.render())
    assertEquals(true, results.isNotEmpty())
  }

  private fun errorOf(testSource: String, pkg: String = "a"): String {
    val r = run(testSource, pkg).values.single()
    assertEquals(Status.ERROR, r.status, r.render())
    return r.message!!
  }

  @Test
  fun `the default self is the first room by label`() {
    passes(
      """
          def test_default(t):
              w = t.world()
              c = w.ctx()
              t.eq(c.self.label, "//zones/a:hall")
              t.eq(c.actor, None)
              t.eq(c.target, None)
              t.eq(c.item, None)
      """
    )
  }

  @Test
  fun `self is an entity or a label`() {
    passes(
      """
          def test_self(t):
              w = t.world()
              rat = w.spawn(":rat", at = ":start")
              t.eq(w.ctx(self = rat).self.label, rat.label)
              t.eq(w.ctx(self = w.room(":start")).self.label, "//zones/a:start")
              t.eq(w.ctx(self = ":start").self.label, "//zones/a:start")
      """
    )
  }

  @Test
  fun `a self that isn't an entity or a room is an error`() {
    assertContains(
      errorOf("def test_bad(t):\n    t.world().ctx(self = 3)"),
      "ctx(self): expected an entity or a label",
    )
    assertContains(
      errorOf("def test_bad(t):\n    t.world().ctx(self = \":nowhere\")"),
      "ctx(self): there is no room :nowhere",
    )
  }

  @Test
  fun `labels resolve relative to the test's package`() {
    passes(
      """
          def test_labels(t):
              w = t.world()
              p = w.spawn_player("Pat", at = ":vault")
              t.eq(w.ctx().self.label, "//zones/b:vault")
              w.ctx().teleport(p, ":vault")
              t.eq(p.room, ":vault")
      """,
      pkg = "b",
    )
  }

  @Test
  fun `a ctx sets up state at once and stays usable across pulses`() {
    passes(
      """
          def test_setup(t):
              w = t.world()
              p = w.spawn_player("Pat", at = ":start")
              c = w.ctx()
              t.eq(p.max_hp, 20)
              c.wear(p, c.grant_item(p, ":amulet"))
              t.eq(p.max_hp, 25)
              t.eq(c.teleport(p, ":hall"), None)
              t.eq(p.room, ":hall")
              w.run(pulses = 3)
              c.teleport(p, ":start")
              t.eq(p.room, ":start")
              t.eq(c.self.label, "//zones/a:hall")
      """
    )
  }

  @Test
  fun `an affect applied from a ctx is sourced from its self`() {
    passes(
      """
          def test_source(t):
              w = t.world()
              rat = w.spawn(":rat", at = ":start")
              p = w.spawn_player("Pat", at = ":start")
              w.ctx(self = rat).apply_affect(p, "marked", 10, flags = ["no_regen"])
              t.eq(p.affects[0].source.label, rat.label)
      """
    )
  }

  @Test
  fun `mob actions only queue until pulses run`() {
    passes(
      """
          def test_queue(t):
              w = t.world(seed = 1)
              rat = w.spawn(":rat", at = ":start")
              c = w.ctx(self = rat)
              t.true(c.act(rat, "wear amulet"))
              t.eq(rat.equipped("neck"), None)
              c.grant_item(rat, ":amulet")
              w.run(pulses = 4)
              t.eq(rat.equipped("neck").label, "//zones/a:amulet")
      """
    )
  }

  @Test
  fun `deny is an error`() {
    assertContains(errorOf("def test_deny(t):\n    t.world().ctx().deny(\"no\")"), "deny()")
  }

  @Test
  fun `a content function can be called later by after`() {
    passes(
      """
          load(":cb_testdata.star", "mark")

          def test_after_content(t):
              w = t.world()
              rat = w.spawn(":rat", at = ":start")
              w.ctx(self = rat).after(2, mark, n = 7)
              t.eq(w.get_attr(rat, "marked"), None)
              w.run(pulses = 3)
              t.eq(w.get_attr(rat, "marked"), 7)
      """
    )
  }

  @Test
  fun `a function defined in a test file is refused by every verb that keeps it`() {
    val calls =
      mapOf(
        "after(fn)" to "c.after(1, cb)",
        "start_task(on_done)" to "c.start_task(p, 2, cb)",
        "start_task(on_stop)" to "c.start_task(p, 2, done, on_stop = cb)",
        "apply_affect(on_tick)" to "c.apply_affect(p, \"x\", 5, tick_every = 1, on_tick = cb)",
        "apply_affect(on_expire)" to "c.apply_affect(p, \"x\", 5, on_expire = cb)",
      )
    for ((what, call) in calls) {
      val message =
        errorOf(
          """
              load(":cb_testdata.star", "done")

              def cb(ctx):
                  pass

              def test_refused(t):
                  w = t.world()
                  p = w.spawn_player("Pat", at = ":start")
                  c = w.ctx()
                  $call
          """
        )
      assertContains(message, "$what: cb is defined in a test file")
      assertContains(message, "only functions in content files can be called later")
    }
  }
}
