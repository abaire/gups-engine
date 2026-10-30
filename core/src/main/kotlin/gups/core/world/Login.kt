package gups.core.world

import gups.core.Label
import gups.core.Role
import gups.core.SessionId
import gups.core.model.Player

/**
 * Handles incoming client sessions before they enter the game playing an active character.
 *
 * Runs strictly on the world simulation thread. In production, `auth` provides account login; in
 * local developer mode, [DevLogin] provides passwordless login.
 */
interface LoginHandler {
  /** Invoked when a new client session connects to the world. */
  fun connected(world: World, session: Session)

  /** Invoked when a client session in the pre-game login phase submits an input line. */
  fun input(world: World, session: Session, line: String)

  /**
   * Invoked when background authentication work completed off the world thread delivers its outcome
   * back via a control event.
   */
  fun completed(world: World, session: Session, outcome: Any) = Unit

  /** Invoked when a client session disconnects or closes during login. */
  fun disconnected(world: World, session: SessionId) = Unit

  /** Invoked once per simulation pulse to advance login prompt timeouts or session cleanup. */
  fun pulse(world: World) = Unit

  /** Invoked when an account is disabled, allowing active login flows to terminate. */
  fun accountDisabled(world: World, accountId: Long) = Unit

  /** Invoked when an account is re-enabled. */
  fun accountEnabled(world: World, accountId: Long) = Unit
}

/**
 * Developer mode login handler for local connections (`--dev`).
 *
 * Automatically assigns instructor privileges to local loopback connections. Players take over
 * existing characters, restore saved characters, or create new ones without passwords.
 */
class DevLogin(private val start: Label, private val accountId: Long? = null) : LoginHandler {
  private val flows = HashMap<SessionId, CreationFlow>()

  override fun disconnected(world: World, session: SessionId) {
    flows.remove(session)
  }

  override fun connected(world: World, session: Session) {
    if (!isLoopback(session.remote)) {
      world.sendSession(session, world.msg(LoginMsg.DEV_LOCAL_ONLY))
      world.closeSession(session, "not a local connection")
      return
    }
    world.sendSession(session, world.msg(LoginMsg.DEV_WELCOME))
    world.flush(session)
    session.sink.prompt(world.msg(LoginMsg.DEV_PROMPT))
  }

  override fun input(world: World, session: Session, line: String) {
    val activeFlow = flows[session.id]
    if (activeFlow != null) {
      activeFlow.input(line)
      return
    }
    val name = line.trim().lowercase().replaceFirstChar { it.uppercase() }
    if (!NAME.matches(name)) {
      world.sendSession(session, world.msg(LoginMsg.NAME_INVALID))
      return askName(world, session)
    }
    val existing = world.players.firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (existing == null) {
      world.persistence.maintenance?.let { why ->
        world.sendSession(session, why)
        return world.closeSession(session, "maintenance")
      }
    }
    if (existing != null) {
      world.takeOver(existing)
      claim(existing)
      return world.enterGame(session, existing, null)
    }

    val saved = world.persistence.loadCharacter(world, name)
    if (saved != null) {
      claim(saved)
      if (!world.hasMissingChoices(saved))
        return world.enterGame(
          session,
          saved,
          world.msg(KernelMsg.WELCOME_BACK, "name" to saved.name),
        )
      world.unload(saved)
      world.sendSession(session, world.msg(LoginMsg.CREATION_HAS_CHOICES, "name" to saved.name))
      startFlow(world, session, saved.name, newCharacter = false, saved.choices.toMap()) { outcome
        ->
        if (outcome !is CreationOutcome.Completed) return@startFlow askName(world, session)
        if (world.players.any { it.name.equals(name, ignoreCase = true) }) {
          world.sendSession(session, world.msg(LoginMsg.NAME_TAKEN))
          return@startFlow askName(world, session)
        }
        world.persistence.maintenance?.let { why ->
          world.sendSession(session, why)
          return@startFlow world.closeSession(session, "maintenance")
        }
        val p =
          world.persistence.loadCharacter(world, name) ?: return@startFlow askName(world, session)
        claim(p)
        val changes = world.choiceChanges(p, outcome.answers)
        world.choicesRefusal(p, changes)?.let {
          world.unload(p)
          world.sendSession(session, it)
          return@startFlow askName(world, session)
        }
        world.enterGame(session, p, world.msg(KernelMsg.WELCOME_BACK, "name" to p.name))
        world.setChoices(p, changes)?.let { world.send(p, it) }
        world.creation?.created(world, p, newCharacter = false)
      }
      return
    }

    if (world.creation == null) {
      create(world, session, name, start, emptyMap())
      return
    }
    startFlow(world, session, name, newCharacter = true, emptyMap()) { outcome ->
      if (outcome !is CreationOutcome.Created) return@startFlow askName(world, session)
      if (
        world.players.any { it.name.equals(name, ignoreCase = true) } ||
          world.persistence.savedCharacter(name) != null
      ) {
        world.sendSession(session, world.msg(LoginMsg.NAME_TAKEN))
        return@startFlow askName(world, session)
      }
      world.persistence.maintenance?.let { why ->
        world.sendSession(session, why)
        return@startFlow world.closeSession(session, "maintenance")
      }
      val room =
        try {
          world.creationStartRoom(outcome.answers, start)
        } catch (e: Exception) {
          world.diagnose("plugin", "creation provider startRoom() failed: ${e.message}", e)
          world.sendSession(session, world.msg(LoginMsg.CREATION_PROVIDER_FAILED))
          return@startFlow askName(world, session)
        }
      val p = create(world, session, name, room, outcome.answers)
      world.creation?.created(world, p, newCharacter = true)
    }
  }

  private fun startFlow(
    world: World,
    session: Session,
    name: String,
    newCharacter: Boolean,
    answers: Map<String, String>,
    onOutcome: (CreationOutcome) -> Unit,
  ) {
    val flow =
      CreationFlow(world, session, name, newCharacter, answers) { outcome ->
        flows.remove(session.id)
        onOutcome(outcome)
      }
    flows[session.id] = flow
    flow.start()
  }

  private fun create(
    world: World,
    session: Session,
    name: String,
    room: Label,
    answers: Map<String, String>,
  ): Player {
    val p = world.spawnPlayer(name, room, accountId = accountId, choices = answers)
    world.requestImmediate("create")
    claim(p)
    world.enterGame(session, p, world.msg(KernelMsg.WELCOME_NEW, "name" to p.name))
    return p
  }

  /** The local instructor plays every character; one saved before the account existed joins it. */
  private fun claim(p: Player) {
    if (p.accountId == null && accountId != null) {
      p.accountId = accountId
      p.dirty = true
    }
    p.role = Role.INSTRUCTOR
    p.idleExempt = true
  }

  private fun askName(world: World, session: Session) {
    world.flush(session)
    session.sink.prompt(world.msg(LoginMsg.DEV_PROMPT))
  }

  companion object {
    private val NAME = Regex("[A-Z][a-z]{1,15}")

    /** True if [remote] represents a local loopback IP address. */
    fun isLoopback(remote: String): Boolean =
      remote in setOf("local", "localhost", "::1", "0:0:0:0:0:0:0:1") || remote.startsWith("127.")
  }
}

/**
 * Disconnects any prior session playing [p] with a message indicating another login took over the
 * character.
 */
fun World.takeOver(p: Player) {
  p.session?.let { old -> session(old)?.let { closeSession(it, msg(KernelMsg.TAKEN_OVER)) } }
}

/**
 * Binds [session] to player [p], displays [welcome] message, echoes reconnection to the room, and
 * describes the room to the player.
 */
fun World.enterGame(session: Session, p: Player, welcome: String?) {
  attach(session.id, p)
  send(p, welcome ?: msg(KernelMsg.RECONNECTED))
  flushImmediate()
  if (welcome == null) echo(p.room, msg(KernelMsg.RECONNECTED_ROOM, "name" to p.name), p)
  send(p, describeRoom(p))
}
