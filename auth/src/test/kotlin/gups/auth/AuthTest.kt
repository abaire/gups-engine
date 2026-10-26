package gups.auth

import gups.core.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordsTest {
  @Test
  fun `the default is Argon2id m=64MiB t=3 p=1 as a PHC string, and it round-trips`() {
    val h = Argon2Hasher()
    val phc = h.hash("correct horse")
    val parts = phc.split('$')
    assertEquals(listOf("", "argon2id", "v=19", "m=65536,t=3,p=1"), parts.take(4), phc)
    assertTrue(Regex("[A-Za-z0-9+/]{22}").matches(parts[4]), "16-byte salt")
    assertTrue(Regex("[A-Za-z0-9+/]{43}").matches(parts[5]), "32-byte hash")
    assertTrue(h.verify("correct horse", phc))
    assertFalse(h.verify("correct horsE", phc))
    assertNotEquals(phc, h.hash("correct horse"), "a fresh salt each time")
  }

  @Test
  fun `verify reads the parameters from the hash and rejects malformed ones`() {
    val cheap = Argon2Hasher(memoryKiB = 8, iterations = 1).hash("pw")
    assertContains(cheap, "\$m=8,t=1,p=1\$")
    assertTrue(Argon2Hasher().verify("pw", cheap))
    for (bad in
      listOf(
        "",
        "plain",
        cheap.replace("argon2id", "argon2i"),
        cheap.dropLast(3) + "!!!",
        "\$argon2id\$v=19\$m=8,t=1,p=1\$\$",
      )) {
      assertFalse(Argon2Hasher().verify("pw", bad), bad)
    }
  }
}

class LoginLimiterTest {
  @Test
  fun `five failures lock out for a minute, doubling to fifteen, reset after fifteen quiet minutes`() {
    var now = 0L
    val l = LoginLimiter { now }
    val k = LoginLimiter.accountKey("Ann")
    repeat(4) { l.failure(k) }
    assertEquals(0, l.lockedFor(k))
    l.failure(k)
    assertEquals(60_000, l.lockedFor(k))
    now += 60_000
    assertEquals(0, l.lockedFor(k))
    val expected = listOf(120_000L, 240_000L, 480_000L, 900_000L, 900_000L)
    for (lock in expected) {
      l.failure(k)
      assertEquals(lock, l.lockedFor(k))
      now += 60_000
    }
    now += FAILURE_WINDOW_MS + 1
    assertEquals(0, l.failures(k))
    l.failure(k)
    assertEquals(0, l.lockedFor(k), "the count started over")
  }

  @Test
  fun `accounts and addresses are counted separately`() {
    var now = 0L
    val l = LoginLimiter { now }
    val ann = LoginLimiter.accountKey("ann")
    val bob = LoginLimiter.accountKey("bob")
    val ip = LoginLimiter.ipKey("10.0.0.1")
    repeat(3) { l.failure(ann, ip) }
    repeat(2) { l.failure(bob, ip) }
    assertEquals(0, l.lockedFor(ann))
    assertEquals(0, l.lockedFor(bob))
    assertEquals(60_000, l.lockedFor(ip))
    assertEquals(60_000, l.lockedFor(bob, ip))
    l.success(bob)
    assertEquals(0, l.failures(bob))
    assertEquals(5, l.failures(ip), "a success doesn't clear the address")
    now += 1
    assertEquals(59_999, l.lockedFor(ann, ip))
  }
}

class CodesTest {
  @Test
  fun `codes are two groups of five unambiguous characters and are typed forgivingly`() {
    repeat(50) {
      val c = Codes.generate()
      assertTrue(Regex("[${Codes.ALPHABET}]{5}-[${Codes.ALPHABET}]{5}").matches(c), c)
      assertEquals(Codes.hash(c), Codes.hash(" ${c.lowercase().replace("-", " ")} "))
    }
    for (ch in "01OILUV") assertFalse(ch in Codes.ALPHABET)
    assertNull(Codes.normalize("ABCDE-FGHJ"))
    assertNull(Codes.normalize("ABCDE-FGHJ0"))
    assertEquals(64, Codes.hash("ABCDE-FGHJK").length)
  }
}

class RosterTest {
  private val pw = "correct horse"

  @Test
  fun `parsing a roster`() {
    val (entries, problems) =
      Roster.parse(
        """
        # term roster
        role,login,github_handle
        builder,Ann,@ann-gh
        ,bob,
        "instructor",cy.d,"cy"
        """
          .trimIndent()
      )
    assertEquals(emptyList(), problems)
    assertEquals(
      listOf(
        RosterEntry("ann", "ann-gh", Role.BUILDER),
        RosterEntry("bob", null, Role.PLAYER),
        RosterEntry("cy.d", "cy", Role.INSTRUCTOR),
      ),
      entries,
    )
    val (none, errors) = Roster.parse("login,team\nx,1\nann,2\nann,\nbob,1,2")
    assertEquals(emptyList(), none, "a bad roster imports nothing")
    assertEquals(4, errors.size, errors.joinToString("\n"))
    assertContains(errors[0], "unknown column(s) team")
    assertContains(errors[1], "line 2: 'x': Logins are 3 to 24")
    assertContains(errors[2], "line 4: 'ann' appears twice")
    assertContains(errors[3], "line 5: 3 fields")
    assertContains(Roster.parse("github_handle\nann").second.single(), "no 'login' column")
    assertContains(Roster.parse("login,role\nann,wizard").second.single(), "unknown role 'wizard'")
  }

  @Test
  fun `importing is idempotent and existing accounts keep their passwords`() {
    val f = LoginFixture()
    val first = f.service.importRoster(listOf(RosterEntry("ann"), RosterEntry("bob", "bob-gh")))
    assertEquals(listOf("ann", "bob"), first.codes.map { it.first })
    assertNull(f.store.byLogin("ann")!!.pwHash)
    assertEquals(
      RosterResult(emptyList(), 0, 2),
      f.service.importRoster(listOf(RosterEntry("ann"), RosterEntry("bob", "bob-gh"))),
    )
    assertEquals(2, f.store.invites.size, "no new codes")

    assertIs<Redeemed.Ok>(f.service.redeem(first.codes[0].second, "ignored", pw))
    val hash = f.store.byLogin("ann")!!.pwHash
    val again =
      f.service.importRoster(listOf(RosterEntry("ann", "ann-gh", Role.BUILDER), RosterEntry("cy")))
    assertEquals(listOf("cy"), again.codes.map { it.first })
    assertEquals(1, again.updated)
    val ann = f.store.byLogin("ann")!!
    assertEquals(Role.BUILDER to "ann-gh", ann.role to ann.githubHandle)
    assertEquals(hash, ann.pwHash)
  }

  @Test
  fun `setup codes are single-use and expire`() {
    val f = LoginFixture()
    val codes = f.service.importRoster(listOf(RosterEntry("ann"), RosterEntry("bob"))).codes.toMap()
    val ann = f.service.redeem(codes.getValue("ann"), "whatever", pw)
    assertIs<Redeemed.Ok>(ann)
    assertEquals("ann", ann.account.login)
    assertTrue(f.hasher.verify(pw, f.store.byLogin("ann")!!.pwHash!!))
    assertEquals(
      Redeemed.BadCode,
      f.service.redeem(codes.getValue("ann"), "whatever", "another password"),
    )
    assertTrue(
      f.hasher.verify(pw, f.store.byLogin("ann")!!.pwHash!!),
      "a spent code changes nothing",
    )

    f.now += 7L * 24 * 60 * 60_000
    assertNull(f.service.validInvite(codes.getValue("bob")))
    assertEquals(Redeemed.BadCode, f.service.redeem(codes.getValue("bob"), "bob", pw))
    assertNull(f.store.byLogin("bob")!!.pwHash)
  }
}
