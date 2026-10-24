package gups.script

/**
 * Script resource limits (SPEC §6.1, §6.3). Steps are the deterministic limit and agree
 * exactly between the harness and production; the wall-clock backstop only catches slow host
 * calls, so the harness turns it off (D-066).
 */
data class ScriptLimits(
    /** Zone hooks, `think`, timers and commands, per top-level event and its cascade. */
    val hookSteps: Long = 50_000,
    val hookWallNanos: Long = 20_000_000,
    /** The rules module and modifier chain, per evaluation (one rules function or one modifier). */
    val rulesSteps: Long = 200_000,
    val rulesWallNanos: Long = 20_000_000,
    /** Top-level evaluation, per package. */
    val loadSteps: Long = 5_000_000,
    val loadWallNanos: Long = 2_000_000_000,
    /** `@eval`, per evaluation including the hooks its verbs trigger. */
    val evalSteps: Long = 1_000_000,
    val evalWallNanos: Long = 1_000_000_000,
    val wallClock: Boolean = true,
    /** Strings longer than this many chars are rejected where they reach the engine (D-067). */
    val maxStringChars: Int = 1 shl 20,
    /** Lists and dicts with more elements than this are rejected likewise: 1 MB at 8 bytes each. */
    val maxElements: Int = (1 shl 20) / 8,
    /** Serialized `customAttributes` per entity. */
    val maxAttrBytes: Int = 64 * 1024,
    /** More than this many errors from one package within the window quarantines it. */
    val quarantineErrors: Int = 20,
    /** 60 s of game time (D-068). */
    val quarantineWindowPulses: Long = 240,
) {
    companion object {
        /** The harness: no wall clock, so results never depend on machine speed. */
        val HARNESS = ScriptLimits(wallClock = false)
    }
}
