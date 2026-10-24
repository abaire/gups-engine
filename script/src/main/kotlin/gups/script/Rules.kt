package gups.script

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Band
import gups.core.model.DamageType
import gups.core.model.ModifierPoint
import gups.core.rules.DefaultRules
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.Regen
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.world.Diagnostic
import gups.core.world.World
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkValue

// The rules module (SPEC §4.6, §6.3): `//rules:combat.star` exports one function per rules call.
// A missing export, an error, or a rules package that failed to load falls back to the built-in
// Kotlin ruleset for that call, and combat keeps working.

/**
 * The read-only `ctx` of a rules function or modifier (SPEC §4.6). It has the call's fields and
 * `rng`, and `deny` for `cast_allowed`; it has no verbs.
 */
@StarlarkBuiltin(name = "rules_ctx", doc = "The read-only context of a rules call or modifier.")
class RulesCtx internal constructor(private val frame: Frame, private val c: RulesContext) : StarlarkValue {
    private fun <T> field(block: () -> T): T {
        frame.check()
        return block()
    }

    private fun v(e: gups.core.model.Entity?): Any = view(frame, e)

    @StarlarkMethod(name = "actor", structField = true, doc = "Whom the call is about: the attacker, caster, mover or regenerating actor.")
    fun actor(): Any = field { v(c.actor) }

    @StarlarkMethod(name = "attacker", structField = true, doc = "For attack calls, the same as actor.")
    fun attacker(): Any = field { v(c.actor) }

    @StarlarkMethod(name = "defender", structField = true, doc = "For attack calls, the one attacked; also the one taking damage in mitigate.")
    fun defender(): Any = field { v(c.target) }

    @StarlarkMethod(name = "target", structField = true, doc = "The other actor of the call, if any: the defender, the pinner, the victim, the killer.")
    fun target(): Any = field { v(c.target) }

    @StarlarkMethod(name = "weapon", structField = true)
    fun weapon(): Any = field { v(c.weapon) }

    @StarlarkMethod(name = "item", structField = true, doc = "The item, for get_lag and carry_limit.")
    fun item(): Any = field { v(c.item) }

    @StarlarkMethod(name = "band", structField = true, doc = "'melee', 'near' or 'far' between actor and target, or None.")
    fun band(): Any = field { c.band?.word ?: Starlark.NONE }

    @StarlarkMethod(name = "skill", structField = true)
    fun skill(): Any = field { c.skill?.let(::SkillView) ?: Starlark.NONE }

    @StarlarkMethod(name = "room", structField = true, doc = "The actor's room.")
    fun room(): Any = field { v(c.actor.room) }

    @StarlarkMethod(name = "pulse", structField = true)
    fun pulse(): Any = field { sInt(c.world.pulse) }

    @StarlarkMethod(name = "rng", structField = true)
    fun rng(): Any = field { RngView(frame) }

    @StarlarkMethod(name = "roll", doc = "Rolls dice such as '2d6+1' with the seeded rng.", parameters = [Param(name = "dice", named = true)])
    fun roll(dice: Any): Any = field {
        val d = try {
            gups.core.model.Dice.parse(str(dice, "roll(dice)"))
        } catch (e: IllegalArgumentException) {
            fail("roll(): ${e.message}")
        }
        sInt(d.roll(c.world.rng))
    }

    @StarlarkMethod(name = "deny", doc = "Refuses a cast; valid only in cast_allowed and its modifiers.", parameters = [Param(name = "msg", named = true)])
    fun deny(msg: Any): Any = field {
        if (!frame.site.vetoable) fail("deny() is only valid in cast_allowed (this is ${frame.site.hook})")
        Denial(checkString(str(msg, "deny(msg)"), "deny(msg)", frame.host.limits))
    }

    override fun isImmutable(): Boolean = true

    override fun repr(printer: Printer) {
        printer.append("<rules ctx ${frame.site.hook} of ${c.actor.name}>")
    }
}

/** Converts a rules result for [point] to Starlark, as a modifier's `base` argument. */
private fun baseValue(point: ModifierPoint, v: Any?): Any = when (point) {
    ModifierPoint.REGEN -> (v as Regen).let { r -> sDict(mapOf("hp" to sInt(r.hp), "mana" to sInt(r.mana), "move" to sInt(r.move))) }
    ModifierPoint.CAST_ALLOWED -> (v as String?)?.let(::Denial) ?: Starlark.NONE
    else -> sInt(v as Int)
}

private fun regenValue(v: Any?, what: String): Regen {
    val d = dict(v, what)
    for (k in d.keys) choice(k, "$what key", listOf("hp", "mana", "move"))
    fun get(k: String) = d[k]?.let { int(it, "$what[\"$k\"]") } ?: 0
    return Regen(get("hp"), get("mana"), get("move"))
}

private fun denialValue(v: Any?, what: String): String? = when {
    isNone(v) -> null
    v is Denial -> v.message
    else -> fail("$what: expected None or ctx.deny(msg), got ${typeName(v)}")
}

/** Converts a modifier's return value for [point] back to the engine's type. */
private fun pointValue(point: ModifierPoint, v: Any?, what: String): Any? = when (point) {
    ModifierPoint.REGEN -> regenValue(v, what)
    ModifierPoint.CAST_ALLOWED -> denialValue(v, what)
    else -> int(v, what)
}

/**
 * Runs one modifier for [StarlarkHost.modify]: `fn(ctx, base)` with its own rules budget,
 * charged to the package of the function's file. A failure keeps [base].
 */
internal fun StarlarkHost.runModifier(world: World, point: ModifierPoint, ref: HookRef, source: String, c: RulesContext, base: Any?): Any? {
    val site = Site(ref.file.pkg, null, "modifier ${point.scriptName} ($source)", point == ModifierPoint.CAST_ALLOWED, c.actor.id)
    var out: Any? = base
    val result = call(world, site, ref, { f -> RulesCtx(f, c) }, positional = { f -> listOf(RulesCtx(f, c), baseValue(point, base)) }, budget = limits.rulesSteps)
    if (result is CallResult.Ok) {
        try {
            out = pointValue(point, result.value, "${point.scriptName} modifier $source")
        } catch (e: EvalException) {
            report(world, site, "script_error", e.message ?: "bad modifier result", e, null)
        }
    }
    return out
}

/**
 * The Starlark rules module: calls the functions `//<pkg>:combat.star` exports (SPEC §4.6). Any
 * export it lacks, and any call that fails, uses [fallback], the built-in Kotlin ruleset
 * (SPEC §6.3). Bound to its world by [StarlarkHost.install].
 */
class StarlarkRules(private val host: StarlarkHost, val pkg: String = "rules", private val fallback: RulesEngine = DefaultRules()) : RulesEngine {
    internal var world: World? = null
    private val file = Label("//$pkg:combat.star")
    private var warnedUnloaded = false
    private val lastAlert = HashMap<String, Long>()

    /** True if the rules package is loaded and defines [name]. */
    fun defines(name: String): Boolean = host.scripts.function(HookRef(file, name)) != null

    /**
     * Calls [name] with the rules ctx of [c] (or [args] alone when [c] is null) and converts
     * the result with [convert]. Null means "use the fallback".
     */
    private fun <T : Any> run(name: String, c: RulesContext?, args: (Frame) -> List<Any>, deny: Boolean = false, convert: (Any?) -> T?): T? {
        val w = c?.world ?: world ?: return null
        if (host.scripts.packages[pkg] == null) {
            if (pkg in host.failedPackages && !warnedUnloaded) {
                warnedUnloaded = true
                alert(w, name, "the rules package //$pkg failed to load; using the built-in Kotlin ruleset for everything")
            }
            return null
        }
        val ref = HookRef(file, name)
        if (host.scripts.function(ref) == null) return null
        val site = Site(pkg, null, "rules.$name", deny, c?.actor?.id)
        val result = host.call(w, site, ref, { f -> c?.let { RulesCtx(f, it) } ?: Starlark.NONE }, positional = args, budget = host.limits.rulesSteps)
        val value = (result as? CallResult.Ok)?.value
        val converted = if (result is CallResult.Ok) {
            try {
                convert(value) ?: return null
            } catch (e: EvalException) {
                host.report(w, site, "script_error", "rules.$name returned a bad value: ${e.message}", e, null)
                null
            }
        } else {
            null
        }
        if (converted == null) alert(w, name, "rules.$name failed; using the built-in Kotlin rule for this call")
        return converted
    }

    /** Loud, but at most once a minute of game time per function (SPEC §6.3). */
    private fun alert(w: World, name: String, message: String) {
        val last = lastAlert[name]
        if (last != null && w.pulse - last < host.limits.quarantineWindowPulses) return
        lastAlert[name] = w.pulse
        w.record(Diagnostic(w.pulse, "rules", message, pkg = pkg, hook = "rules.$name", seed = w.config.seed))
        host.alerts(message)
    }

    private fun ctxArgs(c: RulesContext, vararg more: Any): (Frame) -> List<Any> = { f -> listOf(RulesCtx(f, c), *more) }

    private fun intOf(name: String): (Any?) -> Int = { v -> int(v, "rules.$name return value") }

    override fun equipmentSlots(): List<String> =
        run("equipment_slots", null, { emptyList() }) { v ->
            strList(v, "rules.equipment_slots return value").also { slots ->
                for (s in slots) if (!Regex("[a-z][a-z0-9_]*").matches(s)) fail("rules.equipment_slots: '$s' is not a valid slot name")
            }
        } ?: fallback.equipmentSlots()

    override fun toHit(c: RulesContext): Int = run("to_hit", c, ctxArgs(c), convert = intOf("to_hit")) ?: fallback.toHit(c)

    override fun damage(c: RulesContext): Int = run("damage", c, ctxArgs(c), convert = intOf("damage")) ?: fallback.damage(c)

    override fun mitigate(c: RulesContext, amount: Int, type: DamageType): Int =
        run("mitigate", c, ctxArgs(c, sInt(amount), type.name), convert = intOf("mitigate"))?.coerceAtLeast(0) ?: fallback.mitigate(c, amount, type)

    override fun initiative(c: RulesContext): Int = run("initiative", c, ctxArgs(c), convert = intOf("initiative")) ?: fallback.initiative(c)

    override fun castAllowed(c: RulesContext): String? {
        if (!defines("cast_allowed") || host.scripts.packages[pkg] == null) return fallback.castAllowed(c)
        // A None result is a real answer here, so it is wrapped to tell it from a failure.
        val answer = run("cast_allowed", c, ctxArgs(c), deny = true) { v -> Allowed(denialValue(v, "rules.cast_allowed return value")) }
        return if (answer == null) fallback.castAllowed(c) else answer.denial
    }

    private class Allowed(val denial: String?)

    override fun interruptCheck(c: RulesContext, amount: Int): Boolean =
        run("interrupt_check", c, ctxArgs(c, sInt(amount))) { v -> bool(v, "rules.interrupt_check return value") } ?: fallback.interruptCheck(c, amount)

    override fun retreatCheck(c: RulesContext): Int = run("retreat_check", c, ctxArgs(c), convert = intOf("retreat_check")) ?: fallback.retreatCheck(c)

    override fun fleeChance(c: RulesContext): Int = run("flee_chance", c, ctxArgs(c), convert = intOf("flee_chance")) ?: fallback.fleeChance(c)

    override fun moveCost(c: RulesContext): Int = run("move_cost", c, ctxArgs(c), convert = intOf("move_cost"))?.coerceAtLeast(0) ?: fallback.moveCost(c)

    override fun regen(c: RulesContext): Regen = run("regen", c, ctxArgs(c)) { v -> regenValue(v, "rules.regen return value") } ?: fallback.regen(c)

    override fun carryLimit(c: RulesContext): Int = run("carry_limit", c, ctxArgs(c), convert = intOf("carry_limit")) ?: fallback.carryLimit(c)

    override fun encumbrance(c: RulesContext): Map<String, Int> =
        run("encumbrance", c, ctxArgs(c)) { v -> intDict(v, "rules.encumbrance return value") } ?: fallback.encumbrance(c)

    override fun getLag(c: RulesContext, band: Band): Int? {
        if (!defines("get_lag") || host.scripts.packages[pkg] == null) return fallback.getLag(c, band)
        val answer = run("get_lag", c, ctxArgs(c, band.word)) { v -> Lag(intOrNull(v, "rules.get_lag return value")) }
        return if (answer == null) fallback.getLag(c, band) else answer.pulses
    }

    private class Lag(val pulses: Int?)

    override fun xpForKill(c: RulesContext): Long =
        run("xp_for_kill", c, ctxArgs(c)) { v -> int(v, "rules.xp_for_kill return value").toLong() } ?: fallback.xpForKill(c)

    override fun xpToLevel(level: Int): Long =
        run("xp_to_level", null, { listOf(sInt(level)) }) { v ->
            int(v, "rules.xp_to_level return value").toLong().also { if (it < 1) fail("rules.xp_to_level must be at least 1") }
        } ?: fallback.xpToLevel(level)

    override fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome =
        run("on_player_death", c, ctxArgs(c)) { v ->
            val d = dict(v, "rules.on_player_death return value")
            val respawn = label(str(d["respawn"], "rules.on_player_death[\"respawn\"]"), pkg, "rules.on_player_death[\"respawn\"]")
            if (c.world.roomOrNull(respawn) == null) fail("rules.on_player_death: there is no room $respawn")
            PlayerDeathOutcome(respawn, (d["xp_loss"]?.let { int(it, "rules.on_player_death[\"xp_loss\"]") } ?: 0).toLong())
        } ?: fallback.onPlayerDeath(c)

    override fun advanceLag(c: RulesContext): Int = fallback.advanceLag(c)

    override fun retreatLag(c: RulesContext): Int = fallback.retreatLag(c)

    override fun retreatFailLag(c: RulesContext): Int = fallback.retreatFailLag(c)

    override fun fleeFailLag(c: RulesContext): Int = fallback.fleeFailLag(c)
}
