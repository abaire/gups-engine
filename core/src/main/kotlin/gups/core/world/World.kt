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
import gups.core.model.Condition
import gups.core.model.Entity
import gups.core.model.Group
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Room
import gups.core.model.ScriptTimer
import gups.core.model.SpawnRule
import gups.core.model.WorldSchema
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.rules.StartingStats
import java.util.TreeMap

data class WorldConfig(
  val seed: Long = 0,
  /** Check the invariants after every pulse (`--dev` and the harness). */
  val checkInvariants: Boolean = false,
  /** Rethrow engine exceptions instead of logging them. For tests. */
  val strict: Boolean = false,
  /**
   * Pulses between routine checkpoints, rounded up to world ticks. The default is 30 minutes; a
   * world sets it with `checkpoint_minutes` in `MODULE.gups`.
   */
  val checkpointPulses: Long = DEFAULT_CHECKPOINT_PULSES,
  /**
   * Keep a reality bubble: only rooms near players and other anchors are simulated. Off, every room
   * is always simulated, as headless tests usually want.
   */
  val bubble: Boolean = false,
  /** `--prod`: `@reload` is refused in favour of deploys. */
  val production: Boolean = false,
  /** Command aliases and prefixes from `MODULE.gups`; a world without them has none. */
  val aliases: CommandAliases = CommandAliases.NONE,
  /** The feature modules the world has on; tests default to all of them. */
  val features: Features = Features.ALL,
  /** Engine command renames from `MODULE.gups`, old name to new. */
  val rename: Map<String, String> = emptyMap(),
  /** Message overrides from `MESSAGES.gups`, by message id. */
  val messages: Map<String, String> = emptyMap(),
)

/** 30 minutes of pulses. */
const val DEFAULT_CHECKPOINT_PULSES = 30L * 60 * 1000 / PULSE_MS

/**
 * A logged engine or script problem. Persisted to the diagnostics table. Script diagnostics fill in
 * where the failing code lives; engine ones leave the script fields null.
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
  /** The room the failing code ran for, which a repro bundle snapshots. */
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
 * The world graph and its scheduler. Not thread-safe by design: everything runs on the world
 * thread, driven by [gups.core.loop.WorldLoop] or directly by a headless test.
 */
/**
 * How a world starts: fresh from its prototypes, or as the shell that saved state is restored into.
 * Saved rooms keep their ids so references to them survive a restart; new ids start above
 * everything ever saved.
 */
class Boot(
  val pulse: Long = 0,
  val nextEntityId: Long = 1,
  val nextHandle: Long = 1,
  val roomIds: Map<Label, EntityId> = emptyMap(),
  /**
   * Whether [World.start] fills the rooms' spawns: false when restoring, so saved mobs come back
   * instead of fresh spawns.
   */
  val fillSpawns: Boolean = true,
)

/** Applies plugin effect primitives; the plugin host installs one. */
fun interface PluginEffectRunner {
  fun apply(
    world: World,
    effect: gups.core.model.Effect.Plugin,
    caster: Actor,
    target: Actor?,
    targetItem: Item?,
    targetRoom: Room?,
    power: Int,
  )
}

class World(
  protos: Prototypes,
  /** The names this world's actors are measured by; fixed for the life of the world. */
  val schema: WorldSchema,
  val rules: RulesEngine,
  val scripts: ScriptHost = ScriptHost.NONE,
  val config: WorldConfig = WorldConfig(),
  boot: Boot = Boot(),
  /**
   * How earned levels are applied to actors in this world: automatically upon eligibility or
   * deferred until a script advances them.
   */
  var levelMode: LevelMode = LevelMode.AUTO,
) {
  /** The live prototype table; a reload swaps it. */
  var protos: Prototypes = protos
    internal set

  /** Game time. */
  var pulse: Long = boot.pulse
    private set

  /**
   * The pulse that room affects are read as of: the current pulse, or the start of the piece of
   * dormant time a regen catch-up is working out.
   */
  val roomClock: Long
    get() = catchUpAt ?: pulse

  /** Set only by the dormant regen catch-up, and always cleared again. */
  internal var catchUpAt: Long? = null

  /** Where checkpoints go; nowhere until a persistence layer is attached. */
  var persistence: Persistence = Persistence.NONE

  /**
   * Handles sessions that have no character yet; when null, such a session's first input answers
   * "You are not playing a character." and closes it.
   */
  var login: LoginHandler? = null

  /** Called for every diagnostic as it is recorded, for example to log it. */
  var onDiagnostic: ((Diagnostic) -> Unit)? = null

  /** Receives each rules evaluation with its modifier chain, for `@trace rules`. */
  var onRules: ((Actor?, String) -> Unit)? = null

  // ---- Operations -----------------------------------------------------------------------

  /** Wall-clock milliseconds, for restrictions and audit times. */
  var wallClock: () -> Long = System::currentTimeMillis

  /** Where slow work runs and how its result comes back (see [later]); null runs it inline. */
  var background: java.util.concurrent.Executor? = null
  var post: ((gups.core.WorldControlEvent) -> Unit)? = null

  /** Restrictions of every account, as loaded from the account store and kept by `@restrict`. */
  var accountRestrictions: List<gups.core.Restriction> = emptyList()

  /** Character creation provider, if a plugin registered one. */
  var creation: gups.core.model.CreationProvider? = null

  /** Known traits registered by plugins, by trait name. */
  var traits: Map<String, gups.core.model.Trait> = emptyMap()

  /** Restriction checks registered by plugins, by restriction point. */
  var restrictions: Map<gups.core.model.RestrictionPoint, List<gups.core.model.Restriction>> =
    emptyMap()

  /** Receives every instructor alert, for the server log. */
  var onAlert: ((String) -> Unit)? = null

  /** The last minute of pulse durations, fed by the world loop. */
  val pulseStats = PulseStats()

  /** Input lines waiting for the world thread, as the world loop counts them. */
  var queueDepth: () -> Int = { 0 }

  /** Called by the world loop after each pulse with its duration in nanoseconds, for metrics. */
  var onPulse: ((Long) -> Unit)? = null

  /** Runs `Effect.Plugin`s; does nothing until a plugin host installs a runner. */
  var pluginEffects: PluginEffectRunner = PluginEffectRunner { _, _, _, _, _, _, _ -> }

  /** Plugin containment filters, in plugin id order then registration order. */
  var containmentFilters: List<ContainmentFilter> = emptyList()

  /** Plugin owned-behavior adjusters, in plugin id order. */
  var ownedBehaviorAdjusters: List<OwnedBehaviorAdjuster> = emptyList()

  /** Plugin GMCP state packages by name, recomputed in phase 7 like the engine's. */
  val pluginGmcp: MutableMap<String, (Player) -> Any?> = LinkedHashMap()

  /** Inbound GMCP packages, keyed by lowercase name. */
  val pluginGmcpIn: MutableMap<String, GmcpInput> = LinkedHashMap()

  /** Plugin handlers per custom hook name, in plugin-id order. */
  val pluginHooks: MutableMap<String, MutableList<CustomHookHandler>> = LinkedHashMap()

  /** Production deploys, when the server can run them. */
  var deploys: Deploys? = null

  // Alert rate limits, the engine-error window and step samples.
  internal val alertTimes = HashMap<String, Long>()
  internal val rulesFaultTimes = HashMap<String, Long>()
  internal val ownerAlertTimes = HashMap<String, Long>()
  internal val engineErrors = ArrayDeque<Long>()
  internal val stepSamples = ArrayDeque<Pair<Long, Map<String, Long>>>()
  internal var shutdownReason: String? = null

  /**
   * Sliding window in pulses for throttling repeated rules fault alerts and diagnostics for the
   * same key. The script host replaces the default with its own quarantine window when installed.
   */
  var quarantineWindowPulses: Long = MINUTE_PULSES

  internal var slotCache: List<String>? = null
  internal var slotFamilyCache: Map<String, List<String>>? = null

  val rng = Rng(config.seed)
  val features: Features
    get() = config.features

  val commands = CommandRegistry(config.aliases, config.features, config.rename)

  internal var nextEntityId =
    maxOf(boot.nextEntityId, (boot.roomIds.values.maxOfOrNull { it.v } ?: 0) + 1)
  internal var nextHandle = boot.nextHandle
  internal val entities = HashMap<EntityId, Entity>()
  internal val actorIndex = TreeMap<EntityId, Actor>()
  internal val roomsByLabel = LinkedHashMap<Label, Room>()
  internal val sessions = LinkedHashMap<SessionId, Session>()
  internal val spawnStates = mutableListOf<SpawnState>()
  private val diagnosticLog = mutableListOf<Diagnostic>()

  /** Next zone reset per package that declares `reset_world_ticks`. */
  internal val zoneResets = TreeMap<String, Long>()

  // Persistence bookkeeping, drained by each checkpoint capture.
  internal val removed = LinkedHashSet<EntityId>()
  internal val itemEvents = mutableListOf<ItemEvent>()
  internal val inputs = mutableListOf<InputRecord>()
  internal var immediateCause: String? = null

  /** Entities the last world tick's invariant check found broken; kept out of checkpoints. */
  var checkpointExclusions: Set<EntityId> = emptySet()
    private set

  // Initiative-tier state.
  internal var snapshot: TierSnapshot? = null
  internal val deferred = mutableListOf<Deferred>()
  internal val removedThisPhase = HashSet<EntityId>()
  /** Actors whose death is being processed right now. */
  internal val dying = HashSet<EntityId>()
  internal var cascadeDepth = 0

  /** The in-memory player groups, by group id; never saved. */
  internal val groups = TreeMap<Int, Group>()
  /** Group ids are allocated from this counter; they are never reused. */
  internal var nextGroupId = 1

  // The reality bubble: how many anchors cover each room, and where each anchor was.
  internal val coverage = HashMap<Room, Int>()
  internal val anchorRooms = HashMap<EntityId, Room>()

  // Perception events waiting for phase 5.
  internal val perceptions = mutableListOf<Perception>()

  /**
   * `path_to` results by `(from, to)` room id, dropped whenever a door, exit or reload changes the
   * graph.
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
      val room = Room(boot.roomIds[proto.label] ?: allocateId(), proto, Feature.TRACK !in features)
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
        spawnStates += SpawnState(room, rule)
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

  /** Ids to save: the next entity id and the next timer/affect handle (`meta`). */
  val nextIds: Pair<Long, Long>
    get() = nextEntityId to nextHandle

  /**
   * Asks for an immediate checkpoint once the current action's cascade ends, for example after an
   * item changes hands between players.
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

  fun diagnose(kind: String, message: String, cause: Throwable? = null) {
    record(Diagnostic(pulse, kind, message, cause))
  }

  /** Logs a diagnostic built elsewhere, for example by the script host. */
  fun record(d: Diagnostic) {
    diagnosticLog += d
    onDiagnostic?.invoke(d)
    persistence.diagnostic(this, d)
    alertFor(d)
  }

  /** Runs [block], logging an engine exception and aborting only that action. */
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

  /**
   * Creates a player with the numbers `rules.starting_stats` gives, then [overrides] on top: a stat
   * or maximum it names replaces the rules' value, and so does a current value.
   */
  fun spawnPlayer(
    name: String,
    at: Label,
    level: Int = 1,
    overrides: StartingStats = StartingStats(),
    index: Int = 0,
    accountId: Long? = null,
    choices: Map<String, String> = emptyMap(),
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
    p.choices.putAll(choices)
    initVitals(p)
    p.recomputeTraits(this)
    applyStartingStats(p, startingStatsOf(p))
    applyStartingStats(p, overrides, keepCurrent = true)
    settleNewCondition(p, "starting_stats")
    p.lastInputPulse = pulse
    enter(p, room, p.trackIndex)
    forgetActorSlots(p)
    refreshFit(p)
    clampVitals(p)
    return p
  }

  fun spawnMob(label: Label, at: Room, index: Int = 0, prepare: (Mob) -> Unit = {}): Mob {
    val proto = protos.mob(label)
    val m = Mob(allocateId(), proto, at, index.coerceIn(0, at.trackSize - 1))
    declareMob(m, proto)
    prepare(m)
    applyStartingStats(m, startingStatsOf(m))
    settleNewCondition(m, "starting_stats")
    enter(m, at, m.trackIndex)
    forgetActorSlots(m)
    refreshFit(m)
    clampVitals(m)
    val full =
      m.vitalMap.filter { (name, v) -> v.current == m.max(name) }.mapValues { it.value.current }
    invokeOn(m, HookName.ON_SPAWN, HookContext(room = at))
    // Gear put on by on_spawn raises maxima; a vital that was full and the hook left alone fills
    // up.
    if (m.inWorld) {
      for ((name, was) in full) {
        val v = m.vitalMap.getValue(name)
        if (v.current == was && m.max(name) > was) {
          v.current = m.max(name)
          m.dirty = true
        }
      }
    }
    return m
  }

  /** Fills [m] with its prototype's declared numbers, which `rules.starting_stats` then sees. */
  internal fun declareMob(m: Mob, proto: gups.core.model.MobProto) {
    m.level = proto.level
    initVitals(m)
    m.baseStats.putAll(proto.stats)
    m.baseSenses.putAll(proto.senses)
    for ((name, max) in proto.vitals) {
      val v = m.vitalMap[name] ?: continue
      v.max = max
      v.current = max
    }
    m.resists.putAll(proto.resists)
    m.skills.putAll(proto.skills)
    m.recomputeTraits(this)
    for ((name, v) in m.vitalMap) {
      v.current = m.max(name)
    }
  }

  private fun startingStatsOf(a: Actor): StartingStats = rules.startingStats(RulesContext(this, a))

  /**
   * Applies a `starting_stats` result: stats and maxima it gives replace the actor's, and a current
   * value it omits equals the maximum. With [keepCurrent], a current value is only replaced when
   * the result names it or its maximum.
   */
  private fun applyStartingStats(a: Actor, r: StartingStats, keepCurrent: Boolean = false) {
    a.baseStats.putAll(r.stats)
    a.baseSenses.putAll(r.senses)
    for ((name, v) in a.vitalMap) {
      val max = r.max[name]
      if (max != null) v.max = max
      val effMax = a.max(name)
      val cur = r.current[name]
      when {
        cur != null -> v.current = cur.coerceAtMost(effMax)
        max != null || !keepCurrent -> v.current = effMax
      }
      v.belowZeroSince = if (v.current < 0) pulse else null
    }
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

  /**
   * Takes [a] and everything it carries out of the world; [stow] keeps a mob's rows for a later
   * restore.
   */
  internal fun removeActor(a: Actor, stow: Boolean = false) {
    if (a is Player) leaveGroupOnRemoval(a)
    a.room.actorIndex.remove(a.id)
    actorIndex.remove(a.id)
    entities.remove(a.id)
    a.inWorld = false
    removedThisPhase += a.id
    // A player who quits keeps their row and their items' rows; a mob is gone for good unless
    // stowed.
    if (a is Mob && !stow) {
      removed += a.id
      for (item in a.inventoryIndex.values + a.equipmentIndex.values) markRemoved(item)
    }
    for (item in a.inventoryIndex.values + a.equipmentIndex.values) forgetItemTree(item)
    if (a is Mob && !stow) releaseSpawn(a)
  }

  internal fun fillSpawn(s: SpawnState) {
    while (s.live.size < s.rule.count) {
      val m = spawnMob(s.rule.label, s.room, s.rule.atIndex)
      m.spawnedBy = s
      s.live += m.id
    }
    s.respawnAt = null
  }

  // ---- Timers (verbs `after` / `cancel`) ----------------------------------------------------

  fun after(
    owner: Entity,
    pulses: Int,
    fn: HookRef,
    args: Map<String, AttrValue> = emptyMap(),
  ): Long {
    require(pulses >= 1) { "after(): pulses must be at least 1" }
    // A dormant owner's timer starts paused.
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

  // ---- The pulse ----------------------------------------------------------------------------

  /** Advances game time by one pulse and runs every phase in order. */
  fun runPulse() {
    pulse++
    runTimers()
    flushImmediate()
    // Roll before phase 2 so a round-boundary action pulse uses the new initiative.
    if (Feature.COMBAT in features && pulse % COMBAT_ROUND_PULSES == 0L) rollInitiative()
    runTiers(actorIndex.values.filter { isSimulated(it) }, ::actionPulse)
    if (Feature.COMBAT in features && pulse % COMBAT_ROUND_PULSES == 0L) runCombatRound()
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
        // The harness fails the test; --dev logs and keeps running.
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
        // A quarantined package's timers stay pending, neither fired nor dropped.
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
    for (e in entities.values) {
      if (e !is Item || e.affects.isEmpty() || !isSimulated(e)) continue
      for (aff in e.affects) {
        val every = aff.tickEvery
        when {
          aff.expiresAt <= pulse -> due += Due(aff.expiresAt, aff.id) { expireItemAffect(e, aff) }
          every != null && aff.onTick != null && (pulse - aff.appliedAt) % every == 0L ->
            due +=
              Due(pulse, aff.id) {
                // An earlier hook this pulse may have destroyed the item or ended the affect.
                if (e.id in entities && aff in e.affects)
                  invokeRef(e, aff.onTick, HookContext(affect = aff))
              }
        }
      }
    }
    for (e in entities.values) {
      if (e !is Room || e.affects.isEmpty() || !isSimulated(e)) continue
      for (aff in e.affects) {
        val every = aff.tickEvery
        when {
          aff.expiresAt <= pulse -> due += Due(aff.expiresAt, aff.id) { expireRoomAffect(e, aff) }
          every != null && aff.onTick != null && (pulse - aff.appliedAt) % every == 0L ->
            due +=
              Due(pulse, aff.id) {
                // An earlier hook this pulse may have ended the affect.
                if (aff in e.affects) invokeRef(e, aff.onTick, HookContext(affect = aff))
              }
        }
      }
    }
    due.sortWith(compareBy<Due>({ it.due }, { it.id }))
    for (d in due) guarded("timer ${d.id}") { d.run() }
  }

  /** Phase 2 for one actor. */
  private fun actionPulse(a: Actor) {
    val c = a.casting
    when {
      c != null -> advanceCast(a, c)
      a.waitState > 0 -> a.waitState--
      a.actionQueue.isNotEmpty() -> runQueued(a, a.actionQueue.removeFirst())
      a is Mob && pulse - a.lastThink >= a.thinkEvery -> think(a)
    }
  }

  /** Rolls initiative for every engaged actor at a round boundary, before phase 2. */
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

  /** Phase 3: one auto-attack per engaged actor, under the initiative rolled this pulse. */
  private fun runCombatRound() {
    val engaged = actorIndex.values.filter { it.isEngaged && isSimulated(it) }
    val rooms = engaged.map { it.room }.distinct()
    runTiers(engaged) { a ->
      if (a.isEngaged) {
        autoAttack(a)
        val ctx = HookContext(actor = a, room = a.room)
        invokeOn(a, HookName.ON_COMBAT_TICK, ctx)
        // Equipped items take part in the round too, after their wearer.
        for (item in
          a.equipmentIndex.values
            .filter { scripts.hasHook(it, HookName.ON_COMBAT_TICK) }
            .sortedBy { it.id }) {
          invokeOn(item, HookName.ON_COMBAT_TICK, ctx)
        }
      }
    }
    // The din of a combat round carries as sound.
    for (r in rooms) perceive(
      "combat",
      gups.core.model.Channel.SOUND,
      r,
      actor = null,
      intensity = COMBAT_INTENSITY,
    )
  }

  /** Phase 4. Checkpoint capture arrives with persistence. */
  private fun runWorldTick() {
    for (a in actorIndex.values.toList()) {
      if (!isSimulated(a) || !a.inWorld) continue
      if (!a.hasFlag("no_regen"))
        guarded("regen of $a") { applyVitalDeltas(a, regenOf(RulesContext(this, a)), null) }
      guarded("condition of $a") { refreshCondition(a) }
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
    // Dormant rooms catch up on respawns and decay when they wake.
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
    // Checked every world tick; broken entities stay out of the next checkpoint.
    val broken = invariantViolations()
    if (!config.checkInvariants) for (v in broken) record(Diagnostic(pulse, "invariant", v.message))
    checkpointExclusions = broken.mapNotNullTo(HashSet()) { it.entity }
    if (pulse - lastCheckpoint >= config.checkpointPulses) checkpointNow("scheduled")
  }

  private var lastCheckpoint = boot.pulse

  /**
   * Writes a routine checkpoint now, as the schedule, a successful reload and shutdown do. Restarts
   * the schedule.
   */
  fun checkpointNow(cause: String) {
    lastCheckpoint = pulse
    guarded("checkpoint") { persistence.checkpoint(this, cause, immediate = false) }
  }

  /**
   * A zone reset: refill every spawn rule in the package, then fire `on_reset` on its rooms,
   * ascending id.
   */
  private fun resetZone(pkg: String) {
    val interval = protos.zones.getValue(pkg).resetPulses ?: return
    zoneResets[pkg] = pulse + interval
    // A dormant room takes its reset when it wakes.
    val rooms = roomsByLabel.values.filter { it.label.pkg == pkg }
    for (room in rooms) if (room.isDormant) room.resetMissed = true
    for (s in spawnStates) if (s.room.label.pkg == pkg && !s.room.isDormant) fillSpawn(s)
    for (room in rooms.filter { !it.isDormant }.sortedBy { it.id }) {
      invokeOn(room, HookName.ON_RESET, HookContext(room = room))
    }
  }

  private val fillSpawnsAtStart = boot.fillSpawns
  private var started = false

  /**
   * Brings the built world to life, once everything that shapes its actors is in place: plugins
   * installed, scripts installed and any saved state restored. A fresh world fills its rooms'
   * spawns first, so each mob spawns as it would later (traits, rules, `on_spawn`); then every
   * entity gets `on_load`, ascending id. Call it once; a world rebuilt only to inspect or replay
   * saved state (a migration check, a repro) has already started and skips it.
   */
  fun start() {
    check(!started) { "The world has already started" }
    started = true
    if (fillSpawnsAtStart) for (s in spawnStates) fillSpawn(s)
    for (e in entities.values.sortedBy { it.id }) {
      if (e.id in entities) invokeOn(e, HookName.ON_LOAD, HookContext(room = e as? Room))
    }
    settleBubble()
  }

  // ---- Initiative tiers ---------------------------------------------------------------------

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
      // Only actors that can be acted on: simulated ones, which covers active rooms.
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
      val dies =
        group.any { it.kind == Deferred.Kind.DEATH } &&
          actor.inWorld &&
          actor.condition == Condition.DEAD
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

  fun canAct(a: Actor): Boolean = snapshot?.canAct(a) ?: (a.inWorld && !a.isDisabled)

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

/** Runs [entity]'s custom hook [name], then the plugin handlers for it, and returns the result. */
fun World.callHook(entity: Entity, name: String, args: Map<String, Any?>): Any? {
  if (entity.id !in entities) return null
  var result = scripts.invokeCustom(this, entity, name, args)
  for (handler in pluginHooks[name].orEmpty()) result = handler.handle(this, entity, args, result)
  return result
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
      able[a.id] = a.inWorld && !a.isDisabled
    }
  }

  fun room(a: Actor): Room = rooms[a.id] ?: a.room

  fun index(a: Actor): Int = indices[a.id] ?: a.trackIndex

  fun canAct(a: Actor): Boolean = able[a.id] ?: (a.inWorld && !a.isDisabled)

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
