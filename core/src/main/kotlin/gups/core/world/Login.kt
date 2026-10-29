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

    fun isLoopback(remote: String): Boolean =
      remote in setOf("local", "localhost", "::1", "0:0:0:0:0:0:0:1") || remote.startsWith("127.")
  }
}

/**
 * Closes the connection playing [p], if any, telling it why: another login is taking the character
 * over.
 */
fun World.takeOver(p: Player) {
  p.session?.let { old -> session(old)?.let { closeSession(it, msg(KernelMsg.TAKEN_OVER)) } }
}

/**
 * Binds [session] to [p] and greets it with [welcome], or with "Reconnected." when [welcome] is
 * null because [p] was already in the world. Saves a newly created character at once.
 */
fun World.enterGame(session: Session, p: Player, welcome: String?) {
  attach(session.id, p)
  send(p, welcome ?: msg(KernelMsg.RECONNECTED))
  flushImmediate()
  if (welcome == null) echo(p.room, msg(KernelMsg.RECONNECTED_ROOM, "name" to p.name), p)
  send(p, describeRoom(p))
}
