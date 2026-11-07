package gups.persist

import gups.core.Account
import gups.core.Invite
import gups.core.Role
import gups.core.world.spawnItem
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Child JVM for [AccountStoreTest]: makes an account change through a running persistence, reports
 * it, then waits to be killed with SIGKILL.
 */
object AccountCrashChild {
  @JvmStatic
  fun main(args: Array<String>) {
    val sp = SqlitePersistence.open(Path.of(args[0]))
    val a = sp.accounts.create(Account(0, "ann", null, Role.BUILDER, githubHandle = "ann-gh"))!!
    sp.accounts.setPassword(a.id, "\$argon2id\$fake")
    println("DONE ${a.id}")
    System.out.flush()
    Thread.sleep(60_000)
  }
}

class AccountStoreTest {
  private fun scalar(file: Path, sql: String): String? =
    DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
      c.createStatement().use { st ->
        st.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
      }
    }

  @Test
  fun `accounts and invites through the checkpoint writer`() {
    val file = tempDb()
    val sp = SqlitePersistence.open(file)
    val store = sp.accounts
    val ann =
      store.create(
        Account(
          0,
          "ann",
          null,
          Role.BUILDER,
          scope = listOf("zones/forest"),
          githubHandle = "ann-gh",
          createdAt = 5,
        )
      )!!
    assertTrue(ann.id > 0)
    assertNull(store.create(Account(0, "ANN", null)), "logins are unique in any case")
    assertEquals(ann, store.byLogin("Ann"))
    assertEquals(
      """{"role":"BUILDER","scope":["zones/forest"]}""",
      scalar(file, "SELECT roles_json FROM accounts"),
    )

    store.setPassword(ann.id, "hash1")
    store.update(
      ann.copy(role = Role.INSTRUCTOR, scope = emptyList(), githubHandle = null, disabled = true)
    )
    val changed = store.byId(ann.id)!!
    assertEquals(
      Account(
        ann.id,
        "ann",
        "hash1",
        Role.INSTRUCTOR,
        githubHandle = null,
        disabled = true,
        createdAt = 5,
      ),
      changed,
      "update never touches the password",
    )

    DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
      c.createStatement().use {
        it.execute("INSERT INTO session_tokens VALUES ('t', ${ann.id}, 0)")
      }
    }
    store.setPassword(ann.id, null)
    assertEquals(
      "0",
      scalar(file, "SELECT COUNT(*) FROM session_tokens"),
      "a password change revokes tokens",
    )

    store.addInvite(Invite("h1", Role.PLAYER, 100, accountId = ann.id))
    assertEquals(Invite("h1", Role.PLAYER, 100, ann.id, null), store.invite("h1"))
    assertTrue(store.useInvite("h1", ann.id))
    assertFalse(store.useInvite("h1", ann.id), "single use")
    assertFalse(store.useInvite("nope", ann.id))
    assertEquals(ann.id, store.invite("h1")!!.usedBy)
    assertEquals(listOf("ann"), store.all().map { it.login })
    assertTrue(sp.close())
  }

  @Test
  fun `characters are listed per account from memory`() {
    val pw = PersistWorld(tempDb())
    val p = pw.login("Ann")
    p.accountId = 7
    p.dirty = true
    pw.save()
    assertEquals(listOf("Ann"), pw.persistence.characters(7))
    assertEquals(gups.core.world.SavedCharacter("Ann", 7), pw.persistence.savedCharacter("ANN"))
    assertNull(pw.persistence.savedCharacter("Bob"))
    pw.close()
  }

  @Test
  fun `an account change survives a crash right after it`() {
    val file = tempDb()
    Database.open(file).close()
    val java = ProcessHandle.current().info().command().orElse("java")
    val proc =
      ProcessBuilder(
          java,
          "-cp",
          System.getProperty("java.class.path"),
          AccountCrashChild::class.java.name,
          file.toString(),
        )
        .redirectErrorStream(true)
        .start()
    val line = proc.inputStream.bufferedReader().readLine()
    proc.destroyForcibly()
    assertTrue(proc.waitFor(10, TimeUnit.SECONDS))
    assertNotNull(line)
    assertTrue(line.startsWith("DONE "), line)
    val id = line.removePrefix("DONE ").toLong()
    Database.open(file).use { d ->
      SqliteAccountStore(file, d::write).use { store ->
        val a = store.byId(id)!!
        assertEquals(
          Triple("ann", Role.BUILDER, "\$argon2id\$fake"),
          Triple(a.login, a.role, a.pwHash),
        )
      }
    }
  }

  @Test
  fun `restrictions and hand scope survive a restart`() {
    val file = tempDb()
    val first = SqlitePersistence.open(file)
    val ann =
      first.accounts.create(
        Account(
          0,
          "ann",
          null,
          Role.BUILDER,
          scope = listOf("zones/a"),
          manualScope = listOf("lib"),
        )
      )!!
    val r =
      first.accounts.addRestriction(
        gups.core.Restriction(0, ann.id, "goto", 5_000, "why", 9, 1_000)
      )
    val forever =
      first.accounts.addRestriction(
        gups.core.Restriction(0, ann.id, "spawn", null, null, null, 1_000)
      )
    first.accounts.liftRestriction(forever.id, 9, 2_000)
    first.accounts.revokeTokens(ann.id)
    assertTrue(first.close())

    val second = SqlitePersistence.open(file)
    assertEquals(listOf("lib"), second.accounts.byId(ann.id)!!.manualScope)
    assertEquals(setOf("lib", "zones/a"), second.accounts.byId(ann.id)!!.packages)
    assertEquals(
      listOf(r, forever.copy(liftedBy = 9, liftedAt = 2_000)),
      second.accounts.restrictions(ann.id),
    )
    assertEquals(2, second.accounts.restrictions().size)
    assertTrue(second.close())
  }

  @Test
  fun `debug spawns are never saved, and a library's quarantine is`() {
    val file = tempDb()
    val quarantined = linkedSetOf<String>()
    val host =
      object : gups.core.world.ScriptHost by gups.core.world.ScriptHost.NONE {
        override val packages = setOf("t", "lib")
        override val quarantinedPackages: Set<String>
          get() = quarantined

        override fun quarantine(world: gups.core.world.World, pkg: String, why: String) {
          quarantined += pkg
        }
      }
    val pw = PersistWorld(file, scripts = host)
    val rat = pw.w.spawnMob(P.RAT, pw.w.room(P.HALL)).also { it.debug = true }
    val ann = pw.login("Ann")
    pw.w.spawnItem(P.GEM, gups.core.model.ItemLocation.Carried(ann.id), debug = true)
    quarantined += "lib"
    pw.save()
    pw.close()
    quarantined.clear()
    val again = PersistWorld(file, scripts = host)
    assertTrue(again.w.actors.none { it.id == rat.id })
    assertEquals(setOf("lib"), quarantined)
    val back = again.login("Ann")
    assertTrue(back.inventory.none { it.protoLabel == P.GEM })
    again.close()
  }

  @Test
  fun `session tokens expire, prune, and are revoked with the password`() {
    val sp = SqlitePersistence.open(tempDb())
    val s = sp.accounts
    val ann = s.create(Account(0, "ann", "h", Role.PLAYER))!!
    s.addToken("old", ann.id, 50, 0)
    s.addToken("a", ann.id, 1_000, 100)
    assertNull(s.tokenAccount("old", 100), "pruned when it expired")
    assertEquals(ann.id, s.tokenAccount("a", 999))
    assertNull(s.tokenAccount("a", 1_000))
    s.removeToken("a")
    assertNull(s.tokenAccount("a", 0))
    s.addToken("b", ann.id, 1_000, 0)
    s.revokeTokens(ann.id)
    assertNull(s.tokenAccount("b", 0))
    s.addToken("c", ann.id, 1_000, 0)
    s.setPassword(ann.id, "h2")
    assertNull(s.tokenAccount("c", 0))
    assertTrue(sp.close())
  }

  @Test
  fun `migration 4 runs on a database at schema version 3 and existing accounts read back with null record`() {
    val file = tempDb()
    Database.open(file, MIGRATIONS.take(3)).close()
    DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
      c.createStatement().use { st ->
        st.executeUpdate(
          "INSERT INTO accounts (login, pw_hash, roles_json, github_handle, disabled, created_at) VALUES ('ann', 'hash', '{\"role\":\"PLAYER\"}', NULL, 0, 100)"
        )
      }
    }
    val sp = SqlitePersistence.open(file)
    assertEquals(MIGRATIONS.size, sp.db.schemaVersion)
    val ann = sp.accounts.byLogin("ann")!!
    assertFalse(ann.disabled)
    assertNull(ann.disabledBy)
    assertNull(ann.disabledAt)
    assertNull(ann.disabledReason)
    assertTrue(sp.close())
  }

  @Test
  fun `setDisabled stores record, deletes tokens and unused setup codes, and leaves others alone`() {
    val file = tempDb()
    val sp = SqlitePersistence.open(file)
    val s = sp.accounts
    val ida = s.create(Account(0, "ida", "h", Role.INSTRUCTOR))!!
    val ann = s.create(Account(0, "ann", "h", Role.PLAYER))!!
    val bob = s.create(Account(0, "bob", "h", Role.PLAYER))!!

    s.addToken("ann-tok", ann.id, 10_000, 1_000)
    s.addToken("bob-tok", bob.id, 10_000, 1_000)

    s.addInvite(Invite("ann-code", Role.PLAYER, 10_000, accountId = ann.id))
    s.addInvite(Invite("ann-used", Role.PLAYER, 10_000, accountId = ann.id))
    assertTrue(s.useInvite("ann-used", ann.id))
    s.addInvite(Invite("bob-code", Role.PLAYER, 10_000, accountId = bob.id))

    val changed =
      s.setDisabled(ann.id, disabled = true, by = ida.id, at = 2_000, reason = "dropped")
    assertTrue(changed)

    val updatedAnn = s.byId(ann.id)!!
    assertTrue(updatedAnn.disabled)
    assertEquals(ida.id, updatedAnn.disabledBy)
    assertEquals(2_000L, updatedAnn.disabledAt)
    assertEquals("dropped", updatedAnn.disabledReason)

    assertNull(s.tokenAccount("ann-tok", 1_500))
    assertEquals(bob.id, s.tokenAccount("bob-tok", 1_500))

    assertNull(s.invite("ann-code"))
    assertNotNull(s.invite("ann-used"))
    assertNotNull(s.invite("bob-code"))

    assertTrue(sp.close())
  }

  @Test
  fun `setDisabled to the current state returns false and changes nothing`() {
    val sp = SqlitePersistence.open(tempDb())
    val s = sp.accounts
    val ann = s.create(Account(0, "ann", "h", Role.PLAYER))!!
    assertFalse(s.setDisabled(ann.id, disabled = false, by = null, at = 1_000, reason = null))

    assertTrue(s.setDisabled(ann.id, disabled = true, by = 1, at = 1_000, reason = "first"))
    val first = s.byId(ann.id)!!
    assertFalse(s.setDisabled(ann.id, disabled = true, by = 2, at = 2_000, reason = "second"))
    val second = s.byId(ann.id)!!
    assertEquals(first, second)

    assertTrue(sp.close())
  }

  @Test
  fun `enabling clears the flag and record, deleted tokens stay deleted`() {
    val sp = SqlitePersistence.open(tempDb())
    val s = sp.accounts
    val ann = s.create(Account(0, "ann", "h", Role.PLAYER))!!
    s.addToken("tok", ann.id, 10_000, 1_000)
    assertTrue(s.setDisabled(ann.id, disabled = true, by = 1, at = 1_000, reason = "test"))
    assertNull(s.tokenAccount("tok", 1_500))

    assertTrue(s.setDisabled(ann.id, disabled = false, by = null, at = 2_000, reason = null))
    val enabledAnn = s.byId(ann.id)!!
    assertFalse(enabledAnn.disabled)
    assertNull(enabledAnn.disabledBy)
    assertNull(enabledAnn.disabledAt)
    assertNull(enabledAnn.disabledReason)

    assertNull(s.tokenAccount("tok", 1_500))

    assertTrue(sp.close())
  }

  @Test
  fun `update round-trips the disabled record`() {
    val sp = SqlitePersistence.open(tempDb())
    val s = sp.accounts
    val ann = s.create(Account(0, "ann", "h", Role.PLAYER))!!
    val withDisabled =
      ann.copy(
        role = Role.BUILDER,
        disabled = true,
        disabledBy = 42L,
        disabledAt = 12345L,
        disabledReason = "reason",
      )
    s.update(withDisabled)
    val readBack = s.byId(ann.id)!!
    assertEquals(withDisabled, readBack)
    assertTrue(sp.close())
  }

  @Test
  fun `a sanitized copy has no accounts`() {
    val file = tempDb()
    val sp = SqlitePersistence.open(file)
    sp.accounts.create(Account(0, "ann", "h", Role.PLAYER))!!
    sp.accounts.create(Account(0, "bob", "h", Role.PLAYER))!!
    assertTrue(sp.close())

    val target = tempDb()
    Database.sanitizedCopy(file, target)
    Database.open(target).use { d ->
      SqliteAccountStore(target, d::write).use { s -> assertEquals(emptyList(), s.all()) }
    }
  }

  @Test
  fun `account creations counter increments and is immune to stale update`() {
    val sp = SqlitePersistence.open(tempDb())
    val s = sp.accounts
    val ann = s.create(Account(0, "ann", "h", Role.PLAYER))!!
    assertEquals(0, ann.creations)

    s.countCreation(ann.id)
    s.countCreation(ann.id)
    val updated = s.byId(ann.id)!!
    assertEquals(2, updated.creations)

    // Stale update (with creations = 0) does not lower the counter
    s.update(ann.copy(role = Role.BUILDER))
    val afterUpdate = s.byId(ann.id)!!
    assertEquals(2, afterUpdate.creations)
    assertEquals(Role.BUILDER, afterUpdate.role)

    assertTrue(sp.close())
  }

  @Test
  fun `migration 5 adds creations column defaulting to 0`() {
    val file = tempDb()
    Database.open(file, MIGRATIONS.subList(0, 4)).close()
    DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
      c.createStatement().use {
        it.execute(
          "INSERT INTO accounts (id, login, pw_hash, roles_json, github_handle, disabled, created_at) VALUES (1, 'ann', 'h', '{\"role\":\"PLAYER\",\"scope\":[],\"manual\":[]}', NULL, 0, 100)"
        )
      }
    }
    // Opening with full persistence runs migration 5
    val sp = SqlitePersistence.open(file)
    val ann = sp.accounts.byId(1)!!
    assertEquals(0, ann.creations)
    sp.accounts.countCreation(1)
    assertEquals(1, sp.accounts.byId(1)!!.creations)
    assertTrue(sp.close())
  }
}
