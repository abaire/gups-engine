package gups.script

import gups.core.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevToolsTest {
  private fun fixture(): ScriptFixture {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
                    def _chatty(ctx):
                        for i in range(60):
                            ctx.send(ctx.actor, "x")

                    room("start", commands = {"chat": _chatty})
                    mob("rat")
                """,
        )
      )
    DevTools.install(f.w, f.host, eval = true)
    return f
  }

  private fun prompted(s: String) = s.lines().filter { it.isNotEmpty() }

  @Test
  fun `eval runs Starlark with a live ctx`() {
    val f = fixture()
    val p = f.player()
    p.role = Role.BUILDER
    f.input(p, "@eval self.hp + 1")
    f.input(p, "@eval ctx.set_attr(self, \"seen\", True)")
    f.input(p, "@eval ctx.get_attr(self, \"seen\"), room.label")
    f.input(p, "@eval x = 1\nprint(x)")
    f.input(p, "@eval self.nope")
    f.input(p, "@eval ctx.spawn(\":rat\", room)")
    assertEquals(
      listOf(
        "51",
        "(True, \"//zones/z:start\")",
        "1",
        "Error: 'actor' value has no field or method 'nope'",
        "<actor rat #3>",
      ),
      prompted(f.out(p)),
    )
    assertEquals(gups.core.AttrValue.B(true), p.customAttributes["z:seen"])
  }

  @Test
  fun `eval has its own step limit`() {
    val f = fixture()
    val p = f.player()
    p.role = Role.INSTRUCTOR
    f.input(p, "@eval [i for i in range(10000000)]")
    assertContains(f.out(p), "exceeded the 1000000-step limit")
  }

  @Test
  fun `builder tools don't exist for players`() {
    val f = fixture()
    val p = f.player()
    f.input(p, "@eval 1")
    f.input(p, "@trace")
    f.input(p, "help")
    val out = f.out(p)
    assertEquals(2, out.lines().count { it == "Huh?" })
    assertTrue("@eval" !in out)
  }

  @Test
  fun `trace streams calls, filtered and rate limited`() {
    val f = fixture()
    val p = f.player()
    p.role = Role.BUILDER
    p.scope = setOf(p.room.label.pkg)
    f.input(p, "@trace hook command")
    f.input(p, "chat")
    f.run()
    val lines = f.out(p).lines()
    assertContains(lines, "Tracing hook command.")
    assertEquals(TRACE_LINES_PER_PULSE, lines.count { it.startsWith("[trace]") })
    f.input(p, "chat")
    f.run()
    assertEquals(
      "[trace] ... 12 more lines at pulse 1",
      f.out(p).lines().first(),
      "reported with the next pulse's first line",
    )
    f.input(p, "@trace off")
    assertEquals("[trace] ... 12 more lines at pulse 2\nTracing off.\n", f.out(p))
    f.input(p, "chat")
    f.run()
    assertTrue(f.out(p).lines().none { it.startsWith("[trace]") })
  }

  @Test
  fun `builders trace only their own packages and their own rules lines`() {
    val f = fixture()
    val p = f.player()
    p.role = Role.BUILDER
    f.input(p, "@trace zone")
    assertEquals("That isn't one of your packages.\n", f.out(p))
    f.input(p, "@trace")
    f.input(p, "chat")
    f.run()
    assertTrue(
      f.out(p).lines().none { it.startsWith("[trace]") },
      "nothing of zones/z without it in scope",
    )
    p.scope = setOf("zones/z")
    f.input(p, "chat")
    f.run()
    assertTrue(f.out(p).lines().any { it.startsWith("[trace] command") })
  }

  @Test
  fun `quarantine and unquarantine through the host, and step counts per package`() {
    val f = fixture()
    val p = f.player()
    p.role = Role.INSTRUCTOR
    f.input(p, "chat")
    f.run()
    assertTrue((f.host.stepsByPackage["zones/z"] ?: 0) > 0)
    assertEquals(setOf("zones/z", "rules"), f.host.packages)
    f.input(p, "@quarantine //zones/nope")
    assertEquals(
      "There is no package //zones/nope.\n",
      f.out(p).lines().last { it.isNotEmpty() } + "\n",
    )
    f.input(p, "@quarantine zones/z")
    assertContains(f.out(p), "//zones/z is quarantined.")
    assertEquals(setOf("zones/z"), f.host.quarantinedPackages)
    f.input(p, "@quarantine zones/z")
    assertContains(f.out(p), "already quarantined")
    f.input(p, "@unquarantine zones/z")
    assertContains(f.out(p), "//zones/z is running again.")
    assertEquals(emptySet(), f.host.quarantinedPackages)
  }
}
