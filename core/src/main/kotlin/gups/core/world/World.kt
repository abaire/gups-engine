package gups.core.world

import gups.core.AttrValue
import gups.core.COMBAT_ROUND_PULSES
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.Rng
import gups.core.SessionId
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Actor
import gups.core.model.DEFAULT_STATS
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Room
import gups.core.model.ScriptTimer
import gups.core.model.SpawnRule
import gups.core.rules.DefaultRules
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import java.util.TreeMap

class WorldConfig(
    val seed: Long = 0,
    /** Check the §3.8 invariants after every pulse (`--dev` and the harness, SPEC §6.5). */
    val checkInvariants: Boolean = false,
    /** Rethrow engine exceptions instead of logging them. For tests. */
    val strict: Boolean = false,
)

/** A logged engine or script problem. Persisted to the diagnostics table from M4. */
data class Diagnostic(val pulse: Long, val kind: String, val message: String, val cause: Throwable? = null)

class InvariantViolation(val violations: List<String>) :
    IllegalStateException("World invariants violated:\n  " + violations.joinToString("\n  "))

/** Tracks the live instances of one room spawn rule and its pending respawn. */
class SpawnState internal constructor(val room: Room, val rule: SpawnRule) {
    internal val live = sortedSetOf<EntityId>()
    internal var respawnAt: Long? = null
}

/**
 * The world graph and its scheduler (SPEC §5.1). Not thread-safe by design: everything runs
 * on the world thread, driven by [gups.core.loop.WorldLoop] or directly by a headless test.
 */
class World(
    val protos: Prototypes,
    val rules: RulesEngine = DefaultRules(),
    val scripts: ScriptHost = ScriptHost.NONE,
    val config: WorldConfig = WorldConfig(),
) {
    /** Game time (SPEC §3.1). */
    var pulse: Long = 0
        private set

    val rng = Rng(config.seed)
    val commands = CommandRegistry()

    private var nextEntityId = 1L
    private var nextHandle = 1L
    internal val entities = HashMap<EntityId, Entity>()
    internal val actorIndex = TreeMap<EntityId, Actor>()
    private val roomsByLabel = LinkedHashMap<Label, Room>()
    internal val sessions = LinkedHashMap<SessionId, Session>()
    internal val spawnStates = mutableListOf<SpawnState>()
    private val diagnosticLog = mutableListOf<Diagnostic>()

    // Initiative-tier state (SPEC §5.4.1).
    internal var snapshot: TierSnapshot? = null
    internal val deferred = mutableListOf<Deferred>()
    internal val removedThisPhase = HashSet<EntityId>()
    internal var cascadeDepth = 0

    var shutdownAt: Long? = null
        internal set
    val shutdownRequested: Boolean get() = shutdownAt?.let { pulse >= it } ?: false

    init {
        for (proto in protos.rooms.values) {
            val room = Room(allocateId(), proto)
            register(room)
            roomsByLabel[proto.label] = room
        }
        for (room in roomsByLabel.values) {
            for (exit in room.exits.values) {
                require(exit.to in roomsByLabel) { "${room.label} exit ${exit.direction} leads to unknown room ${exit.to}" }
            }
            for (rule in room.proto.spawns) {
                protos.mob(rule.label)
                spawnStates += SpawnState(room, rule).also(::fillSpawn)
            }
        }
    }

    val rooms: Collection<Room> get() = roomsByLabel.values

    /** Actors in the world, ascending [EntityId]. */
    val actors: List<Actor> get() = actorIndex.values.toList()
    val players: List<Player> get() = actorIndex.values.filterIsInstance<Player>()
    val diagnostics: List<Diagnostic> get() = diagnosticLog

    fun room(label: Label): Room = roomsByLabel[label] ?: throw NoSuchElementException("No room $label")

    fun roomOrNull(label: Label): Room? = roomsByLabel[label]

    fun entity(id: EntityId): Entity? = entities[id]

    fun actor(id: EntityId): Actor? = actorIndex[id]

    fun item(id: EntityId): Item? = entities[id] as? Item

    internal fun allocateId(): EntityId = EntityId(nextEntityId++)

    /** Ids for timers and affects, which share one `(due, id)` ordering. */
    internal fun allocateHandle(): Long = nextHandle++

    internal fun register(e: Entity) {
        entities[e.id] = e
    }

    internal fun diagnose(kind: String, message: String, cause: Throwable? = null) {
        diagnosticLog += Diagnostic(pulse, kind, message, cause)
    }

    /** Runs [block], logging an engine exception and aborting only that action (SPEC §6.4). */
    internal inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: InvariantViolation) {
            throw e
        } catch (e: Exception) {
            if (config.strict) throw e
            diagnose("engine", "$what: ${e.message}", e)
        }
    }

    // ---- Spawning ---------------------------------------------------------------------------

    fun spawnPlayer(
        name: String,
        at: Label,
        level: Int = 1,
        hp: Int = 20,
        mana: Int = 20,
        move: Int = 100,
        stats: Map<String, Int> = DEFAULT_STATS,
        index: Int = 0,
    ): Player {
        require(players.none { it.name.equals(name, ignoreCase = true) }) { "A player named $name is already in the world" }
        val room = room(at)
        val p = Player(allocateId(), name, room, index.coerceIn(0, room.trackSize - 1), recallRoom = at)
        p.level = level
        p.baseStats.putAll(stats)
        p.hp = hp
        p.maxHp = hp
        p.mana = mana
        p.maxMana = mana
        p.move = move
        p.maxMove = move
        p.lastInputPulse = pulse
        enter(p, room, p.trackIndex)
        return p
    }

    fun spawnMob(label: Label, at: Room, index: Int = 0): Mob {
        val proto = protos.mob(label)
        val m = Mob(allocateId(), proto, at, index.coerceIn(0, at.trackSize - 1))
        m.level = proto.level
        m.baseStats.putAll(proto.stats)
        m.hp = proto.hp
        m.maxHp = proto.hp
        m.mana = proto.mana
        m.maxMana = proto.mana
        m.move = proto.move
        m.maxMove = proto.move
        m.armor = proto.armor
        m.resists.putAll(proto.resists)
        m.skills.putAll(proto.skills)
        enter(m, at, m.trackIndex)
        invokeOn(m, HookName.ON_SPAWN, HookContext(room = at))
        return m
    }

    private fun enter(a: Actor, room: Room, index: Int) {
        register(a)
        actorIndex[a.id] = a
        a.inWorld = true
        placeActor(a, room, index)
    }

    /** Moves [a] within the world graph; the only writer of actor position. */
    internal fun placeActor(a: Actor, room: Room, index: Int) {
        a.room.actorIndex.remove(a.id)
        a.room = room
        a.trackIndex = index.coerceIn(0, room.trackSize - 1)
        room.actorIndex[a.id] = a
        a.dirty = true
    }

    /** Takes [a] and everything it carries out of the world (mob death, quit). */
    internal fun removeActor(a: Actor) {
        a.room.actorIndex.remove(a.id)
        actorIndex.remove(a.id)
        entities.remove(a.id)
        a.inWorld = false
        removedThisPhase += a.id
        for (item in a.inventoryIndex.values + a.equipmentIndex.values) forgetItemTree(item)
        if (a is Mob) {
            a.spawnedBy?.let { s ->
                s.live.remove(a.id)
                if (s.respawnAt == null) s.respawnAt = pulse + s.rule.respawnPulses
            }
        }
    }

    private fun fillSpawn(s: SpawnState) {
        while (s.live.size < s.rule.count) {
            val m = spawnMob(s.rule.label, s.room, s.rule.atIndex)
            m.spawnedBy = s
            s.live += m.id
        }
        s.respawnAt = null
    }

    // ---- Timers (verbs `after` / `cancel`, SPEC §4.5) -----------------------------------------

    fun after(owner: Entity, pulses: Int, fn: HookRef, args: Map<String, AttrValue> = emptyMap()): Long {
        require(pulses >= 1) { "after(): pulses must be at least 1" }
        val t = ScriptTimer(allocateHandle(), fn, args, due = pulse + pulses)
        owner.timers += t
        owner.dirty = true
        return t.id
    }

    fun cancel(owner: Entity, timerId: Long): Boolean = owner.timers.removeIf { it.id == timerId }.also {
        if (it) owner.dirty = true
    }

    // ---- The pulse (SPEC §5.1) ----------------------------------------------------------------

    /** Advances game time by one pulse and runs every phase in order. */
    fun runPulse() {
        pulse++
        runTimers()
        runTiers(actorIndex.values.toList(), ::actionPulse)
        if (pulse % COMBAT_ROUND_PULSES == 0L) runCombatRound()
        if (pulse % WORLD_TICK_PULSES == 0L) runWorldTick()
        // Phase 5 (deferred perception) and phase 6 (bubble maintenance) arrive with M5. Until
        // then every room is simulated.
        expireSessions()
        flushOutput()
        if (config.checkInvariants) {
            val violations = checkInvariants()
            if (violations.isNotEmpty()) throw InvariantViolation(violations)
        }
    }

    fun runPulses(n: Int) = repeat(n) { runPulse() }

    /** Phase 1: script timers, affect ticks and expiries due now, ordered by `(due, id)`. */
    private fun runTimers() {
        class Due(val due: Long, val id: Long, val run: () -> Unit)
        val due = ArrayList<Due>()
        for (e in entities.values) {
            for (t in e.timers) {
                val at = t.due ?: continue
                if (at <= pulse) {
                    due += Due(at, t.id) {
                        if (e.timers.remove(t)) {
                            e.dirty = true
                            invokeRef(e, t.fn, HookContext(args = t.args))
                        }
                    }
                }
            }
        }
        for (a in actorIndex.values) {
            for (aff in a.affects) {
                val every = aff.tickEvery
                when {
                    aff.expiresAt <= pulse -> due += Due(aff.expiresAt, aff.id) { expireAffect(a, aff) }
                    every != null && aff.onTick != null && (pulse - aff.appliedAt) % every == 0L ->
                        due += Due(pulse, aff.id) { invokeRef(a, aff.onTick, HookContext(affect = aff)) }
                }
            }
        }
        due.sortWith(compareBy<Due>({ it.due }, { it.id }))
        for (d in due) guarded("timer ${d.id}") { d.run() }
    }

    /** Phase 2 for one actor (SPEC §5.1). */
    private fun actionPulse(a: Actor) {
        val c = a.casting
        when {
            c != null -> advanceCast(a, c)
            a.waitState > 0 -> a.waitState--
            a.actionQueue.isNotEmpty() -> runQueued(a, a.actionQueue.removeFirst())
            a is Mob && pulse - a.lastThink >= a.thinkEvery -> think(a)
        }
    }

    /** Phase 3: roll initiative, then one auto-attack per engaged actor (SPEC §5.4). */
    private fun runCombatRound() {
        for (a in actorIndex.values) a.initiative = null
        val engaged = actorIndex.values.filter { it.isEngaged }
        for (a in engaged) guarded("initiative of $a") { a.initiative = rules.initiative(RulesContext(this, a)) }
        runTiers(engaged) { a ->
            if (a.isEngaged) {
                autoAttack(a)
                invokeOn(a, HookName.ON_COMBAT_TICK, HookContext(actor = a, room = a.room))
            }
        }
    }

    /** Phase 4 (SPEC §5.1). Checkpoint capture arrives with persistence in M4. */
    private fun runWorldTick() {
        for (a in actorIndex.values.toList()) {
            if (a.hasFlag("no_regen")) continue
            guarded("regen of $a") {
                val r = rules.regen(RulesContext(this, a))
                a.hp = (a.hp + r.hp).coerceAtMost(a.maxHp)
                a.mana = (a.mana + r.mana).coerceAtMost(a.maxMana)
                a.move = (a.move + r.move).coerceAtMost(a.maxMove)
                a.dirty = true
            }
        }
        for (e in entities.values.toList()) if (e !is Item) invokeOn(e, HookName.ON_TICK, HookContext())
        for (s in spawnStates) {
            val at = s.respawnAt ?: continue
            if (at <= pulse) guarded("respawn in ${s.room.label}") { fillSpawn(s) }
        }
        for (item in entities.values.filterIsInstance<Item>()) {
            val at = item.decayAt ?: continue
            if (at <= pulse) guarded("decay of $item") { decay(item) }
        }
    }

    // ---- Initiative tiers (SPEC §5.4.1) -------------------------------------------------------

    /**
     * Runs [act] for each participant tier by tier: engaged actors grouped by initiative from
     * highest to lowest, then everyone else. Same-tier actions validate against a snapshot
     * taken when the tier starts; deaths and room transitions commit when it ends.
     */
    internal fun runTiers(participants: List<Actor>, act: (Actor) -> Unit) {
        removedThisPhase.clear()
        val (ranked, rest) = participants.partition { it.isEngaged && it.initiative != null }
        val tiers = ranked.groupBy { it.initiative!! }.toSortedMap(reverseOrder()).values + listOf(rest)
        for (tier in tiers) {
            val members = tier.filter { it.inWorld && it.id !in removedThisPhase }.sortedBy { it.id }
            if (members.isEmpty()) continue
            val snap = TierSnapshot(actorIndex.values)
            snapshot = snap
            try {
                for (a in members) if (snap.canAct(a)) guarded("action of $a") { act(a) }
            } finally {
                snapshot = null
            }
            commitDeferred()
        }
        removedThisPhase.clear()
    }

    /** Runs [op] at the end of the current tier, or now if no tier is running. */
    internal fun deferRemoval(actor: Actor, kind: Deferred.Kind, op: () -> Unit) {
        if (snapshot == null) op() else deferred += Deferred(actor, kind, deferred.size, op)
    }

    private fun commitDeferred() {
        val ops = deferred.sortedWith(compareBy<Deferred>({ it.actor.id }, { it.seq }))
        deferred.clear()
        for ((_, group) in ops.groupBy { it.actor.id }) {
            val actor = group.first().actor
            // A death that still stands wins over the actor's own movement.
            val dies = group.any { it.kind == Deferred.Kind.DEATH } && actor.inWorld && actor.hp <= 0
            for (op in group) {
                if (dies && op.kind != Deferred.Kind.DEATH) continue
                guarded("end-of-tier ${op.kind} of $actor") { op.run() }
            }
        }
    }

    // ---- Views used by validation -------------------------------------------------------------

    /** Where [a] counts as being for validation: the tier snapshot while one runs, else live. */
    fun roomOf(a: Actor): Room = snapshot?.room(a) ?: a.room

    fun indexOf(a: Actor): Int = snapshot?.index(a) ?: a.trackIndex

    fun canAct(a: Actor): Boolean = snapshot?.canAct(a) ?: (a.inWorld && !a.isStunned)

    /** True if [a] is in the world and, for validation, in [room]. */
    fun isPresent(a: Actor, room: Room): Boolean = a.inWorld && roomOf(a) === room

    /** True if [item] is on the ground in [room], or carried by an actor there. */
    fun isPresent(item: Item, room: Room): Boolean = when (val loc = item.location) {
        is ItemLocation.InRoom -> loc.room == room.id && item.id in entities
        is ItemLocation.Carried -> actor(loc.actor)?.let { isPresent(it, room) } ?: false
        is ItemLocation.Equipped -> actor(loc.actor)?.let { isPresent(it, room) } ?: false
        is ItemLocation.Contained -> item(loc.container)?.let { isPresent(it, room) } ?: false
    }
}

/** Positions and ability to act of every actor, captured when an initiative tier starts. */
internal class TierSnapshot(actors: Collection<Actor>) {
    private val rooms = HashMap<EntityId, Room>()
    private val indices = HashMap<EntityId, Int>()
    private val able = HashMap<EntityId, Boolean>()

    init {
        for (a in actors) {
            rooms[a.id] = a.room
            indices[a.id] = a.trackIndex
            able[a.id] = a.inWorld && !a.isStunned
        }
    }

    fun room(a: Actor): Room = rooms[a.id] ?: a.room

    fun index(a: Actor): Int = indices[a.id] ?: a.trackIndex

    fun canAct(a: Actor): Boolean = able[a.id] ?: (a.inWorld && !a.isStunned)
}

/** Something that takes an actor out of the fight, committed at the end of the tier. */
internal class Deferred(val actor: Actor, val kind: Kind, val seq: Int, val run: () -> Unit) {
    enum class Kind { DEATH, TRANSITION }
}
