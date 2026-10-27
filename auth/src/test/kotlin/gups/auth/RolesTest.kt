package gups.auth

import gups.core.Account
import gups.core.Label
import gups.core.Role
import gups.core.WebLogin
import gups.core.world.disconnect
import gups.core.world.handle
import gups.core.world.startCombat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeOwnersTest {
  private val packages =
    listOf(
      "rules",
      "lib",
      "zones/forest",
      "zones/forest/cave",
      "zones/town",
      "zones/townhall",
      "skills/fire",
    )

  @Test
  fun `patterns own packages and the last match wins`() {
    val c =
      CodeOwners.parse(
        """
        # Instructors own everything by default.
        *                    @prof
        /zones/forest/       @Ann @bob
        /zones/forest/cave/  @cy
        zones/town           @dee
        skills/**            @ed
        /lib/                @org/staff someone@example.com
        /rules/
        """
          .trimIndent()
      )
    assertEquals(
      listOf("prof"),
      c.owners("zones/townhall"),
      "zones/town doesn't match zones/townhall",
    )
    assertEquals(listOf("ann", "bob"), c.owners("zones/forest"))
    assertEquals(listOf("cy"), c.owners("zones/forest/cave"), "the later, deeper line wins")
    assertEquals(listOf("dee"), c.owners("zones/town"))
    assertEquals(listOf("ed"), c.owners("skills/fire"))
    assertEquals(emptyList(), c.owners("lib"), "only @user owners count")
    assertEquals(emptyList(), c.owners("rules"), "a line without owners leaves it unowned")
    assertTrue(c.covers("zones/town"))
    assertTrue(!c.covers("rules"))
    assertEquals(2, c.warnings.size, c.warnings.joinToString())
    assertContains(c.warnings[0], "team owner @org/staff")
    assertEquals(
      mapOf(
        "prof" to setOf("zones/townhall"),
        "ann" to setOf("zones/forest"),
        "bob" to setOf("zones/forest"),
        "cy" to setOf("zones/forest/cave"),
        "dee" to setOf("zones/town"),
        "ed" to setOf("skills/fire"),
      ),
      c.scopes(packages),
    )
  }

  @Test
  fun `unanchored names match at any depth and directories own what is below them`() {
    val c = CodeOwners.parse("/zones/ @zed\n/zones/forest @ann\ncave @cy\n")
    assertEquals(listOf("cy"), c.owners("zones/forest/cave"))
    assertEquals(listOf("ann"), c.owners("zones/forest"))
    assertEquals(listOf("zed"), c.owners("zones/town"))
    assertEquals(emptyList(), c.owners("rules"))
    assertEquals(
      emptyList(),
      CodeOwners.parse("*.star @x").owners("zones/forest"),
      "file patterns own no package",
    )
  }

  @Test
  fun `a sync replaces scopes and never changes roles`() {
    val f = LoginFixture()
    val ann =
      f.store.create(
        Account(
          0,
          "ann",
          null,
          Role.BUILDER,
          scope = listOf("zones/old"),
          manualScope = listOf("zones/town", "lib"),
          githubHandle = "Ann",
        )
      )!!
    val prof =
      f.store.create(
        Account(
          0,
          "prof",
          null,
          Role.INSTRUCTOR,
          scope = listOf("zones/forest"),
          githubHandle = "prof",
        )
      )!!
    f.store.create(Account(0, "bob", null, Role.PLAYER, githubHandle = "bob"))
    val c = CodeOwners.parse("/zones/forest/ @ann @bob @ghost\n/zones/town/ @dee\n")
    val report = syncScopes(f.store, c, listOf("zones/forest", "zones/town", "lib"))
    val a = f.store.byId(ann.id)!!
    assertEquals(Role.BUILDER, a.role)
    assertEquals(listOf("zones/forest"), a.scope)
    assertEquals(listOf("lib"), a.manualScope, "hand scope loses packages CODEOWNERS now covers")
    assertEquals(emptyList(), f.store.byId(prof.id)!!.scope, "instructors are never scoped")
    assertEquals(Role.PLAYER, f.store.byLogin("bob")!!.role)
    assertEquals(3, report.changed.size)
    assertEquals(
      listOf(
        "CODEOWNERS owner @dee matches no account's GitHub handle",
        "CODEOWNERS owner @ghost matches no account's GitHub handle",
      ),
      report.warnings,
    )
    assertEquals(
      emptyList(),
      syncScopes(f.store, c, listOf("zones/forest", "zones/town", "lib")).changed,
      "a second sync changes nothing",
    )
  }
}

class AccountToolsTest {
  private val pw = "correct horse"

  /** A fixture with the account commands and an instructor, Ida, playing. */
  private fun setup(
    codeOwners: CodeOwners = CodeOwners.EMPTY
  ): Pair<LoginFixture, LoginFixture.Client> {
    val f = LoginFixture()
    f.w.wallClock = { f.now }
    AccountTools.install(f.w, f.service, DEFAULT_LADDER) { codeOwners }
    f.account("ida", pw, Role.INSTRUCTOR)
    val c = f.connect()
    c.play("ida", pw, "Ida", create = true)
    return f to c
  }

  @Test
  fun `role changes roles and hand scope, and updates characters in play`() {
    val (f, i) = setup(CodeOwners.parse("/zones/forest/ @someone\n"))
    f.account("ann", pw)
    val ann = f.connect()
    ann.play("ann", pw, "Ann", create = true)
    assertContains(
      i.send("@role ann builder zones/town,//lib"),
      "ann is now a builder with packages lib, zones/town.",
    )
    val p = ann.player!!
    assertEquals(Role.BUILDER to setOf("lib", "zones/town"), p.role to p.scope)
    assertContains(
      i.send("@role ann builder zones/forest"),
      "//zones/forest is owned through CODEOWNERS",
    )
    assertContains(i.send("@role ann"), "Packages by hand: lib, zones/town")
    assertContains(i.send("@role nobody builder"), "There is no account 'nobody'.")
    assertContains(i.send("@role ann wizard"), "'wizard' is not a role")
    assertContains(i.send("@role ida player"), "You can't change your own role.")
    assertContains(i.send("@role ann admin"), "Only an admin can make or change admins.")
    assertContains(i.send("@role ann instructor"), "ann is now an instructor.")
    assertTrue(p.idleExempt)
    assertEquals(emptyList(), f.store.byLogin("ann")!!.manualScope)
    assertContains(ann.sink.drain() + i.send("@role ann player"), "ann is now a player.")
    assertContains(i.send("@restrict ann gossip"), "ann can't use gossip")
  }

  @Test
  fun `reset clears the password and hands out a setup code, revoke revokes tokens`() {
    val (f, i) = setup()
    val a = f.account("ann", pw)
    val out = i.send("@role ann reset")
    assertContains(
      out,
      "ann's password is cleared and their web sessions are revoked. Their new setup code is ",
    )
    assertNull(f.store.byLogin("ann")!!.pwHash)
    val code = Regex("[A-Z0-9]{5}-[A-Z0-9]{5}").find(out)!!.value
    val r = f.service.redeem(code, "ignored", "a brand new password")
    assertTrue(r is Redeemed.Ok && r.account.id == a.id)
    assertContains(i.send("@role ann revoke"), "ann's web sessions are revoked.")
    assertEquals(listOf(a.id), f.store.revoked)
    assertContains(i.send("@invite builder 3"), "makes one new builder account within 3 days.")
    assertContains(i.send("@invite admin"), "Only an admin can invite an admin.")
  }

  @Test
  fun `restrictions follow the ladder, can be lifted, and keep their history`() {
    val (f, i) = setup()
    val a = f.account("ann", pw, Role.BUILDER)
    val day = 86_400_000L
    assertContains(
      i.send("@restrict ann goto_player spamming"),
      "ann can't use goto_player until 1970-01-13 13:46 UTC (restriction 1: 1d): spamming.",
    )
    assertTrue(f.w.restrictions.single().let { it.until == f.now + day && it.reason == "spamming" })
    f.now += 2 * day
    assertContains(i.send("@restrict ann goto_player"), "(restriction 2: 7d)")
    assertContains(i.send("@unrestrict ann goto_player"), "ann may use goto_player again.")
    assertContains(
      i.send("@unrestrict ann goto_player"),
      "ann has no active restriction on goto_player.",
    )
    assertContains(
      i.send("@restrict ann goto_player"),
      "until an instructor lifts it (restriction 3: permanent)",
    )
    assertTrue(
      "(restriction 4: permanent)" in i.send("@restrict ann goto_player"),
      "the ladder stays on its last step",
    )
    assertContains(i.send("@restrict ann spawn 30m"), "ann can't use spawn until")
    assertContains(i.send("@restrict ann flying"), "'flying' is not a capability")
    assertContains(i.send("@restrict ida gossip"), "Instructors and admins can't be restricted.")

    val now = i.send("@restrictions")
    assertContains(now, "Restrictions in effect:")
    assertEquals(3, now.lines().count { it.startsWith("  ann ") }, now)
    val history = i.send("@restrictions ann")
    assertContains(history, "ann's restrictions, oldest first:")
    assertContains(history, "ended 1970-01-13")
    assertContains(history, "lifted by ida")
    assertContains(history, "set by ida")
    assertEquals(5, f.store.restrictions(a.id).size)
    assertEquals(5, f.w.restrictions.size)
  }

  @Test
  fun `durations`() {
    assertEquals(30 * 60_000L, parseDuration("30m").getOrThrow())
    assertEquals(14 * 86_400_000L, parseDuration("2w").getOrThrow())
    assertNull(parseDuration("Permanent").getOrThrow())
    assertTrue(parseDuration("soon").isFailure)
  }

  @Test
  fun `role disable quits characters including in combat or linkdead, checkpoints, and removes from world`() {
    val (f, i) = setup()
    val annAcc = f.account("ann", pw)
    val annClient = f.connect()
    annClient.play("ann", pw, "Ann", create = true)
    val annPlayer = annClient.player!!
    val guard = f.w.spawnMob(Label("//t:guard"), f.w.room(HALL))
    f.w.startCombat(annPlayer, guard)
    assertTrue(annPlayer.isEngaged)

    val out = i.send("@role ann disable dropped the course")
    assertContains(out, "ann is now disabled.")
    assertContains(annClient.sink.transcript, "This account has been disabled.")
    assertEquals("This account has been disabled.", annClient.sink.closed)
    assertFalse(annPlayer.inWorld)
    assertNull(annPlayer.session)
    assertEquals(annAcc.id, f.persistence.saved["Ann"])

    val bobAcc = f.account("bob", pw)
    val bobClient = f.connect()
    bobClient.play("bob", pw, "Bob", create = true)
    val bobPlayer = bobClient.player!!
    f.w.disconnect(bobClient.id)
    assertNotNull(bobPlayer.linkDeadSince)
    assertTrue(bobPlayer.inWorld)

    i.send("@role bob disable")
    assertFalse(bobPlayer.inWorld)
    assertEquals(bobAcc.id, f.persistence.saved["Bob"])
  }

  @Test
  fun `connection at character menu and websocket with token are closed on disable`() {
    val (f, i) = setup()
    f.account("ann", pw)

    val annMenu = f.connect()
    annMenu.send("ann")
    val menuPrompt = annMenu.send(pw)
    assertContains(menuPrompt, "Type a name to play")

    val bobAcc = f.account("bob", pw)
    val wsSink = RecordingSink()
    f.w.handle(
      gups.core.WorldControlEvent.Connect(
        gups.core.SessionId(99),
        wsSink,
        "10.0.0.1",
        "websocket",
        account = bobAcc.id,
      )
    )
    f.settle()
    assertContains(wsSink.drain(), "Type a name to play")

    i.send("@role ann disable")
    assertContains(annMenu.sink.transcript, "This account has been disabled.")
    assertEquals("Goodbye.", annMenu.sink.closed)

    i.send("@role bob disable")
    assertContains(wsSink.transcript, "This account has been disabled.")
    assertEquals("Goodbye.", wsSink.closed)
  }

  @Test
  fun `instructor reply comes after kick and role describe shows disabled record`() {
    val (f, i) = setup()
    f.account("ann", pw)
    val annClient = f.connect()
    annClient.play("ann", pw, "Ann", create = true)

    val out = i.send("@role ann disable dropped the course")
    assertContains(out, "ann is now disabled.")
    assertFalse(annClient.player?.inWorld == true)
    assertEquals("This account has been disabled.", annClient.sink.closed)

    val desc = i.send("@role ann")
    assertContains(desc, "disabled by ida")
    assertContains(desc, ": dropped the course")
  }

  @Test
  fun `disabling with no reason stores null and shows no colon suffix`() {
    val (f, i) = setup()
    f.account("ann", pw)
    i.send("@role ann disable")
    val a = f.store.byLogin("ann")!!
    assertTrue(a.disabled)
    assertNull(a.disabledReason)

    val desc = i.send("@role ann")
    assertContains(desc, "disabled by ida")
    val disabledLine = desc.lines().first { it.contains("disabled by ida") }
    assertFalse(disabledLine.contains(": "))
  }

  @Test
  fun `disabling refused on own account and admin checks`() {
    val (f, i) = setup()
    f.account("admin1", pw, Role.ADMIN)
    assertContains(i.send("@role ida disable"), "You can't disable your own account.")
    assertContains(i.send("@role ida enable"), "You can't enable your own account.")

    assertContains(i.send("@role admin1 disable"), "Only an admin can disable an admin.")
    assertContains(i.send("@role admin1 enable"), "Only an admin can enable an admin.")

    f.account("boss", pw, Role.ADMIN)
    val bossClient = f.connect()
    bossClient.play("boss", pw, "Boss", create = true)
    assertContains(bossClient.send("@role admin1 disable"), "admin1 is now disabled.")
    assertTrue(f.store.byLogin("admin1")!!.disabled)
    assertContains(bossClient.send("@role admin1 enable"), "admin1 is now enabled.")
    assertFalse(f.store.byLogin("admin1")!!.disabled)
  }

  @Test
  fun `a character without an account can't disable, so a null disabler always means the roster`() {
    val (f, i) = setup()
    f.account("ann", pw)
    i.player!!.accountId = null
    assertContains(i.send("@role ann disable roster"), "Only an account can disable an account.")
    assertFalse(f.store.byLogin("ann")!!.disabled)

    f.service.importRoster(listOf(RosterEntry("ann", disabled = true)))
    assertContains(i.send("@role ann"), "\n  disabled by the roster")
  }

  @Test
  fun `disabling twice returns no-op and keeps first record`() {
    val (f, i) = setup()
    f.account("ann", pw)
    f.account("prof2", pw, Role.INSTRUCTOR)
    val prof2Client = f.connect()
    prof2Client.play("prof2", pw, "Prof", create = true)

    f.now = 1_000_000L
    assertContains(i.send("@role ann disable first reason"), "ann is now disabled.")
    val first = f.store.byLogin("ann")!!
    assertEquals("first reason", first.disabledReason)

    f.now = 2_000_000L
    val secondReply = prof2Client.send("@role ann disable second reason")
    assertContains(secondReply, "ann is already disabled.")
    val second = f.store.byLogin("ann")!!
    assertEquals(first.disabledBy, second.disabledBy)
    assertEquals(first.disabledAt, second.disabledAt)
    assertEquals(first.disabledReason, second.disabledReason)
  }

  @Test
  fun `role enable clears flag and record and allows login`() {
    val (f, i) = setup()
    val a = f.account("ann", pw)
    val web = WebLogins(f.service, f.limiter, { f.now })
    val tok = assertIs<WebLogin.Ok>(web.login("ann", pw, "ip")).token
    assertEquals(a.id, web.accountFor(tok))

    i.send("@role ann disable")
    assertTrue(f.store.byLogin("ann")!!.disabled)
    assertNull(web.accountFor(tok))

    val enableOut = i.send("@role ann enable")
    assertContains(enableOut, "ann is now enabled.")
    val enabledAcc = f.store.byLogin("ann")!!
    assertFalse(enabledAcc.disabled)
    assertNull(enabledAcc.disabledBy)
    assertNull(enabledAcc.disabledAt)
    assertNull(enabledAcc.disabledReason)

    val desc = i.send("@role ann")
    assertFalse(desc.contains("disabled"))

    val annClient = f.connect()
    annClient.send("ann")
    val menu = annClient.send(pw)
    assertContains(menu, "Type a name to play")

    assertNull(web.accountFor(tok))
    assertContains(i.send("@role ann enable"), "ann is already enabled.")
  }
}
