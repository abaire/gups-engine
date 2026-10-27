package gups.core.world

import gups.core.Label
import gups.core.Role
import gups.core.SessionId
import gups.core.model.Player

/**
 * Handles a session until it plays a character. Runs on the world thread. The account login of
 * `auth` serves `--prod`; [DevLogin] serves `--dev`.
 */
interface LoginHandler {
  fun connected(world: World, session: Session)

  fun input(world: World, session: Session, line: String)

  /** Work this handler started off the world thread finished (`WorldControlEvent.AuthResult`). */
  fun completed(world: World, session: Session, outcome: Any) = Unit

  /** [session] closed, from either side; any login state it had can go. */
  fun disconnected(world: World, session: SessionId) = Unit

  /** Called once per pulse, for example to time out idle login prompts. */
  fun pulse(world: World) = Unit

  /** An account was disabled; pre-game sessions for it are closed. */
  fun accountDisabled(world: World, accountId: Long) = Unit

  /** An account was re-enabled. */
  fun accountEnabled(world: World, accountId: Long) = Unit
}

/**
 * The `--dev` login: a passwordless local instructor account that only accepts connections from
 * this machine. Each name is a character of that account: one already in the world is taken over, a
 * saved one is restored, any other is created. With a database, the account is a real one,
 * `instructor`, and [accountId] is its id.
 */
class DevLogin(private val start: Label, private val accountId: Long? = null) : LoginHandler {
  override fun connected(world: World, session: Session) {
    if (!isLoopback(session.remote)) {
      world.sendSession(
        session,
        "This development server only accepts connections from this machine.",
      )
      world.closeSession(session, "not a local connection")
      return
    }
    world.sendSession(
      session,
      "Welcome to GUPS (development server). You are the local instructor.",
    )
    world.flush(session)
    session.sink.prompt("By what name do you wish to be known? ")
  }

  override fun input(world: World, session: Session, line: String) {
    val name = line.trim().lowercase().replaceFirstChar { it.uppercase() }
    if (!NAME.matches(name)) {
      world.sendSession(session, "Names are 2 to 16 letters.")
      world.flush(session)
      session.sink.prompt("By what name do you wish to be known? ")
      return
    }
    val existing = world.players.firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (existing == null) {
      world.persistence.maintenance?.let { why ->
        world.sendSession(session, why)
        return world.closeSession(session, "maintenance")
      }
    }
    existing?.let { world.takeOver(it) }
    val saved = if (existing == null) world.persistence.loadCharacter(world, name) else null
    val p: Player =
      existing
        ?: saved
        ?: world.spawnPlayer(name, start, accountId = accountId).also {
          world.requestImmediate("create")
        }
    if (p.accountId == null && accountId != null) {
      // A character saved before the instructor account existed joins it.
      p.accountId = accountId
      p.dirty = true
    }
    p.role = Role.INSTRUCTOR
    p.idleExempt = true
    world.enterGame(
      session,
      p,
      if (existing != null) null
      else if (saved != null) "Welcome back, ${p.name}." else "Welcome, ${p.name}.",
    )
  }

  companion object {
    private val NAME = Regex("[A-Z][a-z]{1,15}")

    fun isLoopback(remote: String): Boolean =
      remote in setOf("local", "localhost", "::1", "0:0:0:0:0:0:0:1") || remote.startsWith("127.")
  }
}

/**
 * Closes the connection playing [p], if any, telling it why: another login is taking the character
 * over.
 */
fun World.takeOver(p: Player) {
  p.session?.let { old ->
    session(old)?.let { closeSession(it, "Someone else has taken over this character.") }
  }
}

/**
 * Binds [session] to [p] and greets it with [welcome], or with "Reconnected." when [welcome] is
 * null because [p] was already in the world. Saves a newly created character at once.
 */
fun World.enterGame(session: Session, p: Player, welcome: String?) {
  attach(session.id, p)
  send(p, welcome ?: "Reconnected.")
  flushImmediate()
  if (welcome == null) echo(p.room, "${p.name} has reconnected.", p)
  send(p, describeRoom(p))
}
