package gups.script

/**
 * Script resource limits, allocation caps, and quarantine rules.
 *
 * Execution step limits are deterministic and agree exactly between the test harness and production
 * environments. The wall-clock backstop measures elapsed time since the beginning of the top-level
 * event or package load and is checked at every `ctx` verb call and declaration call, catching slow
 * host invocations; the harness disables wall-clock checks so test execution never depends on host
 * performance.
 *
 * ## Step and Wall-Clock Limits
 * - **Hooks and commands:** 50,000 steps and a 20 ms wall-clock backstop per top-level event and
 *   its synchronous cascade (zone hooks, `think`, `on_perceive`, timers, and commands).
 * - **Rules and modifiers:** 200,000 steps and a 20 ms wall-clock backstop per evaluation (one
 *   rules function call or one modifier invocation). Each evaluation receives a fresh budget even
 *   when triggered within a hook cascade (for instance, `ctx.damage` invoking `interrupt_check`),
 *   and its steps are not charged against the hook cascade's budget.
 * - **Package loading:** 5,000,000 steps and a 2 s wall-clock backstop per package evaluation
 *   across all files in the package.
 * - **Interactive evaluation (`@eval`):** 1,000,000 steps and a 1 s wall-clock backstop per
 *   evaluation, including hooks triggered by its verbs.
 *
 * ## Package Charging Rules
 * Step accounting and diagnostic charges determine package quarantine counts:
 * - A hook is charged to the package of its owner's prototype, even if the executed function is
 *   defined in a shared library package.
 * - Timers and affect hooks are charged to the owner's package if the owner belongs to a script
 *   package; otherwise (such as timers attached to player entities), they are charged to the
 *   package where the function file resides.
 * - Zone commands are charged to their declaring package; global commands to their registering
 *   package.
 * - Rules functions and modifier chain failures are charged to the package where the function file
 *   resides (the rules package, or the zone, library, or skill package defining the modifier).
 *
 * ## Allocation Caps
 * Starlark-Java provides no allocation hook, so bounds are enforced where values cross into the
 * engine (verb arguments, hook return values, stored attributes, and timer arguments):
 * - Strings are capped at 1,048,576 characters (`1 shl 20`).
 * - Lists and dicts are capped at 131,072 elements (`(1 shl 20) / 8`, corresponding to 1 MB at 8
 *   bytes each).
 * - Exceeding character or element limits raises an `alloc_limit` error. Hooks may construct larger
 *   structures internally if they do not pass them to the engine; internal execution is constrained
 *   by step limits.
 * - Serialized `customAttributes` are capped at 64 KB (65,536 bytes) per entity; exceeding this
 *   rejects `set_attr`.
 *
 * ## Quarantine Thresholds
 * Accumulating more than 20 errors within a 240-pulse window (60 seconds of game time) quarantines
 * the package.
 */
data class ScriptLimits(
  /**
   * Zone hooks, `think`, `on_perceive`, timers and commands, per top-level event and its cascade.
   * Limit: 50,000 steps.
   */
  val hookSteps: Long = 50_000,
  /** Wall-clock backstop for hooks and commands: 20 ms (20,000,000 ns). */
  val hookWallNanos: Long = 20_000_000,
  /**
   * The rules module and modifier chain, per evaluation (one rules function or one modifier).
   * Limit: 200,000 steps.
   */
  val rulesSteps: Long = 200_000,
  /** Wall-clock backstop for rules evaluations: 20 ms (20,000,000 ns). */
  val rulesWallNanos: Long = 20_000_000,
  /** Top-level evaluation, per package across all files. Limit: 5,000,000 steps. */
  val loadSteps: Long = 5_000_000,
  /** Wall-clock backstop for package loading: 2 s (2,000,000,000 ns). */
  val loadWallNanos: Long = 2_000_000_000,
  /** `@eval`, per evaluation including the hooks its verbs trigger. Limit: 1,000,000 steps. */
  val evalSteps: Long = 1_000_000,
  /** Wall-clock backstop for `@eval`: 1 s (1,000,000,000 ns). */
  val evalWallNanos: Long = 1_000_000_000,
  /** Whether the wall-clock backstop is enabled (disabled in the test harness for determinism). */
  val wallClock: Boolean = true,
  /**
   * Strings longer than this many chars (1,048,576 chars, 1 MB) are rejected where they reach the
   * engine.
   */
  val maxStringChars: Int = 1 shl 20,
  /**
   * Lists and dicts with more elements than this (131,072 elements, 1 MB at 8 bytes each) are
   * rejected.
   */
  val maxElements: Int = (1 shl 20) / 8,
  /**
   * Serialized `customAttributes` per entity: 64 KB (65,536 bytes). Exceeding this rejects
   * `set_attr`.
   */
  val maxAttrBytes: Int = 64 * 1024,
  /**
   * Lists and dicts nested more than this deep (8) are rejected where they reach the engine, as
   * attribute values, timer arguments and GMCP payloads.
   */
  val maxNesting: Int = 8,
  /**
   * More than this many errors (20) from one package within [quarantineWindowPulses] quarantines
   * it.
   */
  val quarantineErrors: Int = 20,
  /** Quarantine error sliding window: 240 pulses (60 seconds of game time at 250 ms/pulse). */
  val quarantineWindowPulses: Long = 240,
) {
  companion object {
    /** The harness: no wall clock, so results never depend on machine speed. */
    val HARNESS = ScriptLimits(wallClock = false)
  }
}
