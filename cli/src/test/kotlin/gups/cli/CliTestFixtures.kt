package gups.cli

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.ContainerProto
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.TrackSize
import gups.core.model.ZoneProto
import gups.core.rules.DefaultRules
import gups.core.world.DevLogin
import gups.core.world.ScriptHost
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.handle
import gups.persist.SqlitePersistence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

fun tempWorld(files: Map<String, String>): Path {
    val root = Files.createTempDirectory("gups-world")
    for ((path, text) in files) {
        val p = root.resolve(path)
        p.parent.createDirectories()
        p.writeText(text.trimIndent() + "\n")
    }
    return root
}

fun tempDb(): Path = Files.createTempDirectory("gups-db").resolve("world.db")

object P {
    val HALL = Label("//t:hall")
    val VAULT = Label("//t:vault")
    val RAT = Label("//t:rat")
    val COIN = Label("//t:coin")
    val BAG = Label("//t:bag")
    val GEM = Label("//t:gem")

    fun rooms(ratHp: Int = 10) = listOf(
        RoomProto(
            HALL, "The Hall", size = TrackSize.LARGE,
            exits = listOf(ExitProto(Direction.SOUTH, VAULT, door = DoorProto(DoorState.CLOSED))),
            spawns = listOf(SpawnRule(RAT, count = 2, respawnPulses = 300)),
        ),
        RoomProto(VAULT, "The Vault", exits = listOf(ExitProto(Direction.NORTH, HALL, door = DoorProto(DoorState.CLOSED)))),
    )

    fun protos(
        ratHp: Int = 10,
        ratVersion: Int = 1,
        withGem: Boolean = true,
        withRat: Boolean = true,
    ) = Prototypes(
        rooms = if (withRat) rooms(ratHp) else rooms(ratHp).map { it.copy(spawns = emptyList()) },
        mobs = if (withRat) listOf(MobProto(RAT, "a rat", listOf("rat"), hp = ratHp, version = ratVersion)) else emptyList(),
        items = listOfNotNull(
            ItemProto(COIN, "a coin", listOf("coin"), stackable = true),
            ItemProto(BAG, "a bag", listOf("bag"), container = ContainerProto()),
            ItemProto(GEM, "a gem", listOf("gem")).takeIf { withGem },
        ),
        zones = listOf(ZoneProto("t", resetPulses = 600)),
    )
}

class PersistWorld(
    val file: Path,
    protos: Prototypes = P.protos(),
    fresh: Boolean = false,
    scripts: ScriptHost = ScriptHost.NONE,
    checkpointPulses: Long = gups.core.world.DEFAULT_CHECKPOINT_PULSES,
) {
    val persistence = SqlitePersistence.open(file)
    val boot = persistence.boot(protos, fresh)
    val w = World(protos, DefaultRules(), scripts, WorldConfig(seed = 1, checkInvariants = true, strict = true, checkpointPulses = checkpointPulses), boot)
    val report = persistence.restore(w, fresh)
    private var nextSession = 1L
    val sinks = mutableMapOf<String, CapturingSink>()

    init {
        w.login = DevLogin(P.HALL)
    }

    fun login(name: String): Player {
        val id = SessionId(nextSession++)
        val sink = CapturingSink()
        w.handle(WorldControlEvent.Connect(id, sink, "127.0.0.1"))
        w.handle(gups.core.ClientInputEvent(id, name, 0))
        sinks[name] = sink
        return w.players.first { it.name == name }
    }

    fun save() {
        persistence.checkpoint(w, "test", immediate = false)
        check(persistence.writer.flush()) { "writer didn't finish" }
    }

    fun close() = persistence.close()
}
