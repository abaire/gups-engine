package gups.core.world

import gups.core.CapturingSink
import gups.core.ClientInputEvent
import gups.core.OutputSink
import gups.core.Role
import gups.core.SessionId
import gups.core.WorldControlEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginTest {
  /** Records prompts as well as text. */
  private class PromptSink : OutputSink {
    val all = StringBuilder()
    var closed: String? = null

    override fun send(text: String) {
      all.append(text)
    }

    override fun prompt(text: String) {
      all.append("[$text]")
    }

    override fun close(reason: String) {
      closed = reason
    }
  }

  private fun world() = Fixture().w.also { it.login = DevLogin(T.HALL) }

  @Test
  fun `dev login creates a local instructor and prompts after output`() {
    val w = world()
    val sink = PromptSink()
    w.handle(WorldControlEvent.Connect(SessionId(9), sink, "127.0.0.1", "telnet"))
    assertContains(sink.all, "[By what name do you wish to be known? ]")
    w.handle(ClientInputEvent(SessionId(9), "x", 0))
    assertContains(sink.all, "Names are 2 to 16 letters.")
    w.handle(ClientInputEvent(SessionId(9), "tESS", 0))
    val p = w.players.single()
    assertEquals("Tess", p.name)
    assertEquals(Role.INSTRUCTOR, p.role)
    assertTrue(p.idleExempt)
    assertContains(sink.all, "Welcome, Tess.\nThe Hall")
    assertTrue(
      sink.all.endsWith("[<50hp 20m 100mv> ]") || sink.all.endsWith("[<20hp 20m 100mv> ]"),
      sink.all.toString(),
    )
  }

  @Test
  fun `a flood before login is dropped and disconnected`() {
    val w = world()
    val sink = PromptSink()
    w.handle(WorldControlEvent.Connect(SessionId(9), sink, "127.0.0.1", "telnet"))
    repeat(28) { w.handle(ClientInputEvent(SessionId(9), "x", 0)) }
    assertNull(sink.closed)
    w.handle(ClientInputEvent(SessionId(9), "x", 0))
    assertEquals("Too much input.", sink.closed)
    assertNull(w.session(SessionId(9)))
  }

  @Test
  fun `remote connections are refused and a name can be taken over`() {
    val w = world()
    val far = PromptSink()
    w.handle(WorldControlEvent.Connect(SessionId(1), far, "10.0.0.5", "telnet"))
    assertEquals("not a local connection", far.closed)
    assertNull(w.session(SessionId(1)))

    val first = PromptSink()
    val second = PromptSink()
    w.handle(WorldControlEvent.Connect(SessionId(2), first, "::1", "telnet"))
    w.handle(ClientInputEvent(SessionId(2), "Ann", 0))
    w.handle(WorldControlEvent.Connect(SessionId(3), second, "127.0.0.1", "telnet"))
    w.handle(ClientInputEvent(SessionId(3), "ann", 0))
    assertEquals("Someone else has taken over this character.", first.closed)
    assertEquals(SessionId(3), w.players.single().session)
    assertContains(second.all, "Reconnected.")
  }

  @Test
  fun `builder commands need the role and @errors lists diagnostics`() {
    val f = Fixture(strict = false)
    val p = f.player("Ann")
    f.input(p, "@errors")
    assertEquals("Huh?\n", f.out(p))
    p.role = Role.BUILDER
    p.scope = setOf("t")
    f.input(p, "@errors")
    assertEquals("No diagnostics.\n", f.out(p))
    f.w.record(
      Diagnostic(
        f.w.pulse,
        "script_error",
        "on_enter: boom",
        pkg = "t",
        file = "//t:rooms.star",
        line = 3,
      )
    )
    f.w.flushOutput()
    assertEquals(
      "{red}[error]{/} //t:rooms.star:3: on_enter: boom (@errors t)\n",
      f.out(p),
      "owners hear of their package's errors",
    )
    f.input(p, "@err t")
    assertEquals("[0] script_error //t:rooms.star:3: on_enter: boom\n", f.out(p))
    assertTrue(CapturingSink().closedReason == null)
  }
}
