package gups.core

// Time and capacity constants. Game time is the pulse counter.

const val PULSE_MS = 250L
const val COMBAT_ROUND_PULSES = 8 // 2.0 s
const val WORLD_TICK_PULSES = 120 // 30.0 s
const val THINK_DEFAULT_PULSES = 4 // 1.0 s; per-mob override 1..N
const val BUBBLE_RADIUS = 3 // exits
const val ACTION_QUEUE_MAX = 10
const val CASCADE_MAX_DEPTH = 8
const val LINKDEAD_PULSES = 720 // 3 min
const val IDLE_QUIT_PULSES = 7200 // 30 min
