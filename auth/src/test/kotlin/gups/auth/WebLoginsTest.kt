package gups.auth

import gups.core.Role
import gups.core.WebLogin
import gups.core.world.handle
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebLoginsTest {
  private val pw = "correct horse"

  private fun setup(): Pair<LoginFixture, WebLogins> {
    val f = LoginFixture()
    return f to WebLogins(f.service, f.limiter, { f.now })
  }

  @Test
  fun `a login issues a 30-day token stored only as its hash`() {
    val (f, web) = setup()
    val a = f.account("ann", pw)
    val ok = assertIs<WebLogin.Ok>(web.login(" Ann ", pw, "10.0.0.1"))
    assertEquals(a.id, ok.accountId)
    assertEquals(f.now + 30L * 24 * 60 * 60_000, ok.expiresAt)
    assertTrue(ok.token.length >= 43)
    assertEquals(setOf(WebLogins.hash(ok.token)), f.store.tokens.keys, "only the hash is stored")
    assertEquals(a.id, web.accountFor(ok.token))
    assertNull(web.accountFor("forged"))
    f.now = ok.expiresAt
    assertNull(web.accountFor(ok.token), "expired")
  }

  @Test
  fun `logout, a password change and revoke end tokens`() {
    val (f, web) = setup()
    val a = f.account("ann", pw)
    val t1 = assertIs<WebLogin.Ok>(web.login("ann", pw, "ip")).token
    val t2 = assertIs<WebLogin.Ok>(web.login("ann", pw, "ip")).token
    web.logout(t1)
    assertNull(web.accountFor(t1))
    assertEquals(a.id, web.accountFor(t2))
    f.store.setPassword(a.id, f.hasher.hash("a different password"))
    assertNull(web.accountFor(t2))
    val t3 = assertIs<WebLogin.Ok>(web.login("ann", "a different password", "ip")).token
    f.store.revokeTokens(a.id)
    assertNull(web.accountFor(t3))
    val t4 = assertIs<WebLogin.Ok>(web.login("ann", "a different password", "ip")).token
    f.store.update(f.store.byId(a.id)!!.copy(disabled = true))
    assertNull(web.accountFor(t4), "a disabled account's tokens stop working")
    assertEquals(
      WebLogin.Refused(403, "This account is disabled."),
      web.login("ann", "a different password", "ip"),
    )
  }

  @Test
  fun `web logins are rate-limited with telnet's counts and cost a hash when unknown`() {
    val (f, web) = setup()
    f.account("ann", pw)
    val before = f.hasher.verifies.get()
    assertEquals(
      WebLogin.Refused(401, "Wrong login or password."),
      web.login("nobody", pw, "10.0.0.1"),
    )
    assertEquals(before + 1, f.hasher.verifies.get())
    repeat(5) { web.login("ann", "wrong password", "10.0.0.${it + 2}") }
    val locked = assertIs<WebLogin.Refused>(web.login("ann", pw, "10.0.0.9"))
    assertEquals(429, locked.status)
    assertContains(locked.message, "Try again in 60 seconds")
    // The same counts lock the telnet login.
    val c = f.connect("10.0.0.10")
    c.send("ann")
    assertContains(c.send(pw), "Too many failed logins.")
    f.now += 60_000
    assertIs<WebLogin.Ok>(web.login("ann", pw, "10.0.0.9"))
  }

  @Test
  fun `a socket with a token starts at character selection`() {
    val f = LoginFixture()
    val a = f.account("ann", pw, Role.BUILDER)
    f.persistence.saved["Ann"] = a.id
    val sink = RecordingSink()
    f.w.handle(
      gups.core.WorldControlEvent.Connect(
        gups.core.SessionId(50),
        sink,
        "10.0.0.1",
        "ws",
        account = a.id,
      )
    )
    f.settle()
    val out = sink.drain()
    assertContains(out, "Your characters: Ann.")
    assertTrue(LOGIN_PROMPT !in out, "no login prompt")
    f.w.handle(gups.core.ClientInputEvent(gups.core.SessionId(50), "ann", 0))
    f.settle()
    assertContains(sink.drain(), "Welcome back, Ann.")

    val gone = RecordingSink()
    f.w.handle(
      gups.core.WorldControlEvent.Connect(
        gups.core.SessionId(51),
        gone,
        "10.0.0.1",
        "ws",
        account = 999,
      )
    )
    f.settle()
    assertEquals("Goodbye.", gone.closed)
  }

  @Test
  fun `signing up with a roster setup code sets that account's password`() {
    val (f, web) = setup()
    val code = f.service.importRoster(listOf(RosterEntry("ann"))).codes.single().second
    assertEquals(gups.core.CodeCheck.Valid("ann"), web.checkCode(code.lowercase(), "ip"))
    assertEquals(
      WebLogin.Refused(400, "Passwords need at least 10 characters."),
      web.signup(code, null, "short", "ip"),
    )
    val ok = assertIs<WebLogin.Ok>(web.signup(code, "ignored-for-setup-codes", pw, "ip"))
    val ann = f.store.byLogin("ann")!!
    assertEquals(ann.id, ok.accountId)
    assertEquals(ann.id, web.accountFor(ok.token))
    assertTrue(f.hasher.verify(pw, ann.pwHash!!))
    assertNull(f.store.byLogin("ignored-for-setup-codes"))
    assertEquals(
      WebLogin.Refused(404, "That code isn't valid, or it has expired."),
      web.signup(code, null, pw, "ip"),
      "one use only",
    )
  }

  @Test
  fun `signing up with an invite creates the chosen login with the invite's role`() {
    val (f, web) = setup()
    f.account("taken", pw)
    val code = f.service.createInvite(Role.BUILDER)
    assertEquals(gups.core.CodeCheck.Valid(null), web.checkCode(code, "ip"))
    assertEquals(400, (web.signup(code, "X", pw, "ip") as WebLogin.Refused).status)
    assertEquals(WebLogin.Refused(409, "That login is taken."), web.signup(code, "Taken", pw, "ip"))
    assertEquals(
      WebLogin.Refused(400, "Your password can't be your login."),
      web.signup(code, "beatrice00", "Beatrice00", "ip"),
    )
    assertIs<WebLogin.Ok>(web.signup(code, " Bea ", pw, "ip"))
    assertEquals(Role.BUILDER, f.store.byLogin("bea")!!.role)
    assertIs<WebLogin.Ok>(web.login("bea", pw, "ip"))
    val later = f.service.createInvite(Role.PLAYER, days = 7)
    f.now += 7L * 24 * 60 * 60_000
    assertEquals(404, (web.checkCode(later, "ip") as gups.core.CodeCheck.Refused).status, "expired")
  }

  @Test
  fun `bad codes are rate-limited per address`() {
    val (f, web) = setup()
    val code = f.service.createInvite(Role.PLAYER)
    repeat(3) {
      assertEquals(
        404,
        (web.checkCode("AAAAA-AAAAA", "10.0.0.1") as gups.core.CodeCheck.Refused).status,
      )
    }
    repeat(2) {
      assertEquals(
        404,
        (web.signup("not a code", "bea", pw, "10.0.0.1") as WebLogin.Refused).status,
      )
    }
    assertEquals(
      429,
      (web.checkCode(code, "10.0.0.1") as gups.core.CodeCheck.Refused).status,
      "even a good code waits",
    )
    assertIs<gups.core.CodeCheck.Valid>(web.checkCode(code, "10.0.0.2"))
    f.now += 60_000
    assertIs<WebLogin.Ok>(web.signup(code, "bea", pw, "10.0.0.1"))
  }

  @Test
  fun `a web token issued before disable is refused afterward and is gone from the store`() {
    val (f, web) = setup()
    val a = f.account("ann", pw)
    val ok = assertIs<WebLogin.Ok>(web.login("ann", pw, "ip"))
    assertEquals(a.id, web.accountFor(ok.token))

    f.store.setDisabled(a.id, disabled = true, by = 1, at = f.now, reason = "test")
    assertNull(web.accountFor(ok.token))
    assertTrue(f.store.tokens.isEmpty())
  }
}
