package gups.core.world

import gups.core.AttrValue
import gups.core.COMBAT_ROUND_PULSES
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.PULSE_MS
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
  /**
   * Pulses between routine checkpoints, rounded up to world ticks. The default is 30 minutes; a
   * world sets it with `checkpoint_minutes` in `MODULE.gups` (SPEC §5.11).
   */
  val checkpointPulses: Long = DEFAULT_CHECKPOINT_PULSES,
  /**
   * Keep a reality bubble (SPEC §5.9): only rooms near players and other anchors are simulated.
   * Off, every room is always simulated, as headless tests usually want (D-116).
   */
  val bubble: Boolean = false,
  /** `--prod`: `@reload` is refused in favour of deploys (SPEC §1.4, §4.9). */
  val production: Boolean = false,
)

/** 30 minutes of pulses (SPEC §5.11). */
const val DEFAULT_CHECKPOINT_PULSES = 30L * 60 * 1000 / PULSE_MS

/**
 * A logged engine or script problem (SPEC §3.6, §6.2). Persisted to the diagnostics table from M4.
 * Script diagnostics fill in where the failing code lives; engine ones leave the script fields
 * null.
 */
data class Diagnostic(
  val pulse: Long,
  val kind: String,
  val message: String,
  val cause: Throwable? = null,
  /** The package the failure is charged to, for example `zones/tutorial`. */
  val pkg: String? = null,
  /** The prototype whose hook failed. */
  val label: Label? = null,
  val hook: String? = null,
  /** The room the failing code ran for, which a repro bundle snapshots (SPEC §6.2). */
  val room: Label? = null,
  /** The file label, for example `//zones/tutorial:rooms.star`. */
  val file: String? = null,
  val line: Int? = null,
  /** The Starlark call stack. */
  val stack: String? = null,
  /** A one-line summary of the hook's `ctx`. */
  val ctx: String? = null,
  val seed: Long? = null,
)

class InvariantViolation(val violations: List<String>) :
  IllegalStateException("World invariants violated:\n  " + violations.joinToString("\n  "))

/** Tracks the live instances of one room spawn rule and its pending respawn. */
class SpawnState internal constructor(val room: Room, val rule: SpawnRule) {
  internal val live = sortedSetOf<EntityId>()
  internal var respawnAt: Long? = null
}

/**
 * The world graph and its scheduler (SPEC §5.1). Not thread-safe by design: everything runs on the
 * world thread, driven by [gups.core.loop.WorldLoop] or directly by a headless test.
 */
/**
 * How a world starts (SPEC §5.11 startup): fresh from its prototypes, or as the shell that saved
 * state is restored into. Saved rooms keep their ids so references to them survive a restart; new
 * ids start above everything ever saved.
 */
class Boot(
  val pulse: Long = 0,
  val nextEntityId: Long = 1,
  val nextHandle: Long = 1,
  val roomIds: Map<Label, EntityId> = emptyMap(),
  /** False when restoring, so saved mobs come back instead of fresh spawns. */
  val fillSpawns: Boolean = true,
)

/** Applies plugin effect primitives; the plugin host installs one (SPEC §4.15). */
fun interface PluginEffectRunner {
  fun apply(world: World, effect: gups.core.model.Effect.Plugin, caster: Actor, target: Actor?)
}

class World(
  protos: Prototypes,
  val rules: RulesEngine = DefaultRules(),
  val scripts: ScriptHost = ScriptHost.NONE,
  val config: WorldConfig = WorldConfig(),
  boot: Boot = Boot(),
) {
  /** The live prototype table; a reload swaps it (SPEC §5.10). */
  var protos: Prototypes = protos
    internal set

  /** Game time (SPEC §3.1). */
  var pulse: Long = boot.pulse
    private set

  /** Where checkpoints go (SPEC §5.11); nowhere until a persistence layer is attached. */
  var persistence: Persistence = Persistence.NONE

  /**
   * Handles sessions that have no character yet; null answers "You are not playing a character."
   */
  var login: LoginHandler? = null

  /** Called for every diagnostic as it is recorded, for example to log it. */
  var onDiagnostic: ((Diagnostic) -> Unit)? = null

  /**
   * Receives each rules evaluation with its modifier chain, for `@trace rules` (SPEC §4.6, §5.13).
   */
  var onRules: ((Actor?, String) -> Unit)? = null

  // ---- Operations (SPEC §4.9) -----------------------------------------------------------

  /** Wall-clock milliseconds, for restrictions and audit times. */
  var wallClock: () -> Long = System::currentTimeMillis

  /** Where slow work runs and how its result comes back (see [later]); null runs it inline. */
  var background: java.util.concurrent.Executor? = null
  var post: ((gups.core.WorldControlEvent) -> Unit)? = null

  /**
   * Restrictions of every account, as loaded from the account store and kept by `@restrict` (SPEC
   * §4.9).
   */
  var restrictions: List<gups.core.Restriction> = emptyList()

  /** Receives every instructor alert, for the server log (SPEC §6.2). */
  var onAlert: ((String) -> Unit)? = null

  /** The last minute of pulse durations, fed by the world loop. */
  val pulseStats = PulseStats()

  /** Input lines waiting for the world thread, as the world loop counts them. */
  var queueDepth: () -> Int = { 0 }

  /**
   * Called by the world loop after each pulse with its duration in nanoseconds, for metrics (SPEC
   * §4.14).
   */
  var onPulse: ((Long) -> Unit)? = null

  /** Runs `Effect.Plugin`s; does nothing until a plugin host installs a runner (SPEC §4.15). */
  var pluginEffects: PluginEffectRunner = PluginEffectRunner { _, _, _, _ -> }

  /** Plugin GMCP state packages by name, recomputed in phase 7 like the engine's (SPEC §4.15). */
  val pluginGmcp: MutableMap<String, (Player) -> Any?> = LinkedHashMap()

  /** Production deploys (SPEC §5.10), when the server can run them. */
  var deploys: Deploys? = null

  // Alert rate limits, the engine-error window and step samples (SPEC §6.2).
  internal val alertTimes = HashMap<String, Long>()
  internal val ownerAlertTimes = HashMap<String, Long>()
  internal val engineErrors = ArrayDeque<Long>()
  internal val stepSamples = ArrayDeque<Pair<Long, Map<String, Long>>>()
  internal var shutdownReason: String? = null

  internal var slotCache: List<String>? = null

  val rng = Rng(config.seed)
  val commands = CommandRegistry()

  internal var nextEntityId =
    maxOf(boot.nextEntityId, (boot.roomIds.values.maxOfOrNull { it.v } ?: 0) + 1)
  internal var nextHandle = boot.nextHandle
  internal val entities = HashMap<EntityId, Entity>()
  internal val actorIndex = TreeMap<EntityId, Actor>()
  internal val roomsByLabel = LinkedHashMap<Label, Room>()
  internal val sessions = LinkedHashMap<SessionId, Session>()
  internal val spawnStates = mutableListOf<SpawnState>()
  private val diagnosticLog = mutableListOf<Diagnostic>()

  /** Next zone reset per package that declares `reset_pulses` (SPEC §4.1). */
  internal val zoneResets = TreeMap<String, Long>()

  // Persistence bookkeeping (SPEC §5.11), drained by each checkpoint capture.
  internal val removed = LinkedHashSet<EntityId>()
  internal val itemEvents = mutableListOf<ItemEvent>()
  internal val inputs = mutableListOf<InputRecord>()
  internal var immediateCause: String? = null

  /**
   * Entities the last world tick's invariant check found broken; kept out of checkpoints (SPEC
   * §6.5).
   */
  var checkpointExclusions: Set<EntityId> = emptySet()
    private set

  // Initiative-tier state (SPEC §5.4.1).
  internal var snapshot: TierSnapshot? = null
  internal val deferred = mutableListOf<Deferred>()
  internal val removedThisPhase = HashSet<EntityId>()
  internal var cascadeDepth = 0

  // The reality bubble (SPEC §5.9): how many anchors cover each room, and where each anchor was.
  internal val coverage = HashMap<Room, Int>()
  internal val anchorRooms = HashMap<EntityId, Room>()

  // Perception events waiting for phase 5 (SPEC §5.7).
  internal val perceptions = mutableListOf<Perception>()

  /**
   * `path_to` results by `(from, to)` room id, dropped whenever a door, exit or reload changes the
   * graph (SPEC §5.9).
   */
  internal val pathCache = HashMap<Pair<EntityId, EntityId>, List<gups.core.model.Direction>?>()
  internal var inboundCache: HashMap<Room, MutableList<Pair<Room, gups.core.model.Exit>>>? = null

  /** Forgets everything derived from the room graph, after doors, exits or rooms change. */
  internal fun graphChanged() {
    pathCache.clear()
  }

  var shutdownAt: Long? = null
    internal set

  val shutdownRequested: Boolean
    get() = shutdownAt?.let { pulse >= it } ?: false

  init {
    for (proto in protos.rooms.values) {
      val room = Room(boot.roomIds[proto.label] ?: allocateId(), proto)
      register(room)
      roomsByLabel[proto.label] = room
    }
    for (room in roomsByLabel.values) {
      for (exit in room.exits.values) {
        require(exit.to in roomsByLabel) {
          "${room.label} exit ${exit.direction} leads to unknown room ${exit.to}"
        }
      }
      for (rule in room.proto.spawns) {
        protos.mob(rule.label)
        spawnStates += SpawnState(room, rule).also { if (boot.fillSpawns) fillSpawn(it) }
      }
    }
    for (zone in protos.zones.values) zone.resetPulses?.let { zoneResets[zone.pkg] = pulse + it }
    startDormant()
  }

  val rooms: Collection<Room>
    get() = roomsByLabel.values

  /** Actors in the world, ascending [EntityId]. */
  val actors: List<Actor>
    get() = actorIndex.values.toList()

  val players: List<Player>
    get() = actorIndex.values.filterIsInstance<Player>()

  val diagnostics: List<Diagnostic>
    get() = diagnosticLog

  fun room(label: Label): Room =
    roomsByLabel[label] ?: throw NoSuchElementException("No room $label")

  fun roomOrNull(label: Label): Room? = roomsByLabel[label]

  fun entity(id: EntityId): Entity? = entities[id]

  fun actor(id: EntityId): Actor? = actorIndex[id]

  fun item(id: EntityId): Item? = entities[id] as? Item

  /** Every entity in the world, in no particular order. */
  val allEntities: Collection<Entity>
    get() = entities.values

  internal fun allocateId(): EntityId = EntityId(nextEntityId++)

  /** Ids to save: the next entity id and the next timer/affect handle (SPEC §3.6 `meta`). */
  val nextIds: Pair<Long, Long>
    get() = nextEntityId to nextHandle

  /**
   * Asks for an immediate checkpoint once the current action's cascade ends (SPEC §5.11), for
   * example after an item changes hands between players.
   */
  fun requestImmediate(cause: String) {
    if (immediateCause == null) immediateCause = cause
  }

  /** Submits the requested immediate checkpoint, if any. */
  internal fun flushImmediate() {
    val cause = immediateCause ?: return
    immediateCause = null
    guarded("checkpoint") { persistence.checkpoint(this, cause, immediate = true) }
  }

  /** Ids for timers and affects, which share one `(due, id)` ordering. */
  internal fun allocateHandle(): Long = nextHandle++

  internal fun register(e: Entity) {
    entities[e.id] = e
  }

  internal fun diagnose(kind: String, message: String, cause: Throwable? = null) {
    record(Diagnostic(pulse, kind, message, cause))
  }

  /** Logs a diagnostic built elsewhere, for example by the script host. */
  fun record(d: Diagnostic) {
    diagnosticLog += d
    onDiagnostic?.invoke(d)
    persistence.diagnostic(this, d)
    alertFor(d)
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
    accountId: Long? = null,
  ): Player {
    require(players.none { it.name.equals(name, ignoreCase = true) }) {
      "A player named $name is already in the world"
    }
    val room = room(at)
    val p =
      Player(
        allocateId(),
        name,
        room,
        index.coerceIn(0, room.trackSize - 1),
        recallRoom = at,
        accountId = accountId,
      )
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
    // A player who quits keeps their row and their items' rows; a mob is gone for good.
    if (a is Mob) {
      removed += a.id
      for (item in a.inventoryIndex.values + a.equipmentIndex.values) markRemoved(item)
    }
    for (item in a.inventoryIndex.values + a.equipmentIndex.values) forgetItemTree(item)
    if (a is Mob) {
      a.spawnedBy?.let { s ->
        s.live.remove(a.id)
        if (s.respawnAt == null) s.respawnAt = pulse + s.rule.respawnPulses
      }
    }
  }

  internal fun fillSpawn(s: SpawnState) {
    while (s.live.size < s.rule.count) {
      val m = spawnMob(s.rule.label, s.room, s.rule.atIndex)
      m.spawnedBy = s
      s.live += m.id
    }
    s.respawnAt = null
  }

  // ---- Timers (verbs `after` / `cancel`, SPEC §4.5) -----------------------------------------

  fun after(
    owner: Entity,
    pulses: Int,
    fn: HookRef,
    args: Map<String, AttrValue> = emptyMap(),
  ): Long {
    require(pulses >= 1) { "after(): pulses must be at least 1" }
    // A dormant owner's timer starts paused (SPEC §3.8 inv. 7).
    val t =
      if (isSimulated(owner)) {
        ScriptTimer(allocateHandle(), fn, args, due = pulse + pulses)
      } else {
        ScriptTimer(allocateHandle(), fn, args, due = null, remaining = pulses)
      }
    owner.timers += t
    owner.dirty = true
    return t.id
  }

  fun cancel(owner: Entity, timerId: Long): Boolean =
    owner.timers.removeIf { it.id == timerId }.also { if (it) owner.dirty = true }

  // ---- The pulse (SPEC §5.1) ----------------------------------------------------------------

  /** Advances game time by one pulse and runs every phase in order. */
  fun runPulse() {
    pulse++
    runTimers()
    flushImmediate()
    // Roll before phase 2 so a round-boundary action pulse uses the new initiative (D-002).
    if (pulse % COMBAT_ROUND_PULSES == 0L) rollInitiative()
    runTiers(actorIndex.values.filter { isSimulated(it) }, ::actionPulse)
    if (pulse % COMBAT_ROUND_PULSES == 0L) runCombatRound()
    flushImmediate()
    if (pulse % WORLD_TICK_PULSES == 0L) runWorldTick()
    deliverPerceptions()
    flushImmediate()
    guarded("bubble maintenance") { maintainBubble() }
    flushImmediate()
    expireSessions()
    shutdownWarnings()
    flushOutput()
    if (config.checkInvariants) {
      val violations = checkInvariants()
      if (violations.isNotEmpty()) {
        // The harness fails the test; --dev logs and keeps running (SPEC §6.5, D-084).
        if (config.strict) throw InvariantViolation(violations)
        for (v in violations) record(
          Diagnostic(pulse, "invariant", v, InvariantViolation(listOf(v)))
        )
      }
    }
  }

  fun runPulses(n: Int) = repeat(n) { runPulse() }

  /** Phase 1: script timers, affect ticks and expiries due now, ordered by `(due, id)`. */
  private fun runTimers() {
    class Due(val due: Long, val id: Long, val run: () -> Unit)
    val due = ArrayList<Due>()
    for (e in entities.values) {
      if (e.timers.isEmpty() || !isSimulated(e)) continue
      for (t in e.timers) {
        val at = t.due ?: continue
        // A quarantined package's timers stay pending, neither fired nor dropped (SPEC §6.3).
        if (at <= pulse && !scripts.isSuspended(e, t.fn)) {
          due +=
            Due(at, t.id) {
              if (e.timers.remove(t)) {
                e.dirty = true
                invokeRef(e, t.fn, HookContext(args = t.args))
              }
            }
        }
      }
    }
    for (a in actorIndex.values) {
      if (a.affects.isEmpty() || !isSimulated(a)) continue
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

  /** Rolls initiative for every engaged actor at a round boundary, before phase 2 (D-002). */
  private fun rollInitiative() {
    for (a in actorIndex.values) a.initiative = null
    val engaged = actorIndex.values.filter { it.isEngaged && isSimulated(it) }
    for (a in engaged) guarded("initiative of $a") {
      a.initiative = initiativeOf(RulesContext(this, a))
    }
    if (engaged.isNotEmpty() && onRules != null) {
      val order =
        engaged.sortedWith(
          compareByDescending<Actor> { it.initiative ?: Int.MIN_VALUE }.thenBy { it.id }
        )
      traceRules(
        null,
        "initiative at pulse $pulse: " + order.joinToString(", ") { "${it.name} ${it.initiative}" },
      )
    }
  }

  /**
   * Phase 3: one auto-attack per engaged actor, under the initiative rolled this pulse (SPEC §5.4).
   */
  private fun runCombatRound() {
    val engaged = actorIndex.values.filter { it.isEngaged && isSimulated(it) }
    val rooms = engaged.map { it.room }.distinct()
    runTiers(engaged) { a ->
      if (a.isEngaged) {
        autoAttack(a)
        invokeOn(a, HookName.ON_COMBAT_TICK, HookContext(actor = a, room = a.room))
      }
    }
    // The sound of a combat round carries as noise (SPEC §5.7).
    for (r in rooms) perceive("combat", gups.core.model.Channel.NOISE, r, actor = null)
  }

  /** Phase 4 (SPEC §5.1). Checkpoint capture arrives with persistence in M4. */
  private fun runWorldTick() {
    for (a in actorIndex.values.toList()) {
      if (a.hasFlag("no_regen") || !isSimulated(a)) continue
      guarded("regen of $a") {
        val r = regenOf(RulesContext(this, a))
        a.hp = (a.hp + r.hp).coerceAtMost(a.maxHp)
        a.mana = (a.mana + r.mana).coerceAtMost(a.maxMana)
        a.move = (a.move + r.move).coerceAtMost(a.maxMove)
        a.dirty = true
      }
    }
    for (e in
      entities.values
        .filter { isSimulated(it) && scripts.hasHook(it, HookName.ON_TICK) }
        .sortedBy { it.id }) {
      if (e.id in entities) invokeOn(e, HookName.ON_TICK, HookContext())
    }
    for ((pkg, at) in zoneResets.entries.toList()) {
      if (at <= pulse) guarded("reset of $pkg") { resetZone(pkg) }
    }
    // Dormant rooms catch up on respawns and decay when they wake (SPEC §5.9).
    for (s in spawnStates) {
      val at = s.respawnAt ?: continue
      if (at <= pulse && !s.room.isDormant) guarded("respawn in ${s.room.label}") { fillSpawn(s) }
    }
    for (item in entities.values.filterIsInstance<Item>()) {
      val at = item.decayAt ?: continue
      if (at <= pulse && item.id in entities && isSimulated(item))
        guarded("decay of $item") { decay(item) }
    }
    for (a in actorIndex.values) if (isSimulated(a)) updateEncumbrance(a)
    expireDebugMobs()
    // Checked every world tick; broken entities stay out of the next checkpoint (SPEC §6.5).
    val broken = invariantViolations()
    if (!config.checkInvariants) for (v in broken) record(Diagnostic(pulse, "invariant", v.message))
    checkpointExclusions = broken.mapNotNullTo(HashSet()) { it.entity }
    if (pulse - lastCheckpoint >= config.checkpointPulses) checkpointNow("scheduled")
  }

  private var lastCheckpoint = boot.pulse

  /**
   * Writes a routine checkpoint now, as the schedule, a successful reload and shutdown do (SPEC
   * §5.10, §5.11). Restarts the schedule.
   */
  fun checkpointNow(cause: String) {
    lastCheckpoint = pulse
    guarded("checkpoint") { persistence.checkpoint(this, cause, immediate = false) }
  }

  /**
   * A zone reset (D-061): refill every spawn rule in the package, then fire `on_reset` on its
   * rooms, ascending id.
   */
  private fun resetZone(pkg: String) {
    val interval = protos.zones.getValue(pkg).resetPulses ?: return
    zoneResets[pkg] = pulse + interval
    // A dormant room takes its reset when it wakes (SPEC §5.9).
    val rooms = roomsByLabel.values.filter { it.label.pkg == pkg }
    for (room in rooms) if (room.isDormant) room.resetMissed = true
    for (s in spawnStates) if (s.room.label.pkg == pkg && !s.room.isDormant) fillSpawn(s)
    for (room in rooms.filter { !it.isDormant }.sortedBy { it.id }) {
      invokeOn(room, HookName.ON_RESET, HookContext(room = room))
    }
  }

  /** Fires `on_load` on every entity, ascending id, once the world is built (D-062). */
  fun fireOnLoad() {
    for (e in entities.values.sortedBy { it.id }) {
      if (e.id in entities) invokeOn(e, HookName.ON_LOAD, HookContext(room = e as? Room))
    }
    settleBubble()
  }

  // ---- Initiative tiers (SPEC §5.4.1) -------------------------------------------------------

  /**
   * Runs [act] for each participant tier by tier: engaged actors grouped by initiative from highest
   * to lowest, then everyone else. Same-tier actions validate against a snapshot taken when the
   * tier starts; deaths and room transitions commit when it ends.
   */
  internal fun runTiers(participants: List<Actor>, act: (Actor) -> Unit) {
    removedThisPhase.clear()
    val (ranked, rest) = participants.partition { it.isEngaged && it.initiative != null }
    val tiers = ranked.groupBy { it.initiative!! }.toSortedMap(reverseOrder()).values + listOf(rest)
    for (tier in tiers) {
      val members = tier.filter { it.inWorld && it.id !in removedThisPhase }.sortedBy { it.id }
      if (members.isEmpty()) continue
      // Only actors that can be acted on: simulated ones, which covers active rooms (D-003).
      val snap = TierSnapshot(actorIndex.values.filter { isSimulated(it) })
      snapshot = snap
      try {
        for (a in members) {
          if (snap.canAct(a)) guarded("action of $a") { act(a) }
          flushImmediate()
        }
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
  fun isPresent(item: Item, room: Room): Boolean =
    when (val loc = item.location) {
      is ItemLocation.InRoom -> loc.room == room.id && item.id in entities
      is ItemLocation.Carried -> actor(loc.actor)?.let { isPresent(it, room) } ?: false
      is ItemLocation.Equipped -> actor(loc.actor)?.let { isPresent(it, room) } ?: false
      is ItemLocation.Contained -> item(loc.container)?.let { isPresent(it, room) } ?: false
    }
}

/**
 * Positions and ability to act of every simulated actor, captured when an initiative tier starts.
 * Lookups for an actor outside the snapshot fall back to its live state.
 */
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

  /** True if [a] was recorded when the tier started. */
  fun covers(a: Actor): Boolean = a.id in rooms
}

/** Something that takes an actor out of the fight, committed at the end of the tier. */
internal class Deferred(val actor: Actor, val kind: Kind, val seq: Int, val run: () -> Unit) {
  enum class Kind {
    DEATH,
    TRANSITION,
  }
}
