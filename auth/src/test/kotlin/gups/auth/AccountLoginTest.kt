package gups.auth

import gups.core.IDLE_QUIT_PULSES
import gups.core.Role
import gups.core.world.Features
import gups.core.world.WorldConfig
import gups.core.world.disconnect
import gups.core.world.quit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountLoginTest {
  private val pw = "correct horse"

  @Test
  fun `a roster student sets a password with the setup code, then plays`() {
    val f = LoginFixture()
    val code = f.service.importRoster(listOf(RosterEntry("ann", "ann-gh"))).codes.single().second
    val c = f.connect()
    assertContains(c.sink.drain(), "[$LOGIN_PROMPT]")
    assertContains(c.send("new"), "[Invite or setup code: ]")
    assertContains(c.send(code.lowercase()), "This code sets up the account 'ann'.")
    assertContains(c.send("short"), "Passwords need at least 10 characters.")
    assertContains(c.send("ann"), "Passwords need at least 10 characters.")
    c.send(pw)
    assertContains(c.send("something else"), "The passwords don't match.")
    c.send(pw)
    val ready = c.send(pw)
    assertContains(ready, "Your account 'ann' is ready.")
    assertContains(
      ready,
      "You have no characters yet.  Type a name to play, \"new\" to create, or \"password\" to change yours.",
    )
    assertContains(c.send("new"), "[What will your new character be called? ]")
    val welcome = c.send("aNN")
    assertContains(welcome, "Welcome, Ann.\nThe Hall")
    val ann = c.player!!
    assertEquals(f.store.byLogin("ann")!!.id, ann.accountId)
    assertEquals(Role.PLAYER, ann.role)
    assertFalse(ann.idleExempt)
    assertEquals(
      listOf("Ann"),
      f.persistence.characters(ann.accountId!!),
      "a new character is saved at once",
    )
    f.w.quit(ann)

    // The code is spent; the password works.
    val again = f.connect()
    again.send("new")
    assertContains(again.send(code), "That code isn't valid, or it has expired.")
    again.send("Ann")
    val menu = again.send(pw)
    assertContains(menu, "Your characters: Ann.")
    assertContains(again.send("ann"), "Welcome back, Ann.")
  }

  @Test
  fun `password prompts hide input`() {
    val f = LoginFixture()
    f.account("ann", pw)
    val c = f.connect()
    c.sink.drain()
    assertEquals("{hide}[Password: ]", c.send("ann"))
    assertTrue(c.send(pw).startsWith("{show}\n"))
  }

  @Test
  fun `wrong passwords and unknown logins look alike, and three close the connection`() {
    val f = LoginFixture()
    f.account("ann", pw)
    val c = f.connect()
    c.send("ann")
    assertContains(c.send("wrong password"), "Wrong login or password.")
    c.send("nobody")
    val before = f.hasher.verifies.get()
    assertContains(c.send(pw), "Wrong login or password.")
    assertEquals(before + 1, f.hasher.verifies.get(), "an unknown login still costs one hash")
    assertNull(c.sink.closed)
    c.send("ann")
    c.send("still wrong")
    assertEquals("Too many wrong passwords. Goodbye.", c.sink.closed)
    assertNull(f.w.players.firstOrNull())
  }

  @Test
  fun `a locked-out login is refused without hashing until the lockout ends`() {
    val f = LoginFixture()
    f.account("ann", pw)
    repeat(5) { i ->
      val c = f.connect("10.0.0.${i + 10}")
      c.send("ann")
      c.send("wrong password")
    }
    val c = f.connect("10.0.0.99")
    c.send("ann")
    val verifies = f.hasher.verifies.get()
    assertContains(c.send(pw), "Too many failed logins. Try again in 60 seconds.")
    assertEquals(verifies, f.hasher.verifies.get(), "no hash while locked out")
    f.now += 60_000
    c.send("ann")
    assertContains(c.send(pw), "Type a name to play")
  }

  @Test
  fun `input typed during the password check is held and replayed in order`() {
    val f = LoginFixture()
    f.account("ann", pw)
    val c = f.connect()
    c.send("ann")
    c.type(pw)
    c.type("new")
    c.type("Ann")
    c.type("north")
    assertEquals(1, f.queued)
    assertNull(c.player)
    f.settle()
    val out = c.sink.drain()
    assertContains(out, "Welcome, Ann.")
    f.w.runPulses(20)
    assertEquals(YARD, c.player!!.room.label, "the held 'north' ran as a command")
  }

  @Test
  fun `taking over a character and playing one character at a time`() {
    val f = LoginFixture()
    f.account("ann", pw)
    val first = f.connect()
    first.play("ann", pw, "Ann", create = true)
    f.persistence.saved["Tilly"] = f.store.byLogin("ann")!!.id

    val second = f.connect()
    second.send("ann")
    assertContains(second.send(pw), "Your characters: Ann, Tilly.")
    assertContains(second.send("tilly"), "You're already playing Ann.")
    assertContains(second.send("new"), "You're already playing Ann.")
    assertContains(second.send("ann"), "Reconnected.")
    assertEquals("Someone else has taken over this character.", first.sink.closed)
    assertEquals(second.id, f.w.players.single().session)

    // Link-dead still counts as playing.
    f.w.disconnect(second.id)
    val third = f.connect()
    third.send("ann")
    third.send(pw)
    assertContains(third.send("tilly"), "You're already playing Ann.")
    assertContains(third.send("ann"), "Reconnected.")
  }

  @Test
  fun `character names are checked, NPC names included, and the limit binds everyone below instructor`() {
    val f = LoginFixture(LoginSettings(maxCharacters = 2, reservedNames = setOf("Gandalf")))
    f.account("ann", pw)
    f.persistence.saved["Bob"] = 99
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    assertContains(c.send("x"), "Names are 2 to 16 letters.")
    assertContains(c.send("Ann2"), "Names are 2 to 16 letters.")
    assertContains(c.send("Ann Lee"), "Names are 2 to 16 letters.")
    for (name in listOf("north", "Self", "look", "gandalf", "new", "Guard", "brom")) assertTrue(
      "That name is reserved." in c.send(name),
      name,
    )
    assertContains(c.send("bob"), "That name is taken.")
    c.send("Ann")
    f.w.quit(c.player!!)

    val d = f.connect()
    d.play("ann", pw, "Tilly", create = true)
    f.w.quit(d.player!!)
    val e = f.connect()
    e.send("ann")
    e.send(pw)
    assertContains(e.send("new"), "You already have 2 characters, the most an account may have.")

    f.store.update(f.store.byLogin("ann")!!.copy(role = Role.INSTRUCTOR))
    val g = f.connect()
    g.play("ann", pw, "Cleo", create = true)
    assertEquals(Role.INSTRUCTOR, g.player!!.role)
    assertTrue(g.player!!.idleExempt)
  }

  @Test
  fun `invites create accounts with their role, and open signup needs none`() {
    val f = LoginFixture()
    val code = f.service.createInvite(Role.BUILDER)
    val c = f.connect()
    c.send("new")
    assertContains(c.send(code), "[Choose a login")
    assertContains(c.send("X"), "Logins are 3 to 24 characters")
    f.account("taken", pw)
    assertContains(c.send("TAKEN"), "That login is taken.")
    c.send("bea")
    c.send(pw)
    assertContains(c.send(pw), "Your account 'bea' is ready.")
    assertEquals(Role.BUILDER, f.store.byLogin("bea")!!.role)

    val closed = f.connect()
    closed.send("new")
    assertTrue("[$LOGIN_PROMPT]" in closed.send(""), "no open signup: an empty code goes back")

    val open = LoginFixture(LoginSettings(openSignup = true))
    val o = open.connect()
    assertContains(o.send("new"), "Enter to sign up without one")
    o.send("")
    o.send("cy.d")
    o.send(pw)
    assertContains(o.send(pw), "Your account 'cy.d' is ready.")
    assertEquals(Role.PLAYER, open.store.byLogin("cy.d")!!.role)
  }

  @Test
  fun `changing the password at the character menu`() {
    val f = LoginFixture()
    f.account("ann", pw)
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    assertContains(c.send("password"), "{hide}[Current password: ]")
    c.send("not it at all")
    c.send("a new password")
    assertContains(c.send("a new password"), "That isn't your current password.")
    c.send("password")
    c.send(pw)
    c.send("a new password")
    assertContains(c.send("a new password"), "Your password has been changed.")
    val d = f.connect()
    d.send("ann")
    assertContains(d.send("a new password"), "Type a name to play")
  }

  @Test
  fun `idle login prompts time out after a minute, disabled accounts can't log in`() {
    val f = LoginFixture()
    val a = f.account("ann", pw)
    val c = f.connect()
    f.w.runPulses(239)
    assertNull(c.sink.closed)
    f.w.runPulses(1)
    assertEquals("You took too long. Goodbye.", c.sink.closed)

    f.store.update(a.copy(disabled = true))
    val d = f.connect()
    d.send("ann")
    assertContains(d.send(pw), "This account is disabled.")
    assertNotNull(d.sink.closed)
    assertTrue(IDLE_QUIT_PULSES > 240)
  }

  @Test
  fun `the race - login reading account before disable commits is refused when continuation runs`() {
    val f = LoginFixture()
    val a = f.account("ann", pw)
    val c = f.connect()
    c.send("ann")

    c.type(pw)
    assertEquals(1, f.queued)
    // The login reads the still-enabled account before the disable commits.
    f.runWork()
    assertEquals(0, f.queued)

    f.store.setDisabled(a.id, disabled = true, by = 1, at = f.now, reason = "dropped")
    f.w.closeDisabledAccount(a.id)

    f.settle()
    assertContains(c.sink.transcript, "This account is disabled.")
    assertEquals("Goodbye.", c.sink.closed)
    assertFalse(c.sink.transcript.contains("Your characters"))
  }

  @Test
  fun `the setup code of a password-less roster account is unusable after a disable`() {
    val f = LoginFixture()
    val r = f.service.importRoster(listOf(RosterEntry("ann")))
    val code = r.codes.single().second
    val ann = f.store.byLogin("ann")!!

    f.store.setDisabled(ann.id, disabled = true, by = 1, at = f.now, reason = "dropped")
    val c = f.connect()
    c.send("new")
    val out = c.send(code)
    assertContains(out, "That code isn't valid, or it has expired.")
  }

  @Test
  fun `login text follows the world's message overrides`() {
    val f =
      LoginFixture(
        config =
          WorldConfig(
            seed = 1,
            strict = true,
            messages =
              mapOf(
                "login.welcome" to "Link established.",
                "login.wrong" to "Credentials rejected.",
                "login.menu" to "{characters} Pick one.",
              ),
          )
      )
    f.account("ann", pw)
    val c = f.connect()
    assertContains(c.sink.drain(), "Link established.")
    c.send("ann")
    assertContains(c.send("wrong"), "Credentials rejected.")
    c.send("ann")
    val menu = c.send(pw)
    assertContains(menu, "You have no characters yet. Pick one.")
  }

  @Test
  fun `reserved character names follow the world's renamed commands`() {
    val f =
      LoginFixture(
        config =
          WorldConfig(
            seed = 1,
            strict = true,
            rename = mapOf("cast" to "invoke", "kill" to "attack"),
          )
      )
    val reserved = Names.reserved(f.w, emptySet())
    assertTrue("invoke" in reserved && "attack" in reserved)
    assertFalse("cast" in reserved)
    assertFalse("kill" in reserved)
    val without =
      LoginFixture(config = WorldConfig(seed = 1, strict = true, features = Features(emptySet())))
    val names = Names.reserved(without.w, emptySet())
    assertFalse("cast" in names || "kill" in names || "advance" in names)
    assertTrue("look" in names)
  }
}
