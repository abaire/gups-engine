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
class RulesCtx internal constructor(private val frame: Frame, private val c: RulesContext) :
  StarlarkValue {
  private fun <T> field(block: () -> T): T {
    frame.check()
    return block()
  }

  private fun v(e: gups.core.model.Entity?): Any = view(frame, e)

  @StarlarkMethod(
    name = "actor",
    structField = true,
    doc = "Whom the call is about: the attacker, caster, mover or regenerating actor.",
  )
  fun actor(): Any = field { v(c.actor) }

  @StarlarkMethod(
    name = "attacker",
    structField = true,
    doc = "For attack calls, the same as actor.",
  )
  fun attacker(): Any = field { v(c.actor) }

  @StarlarkMethod(
    name = "defender",
    structField = true,
    doc = "For attack calls, the one attacked; also the one taking damage in mitigate.",
  )
  fun defender(): Any = field { v(c.target) }

  @StarlarkMethod(
    name = "target",
    structField = true,
    doc = "The other actor of the call, if any: the defender, the pinner, the victim, the killer.",
  )
  fun target(): Any = field { v(c.target) }

  @StarlarkMethod(
    name = "weapon",
    structField = true,
    doc = "The weapon used in an attack, or None for unarmed.",
  )
  fun weapon(): Any = field { v(c.weapon) }

  @StarlarkMethod(name = "item", structField = true, doc = "The item, for get_lag and carry_limit.")
  fun item(): Any = field { v(c.item) }

  @StarlarkMethod(
    name = "band",
    structField = true,
    doc = "'melee', 'near' or 'far' between actor and target, or None.",
  )
  fun band(): Any = field { c.band?.word ?: Starlark.NONE }

  @StarlarkMethod(
    name = "skill",
    structField = true,
    doc = "The skill being cast or evaluated, or None.",
  )
  fun skill(): Any = field { c.skill?.let(::SkillView) ?: Starlark.NONE }

  @StarlarkMethod(name = "room", structField = true, doc = "The actor's room.")
  fun room(): Any = field { v(c.actor.room) }

  @StarlarkMethod(name = "pulse", structField = true, doc = "Game time.")
  fun pulse(): Any = field { sInt(c.world.pulse) }

  @StarlarkMethod(name = "rng", structField = true, doc = "The seeded random number generator.")
  fun rng(): Any = field { RngView(frame) }

  @StarlarkMethod(
    name = "roll",
    doc = "Rolls dice such as '2d6+1' with the seeded rng.",
    parameters = [Param(name = "dice", named = true, doc = "Dice expression such as '2d6+1'.")],
  )
  fun roll(dice: Any): Any = field {
    val d =
      try {
        gups.core.model.Dice.parse(str(dice, "roll(dice)"))
      } catch (e: IllegalArgumentException) {
        fail("roll(): ${e.message}")
      }
    sInt(d.roll(c.world.rng))
  }

  @StarlarkMethod(
    name = "deny",
    doc = "Refuses a cast; valid only in cast_allowed and its modifiers.",
    parameters = [Param(name = "msg", named = true, doc = "Text shown to the caster.")],
  )
  fun deny(msg: Any): Any = field {
    if (!frame.site.vetoable)
      fail("deny() is only valid in cast_allowed (this is ${frame.site.hook})")
    Denial(checkString(str(msg, "deny(msg)"), "deny(msg)", frame.host.limits))
  }

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<rules ctx ${frame.site.hook} of ${c.actor.name}>")
  }
}

/** Converts a rules result for [point] to Starlark, as a modifier's `base` argument. */
private fun baseValue(point: ModifierPoint, v: Any?): Any =
  when (point) {
    ModifierPoint.REGEN ->
      (v as Regen).let { r ->
        sDict(mapOf("hp" to sInt(r.hp), "mana" to sInt(r.mana), "move" to sInt(r.move)))
      }
    ModifierPoint.CAST_ALLOWED -> (v as String?)?.let(::Denial) ?: Starlark.NONE
    else -> sInt(v as Int)
  }

private fun regenValue(v: Any?, what: String): Regen {
  val d = dict(v, what)
  for (k in d.keys) choice(k, "$what key", listOf("hp", "mana", "move"))
  fun get(k: String) = d[k]?.let { int(it, "$what[\"$k\"]") } ?: 0
  return Regen(get("hp"), get("mana"), get("move"))
}

private fun denialValue(v: Any?, what: String): String? =
  when {
    isNone(v) -> null
    v is Denial -> v.message
    else -> fail("$what: expected None or ctx.deny(msg), got ${typeName(v)}")
  }

/** Converts a modifier's return value for [point] back to the engine's type. */
private fun pointValue(point: ModifierPoint, v: Any?, what: String): Any? =
  when (point) {
    ModifierPoint.REGEN -> regenValue(v, what)
    ModifierPoint.CAST_ALLOWED -> denialValue(v, what)
    else -> int(v, what)
  }

/**
 * Runs one modifier for [StarlarkHost.modify]: `fn(ctx, base)` with its own rules budget, charged
 * to the package of the function's file. A failure keeps [base].
 */
internal fun StarlarkHost.runModifier(
  world: World,
  point: ModifierPoint,
  ref: HookRef,
  source: String,
  c: RulesContext,
  base: Any?,
): Any? {
  val site =
    Site(
      ref.file.pkg,
      null,
      "modifier ${point.scriptName} ($source)",
      point == ModifierPoint.CAST_ALLOWED,
      c.actor.id,
    )
  var out: Any? = base
  val result =
    call(
      world,
      site,
      ref,
      { f -> RulesCtx(f, c) },
      positional = { f -> listOf(RulesCtx(f, c), baseValue(point, base)) },
      budget = limits.rulesSteps,
    )
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
 * export it lacks, and any call that fails, uses [fallback], the built-in Kotlin ruleset (SPEC
 * §6.3). Bound to its world by [StarlarkHost.install].
 */
class StarlarkRules(
  private val host: StarlarkHost,
  val pkg: String = "rules",
  private val fallback: RulesEngine = DefaultRules(),
) : RulesEngine {
  internal var world: World? = null
  private val file = Label("//$pkg:combat.star")
  private var warnedUnloaded = false
  private val lastAlert = HashMap<String, Long>()

  /** True if the rules package is loaded and defines [export]. */
  fun defines(export: RulesExport): Boolean =
    host.scripts.function(HookRef(file, export.scriptName)) != null

  /**
   * Calls [export] with the rules ctx of [c] (or [args] alone when [c] is null) and converts the
   * result with [convert]. Null means "use the fallback".
   */
  private fun <T : Any> run(
    export: RulesExport,
    c: RulesContext?,
    args: (Frame) -> List<Any>,
    deny: Boolean = false,
    convert: (Any?) -> T?,
  ): T? {
    val name = export.scriptName
    val w = c?.world ?: world ?: return null
    if (host.scripts.packages[pkg] == null) {
      if (pkg in host.failedPackages && !warnedUnloaded) {
        warnedUnloaded = true
        alert(
          w,
          name,
          "the rules package //$pkg failed to load; using the built-in Kotlin ruleset for everything",
        )
      }
      return null
    }
    val ref = HookRef(file, name)
    if (host.scripts.function(ref) == null) return null
    val site = Site(pkg, null, "rules.$name", deny, c?.actor?.id)
    val result =
      host.call(
        w,
        site,
        ref,
        { f -> c?.let { RulesCtx(f, it) } ?: Starlark.NONE },
        positional = args,
        budget = host.limits.rulesSteps,
      )
    val value = (result as? CallResult.Ok)?.value
    val converted =
      if (result is CallResult.Ok) {
        try {
          convert(value) ?: return null
        } catch (e: EvalException) {
          host.report(
            w,
            site,
            "script_error",
            "rules.$name returned a bad value: ${e.message}",
            e,
            null,
          )
          null
        }
      } else {
        null
      }
    if (converted == null)
      alert(w, name, "rules.$name failed; using the built-in Kotlin rule for this call")
    return converted
  }

  /** Loud, but at most once a minute of game time per function (SPEC §6.3). */
  private fun alert(w: World, name: String, message: String) {
    val last = lastAlert[name]
    if (last != null && w.pulse - last < host.limits.quarantineWindowPulses) return
    lastAlert[name] = w.pulse
    w.record(
      Diagnostic(w.pulse, "rules", message, pkg = pkg, hook = "rules.$name", seed = w.config.seed)
    )
    host.alerts(message)
  }

  private fun ctxArgs(c: RulesContext, vararg more: Any): (Frame) -> List<Any> = { f ->
    listOf(RulesCtx(f, c), *more)
  }

  private fun intOf(export: RulesExport): (Any?) -> Int = { v ->
    int(v, "rules.${export.scriptName} return value")
  }

  override fun equipmentSlots(): List<String> =
    run(RulesExport.EQUIPMENT_SLOTS, null, { emptyList() }) { v ->
      strList(v, "rules.equipment_slots return value").also { slots ->
        for (s in slots) if (!Regex("[a-z][a-z0-9_]*").matches(s))
          fail("rules.equipment_slots: '$s' is not a valid slot name")
      }
    } ?: fallback.equipmentSlots()

  override fun toHit(c: RulesContext): Int =
    run(RulesExport.TO_HIT, c, ctxArgs(c), convert = intOf(RulesExport.TO_HIT)) ?: fallback.toHit(c)

  override fun damage(c: RulesContext): Int =
    run(RulesExport.DAMAGE, c, ctxArgs(c), convert = intOf(RulesExport.DAMAGE))
      ?: fallback.damage(c)

  override fun mitigate(c: RulesContext, amount: Int, type: DamageType): Int =
    run(
        RulesExport.MITIGATE,
        c,
        ctxArgs(c, sInt(amount), type.name),
        convert = intOf(RulesExport.MITIGATE),
      )
      ?.coerceAtLeast(0) ?: fallback.mitigate(c, amount, type)

  override fun initiative(c: RulesContext): Int =
    run(RulesExport.INITIATIVE, c, ctxArgs(c), convert = intOf(RulesExport.INITIATIVE))
      ?: fallback.initiative(c)

  override fun castAllowed(c: RulesContext): String? {
    if (!defines(RulesExport.CAST_ALLOWED) || host.scripts.packages[pkg] == null)
      return fallback.castAllowed(c)
    // A None result is a real answer here, so it is wrapped to tell it from a failure.
    val answer =
      run(RulesExport.CAST_ALLOWED, c, ctxArgs(c), deny = true) { v ->
        Allowed(denialValue(v, "rules.cast_allowed return value"))
      }
    return if (answer == null) fallback.castAllowed(c) else answer.denial
  }

  private class Allowed(val denial: String?)

  override fun interruptCheck(c: RulesContext, amount: Int): Boolean =
    run(RulesExport.INTERRUPT_CHECK, c, ctxArgs(c, sInt(amount))) { v ->
      bool(v, "rules.interrupt_check return value")
    } ?: fallback.interruptCheck(c, amount)

  override fun retreatCheck(c: RulesContext): Int =
    run(RulesExport.RETREAT_CHECK, c, ctxArgs(c), convert = intOf(RulesExport.RETREAT_CHECK))
      ?: fallback.retreatCheck(c)

  override fun fleeChance(c: RulesContext): Int =
    run(RulesExport.FLEE_CHANCE, c, ctxArgs(c), convert = intOf(RulesExport.FLEE_CHANCE))
      ?: fallback.fleeChance(c)

  override fun moveCost(c: RulesContext): Int =
    run(RulesExport.MOVE_COST, c, ctxArgs(c), convert = intOf(RulesExport.MOVE_COST))
      ?.coerceAtLeast(0) ?: fallback.moveCost(c)

  override fun regen(c: RulesContext): Regen =
    run(RulesExport.REGEN, c, ctxArgs(c)) { v -> regenValue(v, "rules.regen return value") }
      ?: fallback.regen(c)

  override fun carryLimit(c: RulesContext): Int =
    run(RulesExport.CARRY_LIMIT, c, ctxArgs(c), convert = intOf(RulesExport.CARRY_LIMIT))
      ?: fallback.carryLimit(c)

  override fun encumbrance(c: RulesContext): Map<String, Int> =
    run(RulesExport.ENCUMBRANCE, c, ctxArgs(c)) { v ->
      intDict(v, "rules.encumbrance return value")
    } ?: fallback.encumbrance(c)

  override fun getLag(c: RulesContext, band: Band): Int? {
    if (!defines(RulesExport.GET_LAG) || host.scripts.packages[pkg] == null)
      return fallback.getLag(c, band)
    val answer =
      run(RulesExport.GET_LAG, c, ctxArgs(c, band.word)) { v ->
        Lag(intOrNull(v, "rules.get_lag return value"))
      }
    return if (answer == null) fallback.getLag(c, band) else answer.pulses
  }

  private class Lag(val pulses: Int?)

  override fun xpForKill(c: RulesContext): Long =
    run(RulesExport.XP_FOR_KILL, c, ctxArgs(c)) { v ->
      int(v, "rules.xp_for_kill return value").toLong()
    } ?: fallback.xpForKill(c)

  override fun xpToLevel(level: Int): Long =
    run(RulesExport.XP_TO_LEVEL, null, { listOf(sInt(level)) }) { v ->
      int(v, "rules.xp_to_level return value").toLong().also {
        if (it < 1) fail("rules.xp_to_level must be at least 1")
      }
    } ?: fallback.xpToLevel(level)

  override fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome =
    run(RulesExport.ON_PLAYER_DEATH, c, ctxArgs(c)) { v ->
      val d = dict(v, "rules.on_player_death return value")
      val respawn =
        label(
          str(d["respawn"], "rules.on_player_death[\"respawn\"]"),
          pkg,
          "rules.on_player_death[\"respawn\"]",
        )
      if (c.world.roomOrNull(respawn) == null)
        fail("rules.on_player_death: there is no room $respawn")
      PlayerDeathOutcome(
        respawn,
        (d["xp_loss"]?.let { int(it, "rules.on_player_death[\"xp_loss\"]") } ?: 0).toLong(),
      )
    } ?: fallback.onPlayerDeath(c)

  override fun advanceLag(c: RulesContext): Int = fallback.advanceLag(c)

  override fun retreatLag(c: RulesContext): Int = fallback.retreatLag(c)

  override fun retreatFailLag(c: RulesContext): Int = fallback.retreatFailLag(c)

  override fun fleeFailLag(c: RulesContext): Int = fallback.fleeFailLag(c)
}

/** The functions `//rules:combat.star` may export (SPEC §4.6). [RULES_EXPORTS] documents each. */
enum class RulesExport(val scriptName: String) {
  EQUIPMENT_SLOTS("equipment_slots"),
  TO_HIT("to_hit"),
  DAMAGE("damage"),
  MITIGATE("mitigate"),
  INITIATIVE("initiative"),
  CAST_ALLOWED("cast_allowed"),
  INTERRUPT_CHECK("interrupt_check"),
  RETREAT_CHECK("retreat_check"),
  FLEE_CHANCE("flee_chance"),
  MOVE_COST("move_cost"),
  REGEN("regen"),
  CARRY_LIMIT("carry_limit"),
  ENCUMBRANCE("encumbrance"),
  GET_LAG("get_lag"),
  XP_FOR_KILL("xp_for_kill"),
  XP_TO_LEVEL("xp_to_level"),
  ON_PLAYER_DEATH("on_player_death"),
}

/** The docs of every [RulesExport] (SPEC §4.6, M7.1). */
internal val RULES_EXPORTS: List<RulesExportSpec> =
  listOf(
    RulesExportSpec(
      export = RulesExport.EQUIPMENT_SLOTS,
      signature = "equipment_slots()",
      returnType = "list[str]",
      ctxFields = emptyList(),
      doc = "Equipment slot names in display and modifier evaluation order.",
    ),
    RulesExportSpec(
      export = RulesExport.TO_HIT,
      signature = "to_hit(ctx)",
      returnType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "pulse",
          "rng",
        ),
      doc = "Hit chance in percent before modifiers.",
    ),
    RulesExportSpec(
      export = RulesExport.DAMAGE,
      signature = "damage(ctx)",
      returnType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "pulse",
          "rng",
        ),
      doc = "Raw attack damage before modifiers and mitigation.",
    ),
    RulesExportSpec(
      export = RulesExport.MITIGATE,
      signature = "mitigate(ctx, amount, type)",
      returnType = "int",
      ctxFields =
        listOf("actor", "attacker", "defender", "target", "weapon", "room", "pulse", "rng"),
      doc = "Damage remaining after armor and resistances.",
    ),
    RulesExportSpec(
      export = RulesExport.INITIATIVE,
      signature = "initiative(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Combat round initiative score; higher acts first.",
    ),
    RulesExportSpec(
      export = RulesExport.CAST_ALLOWED,
      signature = "cast_allowed(ctx)",
      returnType = "None | denial",
      ctxFields = listOf("actor", "target", "skill", "band", "room", "pulse", "rng"),
      doc = "Returns None if casting is allowed, or ctx.deny(msg) to refuse.",
    ),
    RulesExportSpec(
      export = RulesExport.INTERRUPT_CHECK,
      signature = "interrupt_check(ctx, amount)",
      returnType = "bool",
      ctxFields = listOf("actor", "skill", "room", "pulse", "rng"),
      doc = "True if taking amount damage interrupts an in-progress cast.",
    ),
    RulesExportSpec(
      export = RulesExport.RETREAT_CHECK,
      signature = "retreat_check(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "room", "pulse", "rng"),
      doc = "Chance in percent to retreat along the track when pinned in melee.",
    ),
    RulesExportSpec(
      export = RulesExport.FLEE_CHANCE,
      signature = "flee_chance(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Chance in percent for a fleeing actor to escape through an exit.",
    ),
    RulesExportSpec(
      export = RulesExport.MOVE_COST,
      signature = "move_cost(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Movement points spent when leaving a room.",
    ),
    RulesExportSpec(
      export = RulesExport.REGEN,
      signature = "regen(ctx)",
      returnType = "dict[str, int]",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Vitals regenerated per world tick as {'hp': int, 'mana': int, 'move': int}.",
    ),
    RulesExportSpec(
      export = RulesExport.CARRY_LIMIT,
      signature = "carry_limit(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "item", "room", "pulse", "rng"),
      doc = "Maximum weight the actor can carry without encumbrance.",
    ),
    RulesExportSpec(
      export = RulesExport.ENCUMBRANCE,
      signature = "encumbrance(ctx)",
      returnType = "dict[str, int]",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Penalties applied while the actor carries more than carry_limit.",
    ),
    RulesExportSpec(
      export = RulesExport.GET_LAG,
      signature = "get_lag(ctx, band)",
      returnType = "int | None",
      ctxFields = listOf("actor", "item", "room", "pulse", "rng"),
      doc = "Wait-state pulses for picking up an item at band, or None if out of reach.",
    ),
    RulesExportSpec(
      export = RulesExport.XP_FOR_KILL,
      signature = "xp_for_kill(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "room", "pulse", "rng"),
      doc = "Experience points awarded to actor for slaying target.",
    ),
    RulesExportSpec(
      export = RulesExport.XP_TO_LEVEL,
      signature = "xp_to_level(level)",
      returnType = "int",
      ctxFields = emptyList(),
      doc = "Total experience points required to reach the given level.",
    ),
    RulesExportSpec(
      export = RulesExport.ON_PLAYER_DEATH,
      signature = "on_player_death(ctx)",
      returnType = "dict[str, any]",
      ctxFields = listOf("actor", "target", "room", "pulse", "rng"),
      doc = "Player death outcome as {'respawn': label, 'xp_loss': int}.",
    ),
  )

/** The modifier points in the rules modifier chain (SPEC §4.6, M7.1). */
internal val MODIFIER_POINTS: List<ModifierPointSpec> =
  listOf(
    ModifierPointSpec(
      point = ModifierPoint.TO_HIT,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "pulse",
          "rng",
        ),
      doc = "Adjusts hit chance in percent.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.DAMAGE,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "pulse",
          "rng",
        ),
      doc = "Adjusts raw damage before mitigation.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.INITIATIVE,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts combat round initiative.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.CAST_ALLOWED,
      signature = "fn(ctx, base)",
      valueType = "None | denial",
      ctxFields = listOf("actor", "target", "skill", "band", "room", "pulse", "rng"),
      doc = "Returns None to allow casting, or ctx.deny(msg) to refuse.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.MOVE_COST,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts movement point cost for leaving a room.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.REGEN,
      signature = "fn(ctx, base)",
      valueType = "dict[str, int]",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts per-tick regeneration dict {'hp': int, 'mana': int, 'move': int}.",
    ),
  )
