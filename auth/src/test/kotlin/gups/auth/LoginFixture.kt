package gups.auth

import gups.core.Account
import gups.core.AccountStore
import gups.core.ClientInputEvent
import gups.core.Invite
import gups.core.Label
import gups.core.OutputSink
import gups.core.Restriction
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Direction
import gups.core.model.ExitProto
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.world.Persistence
import gups.core.world.SavedCharacter
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.handle
import java.util.concurrent.atomic.AtomicInteger

val HALL = Label("//t:hall")
val YARD = Label("//t:yard")

fun testProtos() =
  Prototypes(
    rooms =
      listOf(
        RoomProto(
          HALL,
          "The Hall",
          "A long hall.",
          exits = listOf(ExitProto(Direction.NORTH, YARD)),
        ),
        RoomProto(YARD, "The Yard", exits = listOf(ExitProto(Direction.SOUTH, HALL))),
      ),
    mobs = listOf(MobProto(Label("//t:guard"), "Captain Brom", listOf("guard", "captain"))),
  )

/** Accounts in memory, for tests. */
class MemoryAccountStore : AccountStore {
  val accounts = LinkedHashMap<Long, Account>()
  val invites = LinkedHashMap<String, Invite>()
  private var nextId = 1L

  @Synchronized
  override fun byLogin(login: String) =
    accounts.values.firstOrNull { it.login.equals(login, ignoreCase = true) }

  @Synchronized override fun byId(id: Long) = accounts[id]

  @Synchronized override fun all() = accounts.values.toList()

  @Synchronized
  override fun create(account: Account): Account? {
    if (byLogin(account.login) != null) return null
    return account.copy(id = nextId++).also { accounts[it.id] = it }
  }

  @Synchronized
  override fun update(account: Account) {
    val old = accounts.getValue(account.id)
    accounts[account.id] = account.copy(pwHash = old.pwHash, login = old.login)
  }

  @Synchronized
  override fun setDisabled(
    id: Long,
    disabled: Boolean,
    by: Long?,
    at: Long,
    reason: String?,
  ): Boolean {
    val a = accounts[id] ?: return false
    if (a.disabled == disabled) return false
    accounts[id] =
      if (disabled) {
        a.copy(disabled = true, disabledBy = by, disabledAt = at, disabledReason = reason)
      } else {
        a.copy(disabled = false, disabledBy = null, disabledAt = null, disabledReason = null)
      }
    if (disabled) {
      tokens.values.removeIf { it.first == id }
      invites.values.removeIf { it.accountId == id && it.usedBy == null }
    }
    return true
  }

  @Synchronized
  override fun setPassword(id: Long, pwHash: String?) {
    accounts[id] = accounts.getValue(id).copy(pwHash = pwHash)
    tokens.values.removeIf { it.first == id }
  }

  @Synchronized
  override fun addInvite(invite: Invite) {
    invites.putIfAbsent(invite.codeHash, invite)
  }

  @Synchronized override fun invite(codeHash: String) = invites[codeHash]

  @Synchronized
  override fun useInvite(codeHash: String, accountId: Long): Boolean {
    val i = invites[codeHash] ?: return false
    if (i.usedBy != null) return false
    invites[codeHash] = i.copy(usedBy = accountId)
    return true
  }

  val revoked = mutableListOf<Long>()
  val restrictionRows = mutableListOf<Restriction>()

  @Synchronized
  override fun revokeTokens(id: Long) {
    revoked += id
    tokens.values.removeIf { it.first == id }
  }

  /** Token hash to (account, expiry). */
  val tokens = LinkedHashMap<String, Pair<Long, Long>>()

  @Synchronized
  override fun addToken(tokenHash: String, accountId: Long, expiresAt: Long, now: Long) {
    tokens.values.removeIf { it.second <= now }
    tokens[tokenHash] = accountId to expiresAt
  }

  @Synchronized
  override fun tokenAccount(tokenHash: String, now: Long) =
    tokens[tokenHash]?.takeIf { it.second > now }?.first

  @Synchronized
  override fun removeToken(tokenHash: String) {
    tokens.remove(tokenHash)
  }

  @Synchronized
  override fun restrictions(accountId: Long?) =
    restrictionRows.filter { accountId == null || it.accountId == accountId }

  @Synchronized
  override fun addRestriction(r: Restriction) =
    r.copy(id = restrictionRows.size + 1L).also { restrictionRows += it }

  @Synchronized
  override fun liftRestriction(id: Long, by: Long?, at: Long) {
    val i = restrictionRows.indexOfFirst { it.id == id }
    if (i >= 0 && restrictionRows[i].liftedAt == null)
      restrictionRows[i] = restrictionRows[i].copy(liftedBy = by, liftedAt = at)
  }
}

/** Remembers who owns each character that has been checkpointed, and restores them by name. */
class MemoryPersistence : Persistence {
  val saved = LinkedHashMap<String, Long?>()

  override fun checkpoint(world: World, cause: String, immediate: Boolean) {
    for (p in world.players) saved[p.name] = p.accountId
  }

  override fun characters(accountId: Long) = saved.filterValues { it == accountId }.keys.toList()

  override fun savedCharacter(name: String) =
    saved.entries
      .firstOrNull { it.key.equals(name, ignoreCase = true) }
      ?.let { SavedCharacter(it.key, it.value) }

  override fun loadCharacter(world: World, name: String): Player? {
    val c = savedCharacter(name) ?: return null
    return world.spawnPlayer(c.name, HALL, accountId = c.accountId)
  }
}

/** Cheap Argon2id that counts its calls. */
class CountingHasher(
  private val inner: PasswordHasher = Argon2Hasher(memoryKiB = 8, iterations = 1)
) : PasswordHasher {
  val hashes = AtomicInteger()
  val verifies = AtomicInteger()

  override fun hash(password: String) = inner.hash(password).also { hashes.incrementAndGet() }

  override fun verify(password: String, hash: String) =
    inner.verify(password, hash).also { verifies.incrementAndGet() }
}

/** Records text, prompts as `[prompt]`, and echo changes as `{hide}`/`{show}`. */
class RecordingSink : OutputSink {
  private val all = StringBuffer()
  private var mark = 0

  @Volatile var closed: String? = null

  override fun send(text: String) {
    all.append(text)
  }

  override fun prompt(text: String) {
    all.append("[$text]")
  }

  override fun hideInput(hidden: Boolean) {
    all.append(if (hidden) "{hide}" else "{show}")
  }

  override fun close(reason: String) {
    all.append(reason).append('\n')
    closed = reason
  }

  val transcript: String
    get() = all.toString()

  /** Output since the last call. */
  @Synchronized
  fun drain(): String = all.toString().let { s -> s.substring(mark).also { mark = s.length } }
}

/**
 * A headless world with the account login. Background work and the events it posts are queued, and
 * [settle] runs them, as a server's executor and world loop would.
 */
class LoginFixture(
  val settings: LoginSettings = LoginSettings(),
  val hasher: CountingHasher = CountingHasher(),
) {
  val store = MemoryAccountStore()

  /** Wall clock, milliseconds. */
  var now = 1_000_000_000L
  val service = AccountService(store, hasher, { now })
  val limiter = LoginLimiter { now }
  private val work = ArrayDeque<Runnable>()
  private val events = ArrayDeque<WorldControlEvent>()
  val w =
    World(
      testProtos(),
      FixtureSchemas.FANTASY.schema,
      FixtureRules(),
      config = WorldConfig(seed = 1, strict = true),
    )
  val persistence = MemoryPersistence()
  private var nextSession = 1L

  init {
    w.persistence = persistence
    w.login =
      AccountLogin(
        service,
        HALL,
        { work.addLast(it) },
        { synchronized(events) { events.addLast(it) } },
        settings,
        limiter,
      )
  }

  /** Runs queued background work and delivers its results until nothing is left. */
  fun settle() {
    while (true) {
      val e = synchronized(events) { events.removeFirstOrNull() }
      if (e != null) {
        w.handle(e)
        continue
      }
      val job = work.removeFirstOrNull() ?: return
      job.run()
    }
  }

  /** Runs queued background work, holding back the world-thread continuations it posts. */
  fun runWork() {
    while (true) work.removeFirstOrNull()?.run() ?: return
  }

  /** Background jobs waiting to run. */
  val queued: Int
    get() = work.size

  fun account(
    login: String,
    password: String,
    role: gups.core.Role = gups.core.Role.PLAYER,
  ): Account = store.create(Account(0, login, hasher.hash(password), role))!!

  fun connect(remote: String = "10.0.0.1") =
    Client(SessionId(nextSession++), remote).also { it.connect() }

  inner class Client(val id: SessionId, val remote: String) {
    val sink = RecordingSink()

    fun connect() = w.handle(WorldControlEvent.Connect(id, sink, remote, "telnet"))

    /** Types [line] and lets background work finish. */
    fun send(line: String): String {
      type(line)
      settle()
      return sink.drain()
    }

    /** Types [line] without running background work. */
    fun type(line: String) = w.handle(ClientInputEvent(id, line, 0))

    val player: Player?
      get() = w.players.firstOrNull { it.session == id }

    /** Logs in and picks [character], or creates it when [create]. */
    fun play(login: String, password: String, character: String, create: Boolean = false): String {
      send(login)
      send(password)
      if (create) send("new")
      return send(character)
    }
  }
}
