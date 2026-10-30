package gups.core

// Time and capacity constants. Game time is the pulse counter.

/**
 * The fundamental heartbeat interval of the game loop in milliseconds (250 ms, or 4 pulses per
 * second). Monotonic game time advances one pulse every 250 milliseconds.
 */
const val PULSE_MS = 250L

/**
 * Interval between combat rounds in game pulses (8 pulses = 2.0 seconds). Attacks and initiative
 * resolution occur on pulse boundaries divisible by this constant.
 */
const val COMBAT_ROUND_PULSES = 8

/**
 * Interval between periodic world ticks in game pulses (120 pulses = 30.0 seconds). Triggers
 * periodic vital regeneration and environmental updates.
 */
const val WORLD_TICK_PULSES = 120

/**
 * Default mob AI thinking cadence in game pulses (4 pulses = 1.0 second). Individual mob prototypes
 * can override this cadence in the range 1..N pulses.
 */
const val THINK_DEFAULT_PULSES = 4

/**
 * Spatial radius in room exit hops for reality bubbles and event propagation (3 exits). Rooms
 * within this distance of an active player are kept simulated in memory.
 */
const val BUBBLE_RADIUS = 3

/**
 * Maximum number of actions queued per actor in the tick lane (10 actions). Attempts to enqueue
 * actions beyond this capacity are rejected.
 */
const val ACTION_QUEUE_MAX = 10

/**
 * Maximum recursion depth for synchronous hook cascades (8 levels). Prevents runaway recursion when
 * hooks invoke actions that trigger nested hooks.
 */
const val CASCADE_MAX_DEPTH = 8

/**
 * Grace period in game pulses before a disconnected (link-dead) player character is logged out (720
 * pulses = 3 minutes).
 */
const val LINKDEAD_PULSES = 720

/**
 * Inactivity timeout in game pulses before an idle connected player character is disconnected (7200
 * pulses = 30 minutes).
 */
const val IDLE_QUIT_PULSES = 7200

/**
 * Lifetime of a player or mob corpse entity in game pulses before it decays (1200 pulses = 5
 * minutes). When a corpse decays, any contained items drop into the room where the corpse lay.
 */
const val CORPSE_DECAY_PULSES = 1200
