package gups.script

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Interrupt
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.SkillProto
import gups.core.model.WorldSchema
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.rules.StartingStats
import gups.core.world.World
import gups.core.world.WorldConfig
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.spelling.SpellChecker

// The rules module: `//rules:combat.star` exports one function per rules call. The package is
// required, so a call that fails gives a neutral result instead of falling back to anything.

/**
 * The read-only `ctx` of a rules function or modifier. It has the call's fields and `rng`, and
 * `deny` for `cast_allowed`; it has no verbs.
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
  fun skill(): Any = field { c.skill?.let { SkillView(it, c.world.schema) } ?: Starlark.NONE }

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
private fun baseValue(point: ModifierPoint, v: Any?, schema: WorldSchema): Any =
  when (point) {
    ModifierPoint.MOVE_COST,
    ModifierPoint.REGEN -> {
      @Suppress("UNCHECKED_CAST")
      TypedMap(TypedKind.VITALS, v as Map<String, Int>, schema.vitalNames)
    }
    ModifierPoint.CAST_ALLOWED -> (v as String?)?.let(::Denial) ?: Starlark.NONE
    else -> sInt(v as Int)
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
    ModifierPoint.MOVE_COST,
    ModifierPoint.REGEN -> typed(v, TypedKind.VITALS, what)
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
      positional = { f -> listOf(RulesCtx(f, c), baseValue(point, base, world.schema)) },
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
 * The Starlark rules module: calls the functions `//<pkg>:combat.star` exports. There is no
 * built-in ruleset to fall back to: a call that fails, returns the wrong type or names an
 * undeclared name records a diagnostic, raises a `rules_error` alert and gives a neutral result
 * that never hurts, kills, rewards or traps anyone. Bound to its world by [StarlarkHost.install].
 */
class StarlarkRules(private val host: StarlarkHost, val pkg: String = "rules") : RulesEngine {
  internal var world: World? = null
  private val file = Label("//$pkg:combat.star")
  private val lastAlert = HashMap<String, Long>()
  private var goodSlots: List<String> = emptyList()

  /**
   * While set, failures are collected here instead of recorded and alerted: the dry run uses it to
   * learn what a rules package gets wrong.
   */
  internal var collected: MutableList<String>? = null

  /** True if the rules package is loaded and defines [export]. */
  fun defines(export: RulesExport): Boolean =
    host.scripts.function(HookRef(file, export.scriptName)) != null

  /**
   * Calls [export] with the rules ctx of [c] (or [args] alone when [c] is null) and converts the
   * result with [convert]. Null means the call failed and the caller gives the neutral result.
   */
  private fun <T : Any> run(
    export: RulesExport,
    c: RulesContext?,
    args: (Frame) -> List<Any>,
    deny: Boolean = false,
    convert: (Any?) -> T,
  ): T? {
    val name = export.scriptName
    val w = c?.world ?: world ?: return null
    val ref = HookRef(file, name)
    if (host.scripts.packages[pkg] == null || host.scripts.function(ref) == null) {
      fault(w, name, "rules.$name is not defined by //$pkg")
      return null
    }
    val site = Site(pkg, null, "rules.$name", deny, c?.actor?.id)
    val sink = collected
    val result =
      host.call(
        w,
        site,
        ref,
        { f -> c?.let { RulesCtx(f, it) } ?: Starlark.NONE },
        positional = args,
        budget = host.limits.rulesSteps,
        recordErrors = sink == null,
      )
    when (result) {
      is CallResult.Ok ->
        try {
          return convert(result.value)
        } catch (e: EvalException) {
          if (sink == null)
            host.report(
              w,
              site,
              "script_error",
              "rules.$name returned a bad value: ${e.message}",
              e,
              null,
            )
          else sink += "rules.$name returned a bad value: ${e.message}"
        }
      is CallResult.Failed -> if (sink != null) sink += "rules.$name failed: ${result.message}"
      CallResult.Skipped -> Unit
    }
    fault(w, name, "rules.$name failed; using its neutral result for this call")
    return null
  }

  /** Loud, but at most once a minute of game time per function. */
  private fun fault(w: World, name: String, message: String) {
    if (collected != null) return
    val last = lastAlert[name]
    if (last != null && w.pulse - last < host.limits.quarantineWindowPulses) return
    lastAlert[name] = w.pulse
    host.alerts("rules_error", message)
  }

  private fun ctxArgs(c: RulesContext, vararg more: Any): (Frame) -> List<Any> = { f ->
    listOf(RulesCtx(f, c), *more)
  }

  private fun intOf(export: RulesExport): (Any?) -> Int = { v ->
    int(v, "rules.${export.scriptName} return value")
  }

  private fun vitalsOf(export: RulesExport): (Any?) -> Map<String, Int> = { v ->
    typed(v, TypedKind.VITALS, "rules.${export.scriptName} return value")
  }

  override fun equipmentSlots(): List<String> =
    run(RulesExport.EQUIPMENT_SLOTS, null, { emptyList() }) { v ->
        strList(v, "rules.equipment_slots return value").also { slots ->
          for (s in slots) if (!Regex("[a-z][a-z0-9_]*").matches(s))
            fail("rules.equipment_slots: '$s' is not a valid slot name")
        }
      }
      ?.also { goodSlots = it } ?: goodSlots

  override fun startingStats(c: RulesContext): StartingStats =
    run(RulesExport.STARTING_STATS, c, ctxArgs(c)) { v ->
      val what = "rules.starting_stats return value"
      val fields =
        (v as? StructValue)?.fields ?: fail("$what: expected a struct, got ${typeName(v)}")
      for (k in fields.keys) if (k !in STARTING_FIELDS)
        fail("$what: unknown field '$k'${SpellChecker.didYouMean(k, STARTING_FIELDS)}")
      StartingStats(
        stats = typed(fields["stats"], TypedKind.STATS, "$what.stats"),
        max = typed(fields["max"], TypedKind.VITALS, "$what.max"),
        current = typed(fields["current"], TypedKind.VITALS, "$what.current"),
      )
    } ?: StartingStats()

  override fun condition(c: RulesContext): Condition =
    run(RulesExport.CONDITION, c, ctxArgs(c)) { v ->
      val word = str(v, "rules.condition return value")
      Condition.parse(word)
        ?: fail("rules.condition return value: '$word' is not 'ok', 'incapacitated' or 'dead'")
    } ?: c.actor.condition

  override fun toHit(c: RulesContext): Int =
    run(RulesExport.TO_HIT, c, ctxArgs(c), convert = intOf(RulesExport.TO_HIT)) ?: 0

  override fun damage(c: RulesContext): Int =
    run(RulesExport.DAMAGE, c, ctxArgs(c), convert = intOf(RulesExport.DAMAGE)) ?: 0

  override fun mitigate(c: RulesContext, amount: Int, type: DamageType): Map<String, Int> =
    run(
      RulesExport.MITIGATE,
      c,
      ctxArgs(c, sInt(amount), type.name),
      convert = vitalsOf(RulesExport.MITIGATE),
    ) ?: emptyMap()

  override fun initiative(c: RulesContext): Int =
    run(RulesExport.INITIATIVE, c, ctxArgs(c), convert = intOf(RulesExport.INITIATIVE)) ?: 0

  override fun castAllowed(c: RulesContext): String? {
    // A None result is a real answer here, so it is wrapped to tell it from a failure.
    val answer =
      run(RulesExport.CAST_ALLOWED, c, ctxArgs(c), deny = true) { v ->
        Allowed(denialValue(v, "rules.cast_allowed return value"))
      }
    return if (answer == null) NOTHING_HAPPENS else answer.denial
  }

  private class Allowed(val denial: String?)

  override fun interruptCheck(c: RulesContext, amount: Int): Boolean =
    run(RulesExport.INTERRUPT_CHECK, c, ctxArgs(c, sInt(amount))) { v ->
      bool(v, "rules.interrupt_check return value")
    } ?: false

  override fun retreatCheck(c: RulesContext): Int =
    run(RulesExport.RETREAT_CHECK, c, ctxArgs(c), convert = intOf(RulesExport.RETREAT_CHECK)) ?: 0

  override fun fleeChance(c: RulesContext): Int =
    run(RulesExport.FLEE_CHANCE, c, ctxArgs(c), convert = intOf(RulesExport.FLEE_CHANCE)) ?: 0

  override fun moveCost(c: RulesContext): Map<String, Int> =
    run(RulesExport.MOVE_COST, c, ctxArgs(c), convert = vitalsOf(RulesExport.MOVE_COST))
      ?.mapValues { it.value.coerceAtLeast(0) } ?: emptyMap()

  override fun regen(c: RulesContext): Map<String, Int> =
    run(RulesExport.REGEN, c, ctxArgs(c), convert = vitalsOf(RulesExport.REGEN)) ?: emptyMap()

  override fun carryLimit(c: RulesContext): Int =
    run(RulesExport.CARRY_LIMIT, c, ctxArgs(c), convert = intOf(RulesExport.CARRY_LIMIT))
      ?: Int.MAX_VALUE

  override fun encumbrance(c: RulesContext): Map<String, Int> =
    run(RulesExport.ENCUMBRANCE, c, ctxArgs(c)) { v ->
      typed(v, TypedKind.STATS, "rules.encumbrance return value")
    } ?: emptyMap()

  override fun getLag(c: RulesContext, band: Band): Int? {
    val answer =
      run(RulesExport.GET_LAG, c, ctxArgs(c, band.word)) { v ->
        Lag(intOrNull(v, "rules.get_lag return value"))
      }
    return if (answer == null) 0 else answer.pulses
  }

  private class Lag(val pulses: Int?)

  override fun xpForKill(c: RulesContext): Long =
    run(RulesExport.XP_FOR_KILL, c, ctxArgs(c)) { v ->
      int(v, "rules.xp_for_kill return value").toLong()
    } ?: 0

  override fun xpToLevel(level: Int): Long =
    run(RulesExport.XP_TO_LEVEL, null, { listOf(sInt(level)) }) { v ->
      int(v, "rules.xp_to_level return value").toLong().also {
        if (it < 1) fail("rules.xp_to_level must be at least 1")
      }
    } ?: Long.MAX_VALUE

  override fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome =
    run(RulesExport.ON_PLAYER_DEATH, c, ctxArgs(c)) { v ->
      val what = "rules.on_player_death return value"
      val d = dict(v, what)
      for (k in d.keys) choice(k, what, DEATH_FIELDS)
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
        d["vitals"]?.let { typed(it, TypedKind.VITALS, "rules.on_player_death[\"vitals\"]") },
        (d["xp_loss"]?.let { int(it, "rules.on_player_death[\"xp_loss\"]") } ?: 0).toLong(),
      )
    } ?: PlayerDeathOutcome((c.actor as? Player)?.recallRoom ?: c.actor.room.label, null, 0)

  companion object {
    private const val NOTHING_HAPPENS = "Nothing happens."
    private val STARTING_FIELDS = listOf("stats", "max", "current")
    private val DEATH_FIELDS = listOf("respawn", "vitals", "xp_loss")
  }
}

/** The functions `//rules:combat.star` may export. [RULES_EXPORTS] documents each. */
enum class RulesExport(val scriptName: String) {
  EQUIPMENT_SLOTS("equipment_slots"),
  STARTING_STATS("starting_stats"),
  CONDITION("condition"),
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

/** The docs of every [RulesExport]. */
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
      export = RulesExport.STARTING_STATS,
      signature = "starting_stats(ctx)",
      returnType = "struct(stats, max, current)",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc =
        "Numbers for a new character or spawned mob, as struct(stats = stats(...), max = vitals(...), current = vitals(...)); every field is optional and a current value left out equals the maximum.",
    ),
    RulesExportSpec(
      export = RulesExport.CONDITION,
      signature = "condition(ctx)",
      returnType = "str",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc =
        "The actor's life state: 'ok', 'incapacitated' or 'dead'. Asked after any change to one of its vitals and at each world tick.",
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
      returnType = "vitals",
      ctxFields =
        listOf("actor", "attacker", "defender", "target", "weapon", "room", "pulse", "rng"),
      doc = "The vital losses that amount damage of type causes the defender.",
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
      doc = "True if a damage event with this total loss interrupts an in-progress cast.",
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
      returnType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "The vitals spent when leaving a room.",
    ),
    RulesExportSpec(
      export = RulesExport.REGEN,
      signature = "regen(ctx)",
      returnType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "What each vital gains per world tick; negative values lose.",
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
      returnType = "stats",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Stat deltas applied while the actor carries more than carry_limit.",
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
      doc =
        "Player death outcome as {'respawn': label, 'vitals': vitals(...), 'xp_loss': int}; vitals sets the respawned character's current vitals.",
    ),
  )

/** The modifier points in the rules modifier chain. */
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
      valueType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts the vitals spent for leaving a room.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.REGEN,
      signature = "fn(ctx, base)",
      valueType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts the per-tick regeneration of each vital.",
    ),
  )

/**
 * The load-time dry run of the rules package [pkg] in [result]: every export it defines is called
 * against synthetic actors built from [schema], a fresh player and a fresh mob of each loaded
 * prototype, all after `starting_stats`, and `mitigate` once per declared damage type. Returns what
 * went wrong, one line per failed call; empty when the package is sound.
 */
internal fun dryRunRules(
  result: LoadResult,
  schema: WorldSchema,
  limits: ScriptLimits,
  pkg: String = "rules",
): List<String> {
  val host = StarlarkHost(result, limits)
  val rules = host.rules(pkg)
  val problems = mutableListOf<String>()
  rules.collected = problems
  val room = RoomProto(Label("//engine:dry_run"), "The dry run")
  val protos = Prototypes(rooms = listOf(room), mobs = result.prototypes().mobs.values.toList())
  val world =
    World(protos, schema, rules, config = WorldConfig(seed = 0, strict = false)).also {
      rules.world = it
    }
  val skill = SkillProto(Label("//engine:dry_run_skill"), interrupt = Interrupt(damagePct = 10))
  try {
    val at = room.label
    val player = world.spawnPlayer("Dryrunner", at)
    val foe = world.spawnPlayer("Dryrunfoe", at)
    val mobs = protos.mobs.keys.map { world.spawnMob(it, world.room(at)) }
    val actors = listOf(player, foe) + mobs
    rules.equipmentSlots()
    rules.xpToLevel(1)
    for (a in actors) {
      val other = if (a === player) foe else player
      val c = RulesContext(world, a, other)
      val withSkill = RulesContext(world, a, other, skill = skill)
      rules.condition(c)
      rules.toHit(c)
      rules.damage(c)
      rules.initiative(c)
      rules.carryLimit(c)
      rules.encumbrance(c)
      rules.regen(c)
      rules.moveCost(c)
      rules.fleeChance(c)
      rules.retreatCheck(c)
      rules.castAllowed(withSkill)
      rules.interruptCheck(withSkill, 1)
      rules.xpForKill(c)
      for (type in schema.damageTypes) rules.mitigate(c, 5, DamageType(type))
      for (band in Band.entries) rules.getLag(
        RulesContext(world, a, item = null, band = band),
        band,
      )
      if (a is Player) rules.onPlayerDeath(RulesContext(world, a))
    }
  } catch (e: RuntimeException) {
    problems += "the dry run could not set up its actors: ${e.message}"
  } finally {
    rules.collected = null
  }
  return problems.distinct()
}
