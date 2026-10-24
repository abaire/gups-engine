package gups.core.world

import gups.core.Label
import gups.core.Role
import gups.core.model.Player

/**
 * Handles a session until it plays a character (SPEC §5.12). Runs on the world thread. The
 * account-based login arrives with `auth` in M6; [DevLogin] serves `--dev`.
 */
interface LoginHandler {
    fun connected(world: World, session: Session)

    fun input(world: World, session: Session, line: String)
}

/**
 * The `--dev` login (SPEC §2.4, §5.12): a passwordless local instructor account that only
 * accepts connections from this machine. Each name is a character of that account: one already
 * in the world is taken over, a saved one is restored, any other is created (D-082).
 */
class DevLogin(private val start: Label) : LoginHandler {
    override fun connected(world: World, session: Session) {
        if (!isLoopback(session.remote)) {
            world.sendSession(session, "This development server only accepts connections from this machine.")
            world.closeSession(session, "not a local connection")
            return
        }
        world.sendSession(session, "Welcome to GUPS (development server). You are the local instructor.")
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
        existing?.session?.let { old -> world.session(old)?.let { world.closeSession(it, "Someone else has taken over this character.") } }
        val saved = if (existing == null) world.persistence.loadCharacter(world, name) else null
        val p: Player = existing ?: saved ?: world.spawnPlayer(name, start).also { world.requestImmediate("create") }
        p.role = Role.INSTRUCTOR
        p.idleExempt = true
        world.attach(session.id, p)
        world.send(p, if (existing != null) "Reconnected." else if (saved != null) "Welcome back, ${p.name}." else "Welcome, ${p.name}.")
        world.flushImmediate()
        if (existing != null) world.echo(p.room, "${p.name} has reconnected.", p)
        world.send(p, world.describeRoom(p))
    }

    companion object {
        private val NAME = Regex("[A-Z][a-z]{1,15}")

        fun isLoopback(remote: String): Boolean =
            remote in setOf("local", "localhost", "::1", "0:0:0:0:0:0:0:1") || remote.startsWith("127.")
    }
}
