package gups.harness

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Actor
import gups.core.model.DamageType
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.StartingStats
import gups.core.world.Diagnostic
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.damage
import gups.core.world.flushOutput
import gups.core.world.grantTrait
import gups.core.world.handle
import gups.core.world.removeTrait
import gups.core.world.setChoices
import gups.core.world.spawnItem
import gups.core.world.submit
import gups.script.ActorView
import gups.script.EntityView
import gups.script.ItemView
import gups.script.RoomView
import gups.script.StarlarkHost
import gups.script.TypeSpec
import gups.script.ViewScope
import gups.script.callerPackage
import gups.script.damageTypeName
import gups.script.describeBuiltin
import gups.script.starlarkValue
import gups.script.statsValue
import gups.script.storableValue
import gups.script.view
import gups.script.vitalName
import gups.script.vitalsValue
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

// The Starlark objects of the test harness API: the world handle `w` and player
// handles `p`. `t` is in Assertions.kt.

internal fun evalFail(message: String): Nothing = throw EvalException(message)

internal fun typeName(v: Any?): String = if (v == null) "None" else Starlark.type(v)

internal fun isNone(v: Any?): Boolean = v == null || v === Starlark.NONE

internal fun str(v: Any?, what: String): String =
  v as? String ?: evalFail("$what: expected a string, got ${typeName(v)}")

internal fun int(v: Any?, what: String): Int =
  (v as? StarlarkInt)?.toInt(what) ?: evalFail("$what: expected an int, got ${typeName(v)}")

internal fun intOrNull(v: Any?, what: String): Int? = if (isNone(v)) null else int(v, what)

internal fun label(text: String, pkg: String?, what: String): Label =
  try {
    Label.parse(text, pkg)
  } catch (e: IllegalArgumentException) {
    evalFail("$what: ${e.message}")
  }

/** Commands run by `p.do()` must finish within this many pulses. */
const val DO_MAX_PULSES = 1000

/** Keeps at most this many trace lines per world. */
private const val TRACE_KEEP = 400

/** One headless world created by `t.world(...)`: virtual clock, seeded RNG, no network. */
@StarlarkBuiltin(name = "world", doc = "A headless test world.")
class WorldHandle
internal constructor(
  override val world: World,
  val host: StarlarkHost,
  /** The test file's package, for relative labels and attribute keys. */
  override val pkg: String,
) : StarlarkValue, ViewScope {
  private var nextSession = 1L
  internal val traceLines = ArrayDeque<String>()
  private var traceDropped = 0

  override fun check() = Unit

  private fun pkgOf(thread: StarlarkThread) = callerPackage(thread) ?: pkg

  private fun roomArg(v: Any?, what: String, thread: StarlarkThread): Room =
    when (v) {
      is RoomView -> v.room
      is String ->
        world.roomOrNull(label(v, pkgOf(thread), what)) ?: evalFail("$what: there is no room $v")
      else -> evalFail("$what: expected a room or room label, got ${typeName(v)}")
    }

  private fun entityArg(v: Any?, what: String): Entity =
    (v as? EntityView)?.entity ?: evalFail("$what: expected an entity, got ${typeName(v)}")

  private fun actorArg(v: Any?, what: String): Actor =
    (v as? ActorView)?.actor ?: evalFail("$what: expected an actor, got ${typeName(v)}")

  private fun playerArg(v: Any?, what: String): Player =
    ((v as? ActorView)?.actor as? Player)
      ?: evalFail("$what: expected a player, got ${typeName(v)}")

  @StarlarkMethod(
    name = "spawn_player",
    doc =
      "Adds a connected player and returns its handle. The numbers come from rules.starting_stats; vitals and stats override them.",
    parameters =
      [
        Param(name = "name", named = true, doc = "Player name."),
        Param(name = "at", named = true, doc = "Starting room label."),
        Param(name = "level", named = true, defaultValue = "1", doc = "Player level."),
        Param(
          name = "vitals",
          named = true,
          defaultValue = "None",
          doc = "Starting and maximum vitals, built with vitals(...), or None for the rules'.",
        ),
        Param(
          name = "stats",
          named = true,
          defaultValue = "None",
          doc = "Base stats, built with stats(...), or None for the rules'.",
        ),
        Param(
          name = "skills",
          named = true,
          defaultValue = "None",
          doc = "Known skills mapping skill label to proficiency, or None.",
        ),
        Param(
          name = "index",
          named = true,
          defaultValue = "0",
          doc = "Track index in the starting room.",
        ),
        Param(
          name = "choices",
          named = true,
          defaultValue = "None",
          doc = "Character creation answers, or None.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun spawnPlayer(
    name: Any,
    at: Any,
    level: Any,
    vitals: Any,
    stats: Any,
    skills: Any,
    index: Any,
    choices: Any,
    thread: StarlarkThread,
  ): Any {
    val room = roomArg(at, "spawn_player(at)", thread)
    val overrideVitals = vitalsValue(vitals, "spawn_player(vitals)")
    val overrideStats = statsValue(stats, "spawn_player(stats)")
    val choicesMap = LinkedHashMap<String, String>()
    if (!isNone(choices)) {
      val dict = choices as? Dict<*, *> ?: evalFail("spawn_player(choices): expected a dict")
      for ((k, v) in dict.entries) {
        choicesMap[str(k, "spawn_player(choices)")] = str(v, "spawn_player(choices)")
      }
      val creation = world.creation
      if (creation != null) {
        val answersSoFar = LinkedHashMap<String, String>()
        for (c in creation.choices) {
          val chosen = choicesMap[c.id] ?: continue
          if (c.options.none { it.id == chosen }) {
            evalFail("spawn_player(choices): '$chosen' is not an option for choice '${c.id}'")
          }
          val avail =
            try {
              creation.available(world, c.id, answersSoFar)
            } catch (e: Exception) {
              evalFail("spawn_player(choices): creation provider failed: ${e.message}")
            }
          if (chosen !in avail) {
            evalFail(
              "spawn_player(choices): option '$chosen' is not available for choice '${c.id}'"
            )
          }
          answersSoFar[c.id] = chosen
        }
        val err =
          try {
            creation.validate(world, choicesMap)
          } catch (e: Exception) {
            evalFail("spawn_player(choices): creation provider failed: ${e.message}")
          }
        if (err != null) {
          evalFail("spawn_player(choices): $err")
        }
      } else if (choicesMap.isNotEmpty()) {
        evalFail("spawn_player(choices): world has no character creation provider")
      }
    }
    val p =
      guard("spawn_player") {
        world.spawnPlayer(
          str(name, "spawn_player(name)"),
          room.label,
          int(level, "spawn_player(level)"),
          StartingStats(overrideStats, overrideVitals, overrideVitals),
          int(index, "spawn_player(index)"),
          choices = choicesMap,
        )
      }
    // The harness has no idle clock; tests may run for hours of game time.
    p.idleExempt = true
    if (!isNone(skills)) {
      for ((k, v) in
        (skills as? Dict<*, *> ?: evalFail("spawn_player(skills): expected a dict")).entries) {
        val skill = label(str(k, "spawn_player(skills)"), pkgOf(thread), "spawn_player(skills)")
        if (skill !in world.protos.skills)
          evalFail("spawn_player(skills): there is no skill $skill")
        p.skills[skill] = int(v, "spawn_player(skills)")
      }
    }
    val sink = CapturingSink()
    val id = SessionId(nextSession++)
    world.handle(WorldControlEvent.Connect(id, sink))
    world.attach(id, p)
    world.creation?.created(world, p, newCharacter = true)
    return PlayerHandle(this, p, sink)
  }

  @StarlarkMethod(
    name = "spawn",
    doc = "Spawns a mob or item and returns its view.",
    parameters =
      [
        Param(name = "label", named = true, doc = "Mob or item prototype label."),
        Param(name = "at", named = true, doc = "Room label or view, actor, or container item."),
        Param(
          name = "index",
          named = true,
          defaultValue = "0",
          doc = "Track index when spawning into a room.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number to spawn; more than 1 returns a list.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun spawn(label: Any, at: Any, index: Any, count: Any, thread: StarlarkThread): Any {
    val l = label(str(label, "spawn(label)"), pkgOf(thread), "spawn(label)")
    val i = int(index, "spawn(index)")
    return guard("spawn") {
      when {
        l in world.protos.mobs -> view(this, world.spawnMob(l, roomArg(at, "spawn(at)", thread), i))
        l in world.protos.items -> {
          val loc =
            when (at) {
              is ActorView -> ItemLocation.Carried(at.actor.id)
              is ItemView -> ItemLocation.Contained(at.item.id)
              else -> ItemLocation.InRoom(roomArg(at, "spawn(at)", thread).id, i)
            }
          view(this, world.spawnItem(l, loc, int(count, "spawn(count)")))
        }
        else -> evalFail("spawn(label): there is no mob or item $l")
      }
    }
  }

  @StarlarkMethod(
    name = "run",
    doc = "Advances game time.",
    parameters =
      [Param(name = "pulses", named = true, defaultValue = "1", doc = "Pulses to advance.")],
  )
  fun run(pulses: Any): Any {
    val n = int(pulses, "run(pulses)")
    if (n < 0) evalFail("run(pulses): can't be negative")
    repeat(n) { world.runPulse() }
    return Starlark.NONE
  }

  @StarlarkMethod(
    name = "damage",
    doc =
      "Damages one vital of an actor by amount, or by pct of that vital's maximum. Fires on_damage; death is processed at once.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor to damage."),
        Param(name = "vital", named = true, doc = "The declared vital that loses the points."),
        Param(
          name = "amount",
          named = true,
          defaultValue = "None",
          doc = "Points of damage, or None to use pct.",
        ),
        Param(
          name = "pct",
          named = true,
          defaultValue = "None",
          doc = "Percentage of the vital's maximum, or None to use amount.",
        ),
        Param(
          name = "type",
          named = true,
          defaultValue = "None",
          doc = "A declared damage type, or None for the unarmed type.",
        ),
      ],
  )
  fun damage(target: Any, vital: Any, amount: Any, pct: Any, type: Any): Any {
    val a =
      (target as? ActorView)?.actor
        ?: evalFail("damage(target): expected an actor, got ${typeName(target)}")
    val schema = world.schema
    val name = vitalName(str(vital, "damage(vital)"), schema, "damage(vital)")
    val n =
      intOrNull(amount, "damage(amount)")
        ?: intOrNull(pct, "damage(pct)")?.let { a.max(name) * it / 100 }
        ?: evalFail("damage(): give amount or pct")
    val damageType =
      if (isNone(type)) schema.unarmedDamageType
      else damageTypeName(str(type, "damage(type)"), schema, "damage(type)")
    world.damage(a, mapOf(name to n), DamageType(damageType))
    return Starlark.NONE
  }

  /** The stored key for [key]: the test package's namespace unless it names one. */
  private fun attrKey(key: Any, thread: StarlarkThread): String {
    val k = str(key, "attribute key")
    return if (':' in k) k else "${pkgOf(thread).substringAfterLast('/')}:$k"
  }

  @StarlarkMethod(
    name = "set_attr",
    doc = "Sets an attribute directly. Unprefixed keys are in the test package's namespace.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(name = "key", named = true, doc = "Attribute key."),
        Param(name = "value", named = true, doc = "Value to store, or None to delete the key."),
      ],
    useStarlarkThread = true,
  )
  fun setAttr(entity: Any, key: Any, value: Any, thread: StarlarkThread): Any {
    val e = entityArg(entity, "set_attr(entity)")
    val k = attrKey(key, thread)
    val v = storableValue(value, "set_attr(value)")
    if (v == null) e.customAttributes.remove(k) else e.customAttributes[k] = v
    e.dirty = true
    return Starlark.NONE
  }

  @StarlarkMethod(
    name = "get_attr",
    doc = "Reads an attribute directly. Unprefixed keys are in the test package's namespace.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(name = "key", named = true, doc = "Attribute key."),
        Param(
          name = "default",
          named = true,
          defaultValue = "None",
          doc = "Value returned when the key is absent.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun getAttr(entity: Any, key: Any, default: Any, thread: StarlarkThread): Any =
    entityArg(entity, "get_attr(entity)")
      .customAttributes[attrKey(key, thread)]
      ?.let(::starlarkValue) ?: default

  @StarlarkMethod(
    name = "room",
    doc = "A view of a room.",
    parameters = [Param(name = "label", named = true, doc = "Room label.")],
    useStarlarkThread = true,
  )
  fun room(label: Any, thread: StarlarkThread): Any =
    RoomView(this, roomArg(label, "room(label)", thread))

  @StarlarkMethod(
    name = "trace",
    doc = "Records every hook and verb call; shown when the test fails.",
    parameters =
      [
        Param(
          name = "on",
          named = true,
          defaultValue = "True",
          doc = "True to start recording, False to stop.",
        )
      ],
  )
  fun trace(on: Any): Any {
    host.tracer = if (on == true) { e -> addTrace("[${e.pulse}] ${e.text}") } else null
    world.onRules = if (on == true) { _, line -> addTrace("[${world.pulse}] $line") } else null
    return Starlark.NONE
  }

  private fun addTrace(line: String) {
    traceLines.addLast(line)
    if (traceLines.size > TRACE_KEEP) {
      traceLines.removeFirst()
      traceDropped++
    }
  }

  internal fun traceText(): String? {
    if (traceLines.isEmpty()) return null
    val head = if (traceDropped > 0) "  ... $traceDropped earlier lines\n" else ""
    return head + traceLines.joinToString("\n") { "  $it" }
  }

  @StarlarkMethod(name = "diagnostics", doc = "Script and engine problems logged so far.")
  fun diagnostics(): Any = StarlarkList.immutableCopyOf(world.diagnostics.map(::DiagnosticView))

  @StarlarkMethod(name = "quarantined", doc = "Packages currently quarantined.")
  fun quarantined(): Any = StarlarkList.immutableCopyOf(host.quarantined.toList())

  @StarlarkMethod(name = "pulse", structField = true, doc = "Game time.")
  fun pulse(): Any = StarlarkInt.of(world.pulse)

  @StarlarkMethod(
    name = "set_choices",
    doc = "Changes or removes answers for a player, exactly as a plugin's setChoices does.",
    parameters =
      [
        Param(name = "player", named = true, doc = "The player to modify."),
        Param(
          name = "changes",
          named = true,
          doc = "Map of choice id to option id, with None removing an answer.",
        ),
      ],
  )
  fun setChoices(player: Any, changes: Any): Any {
    val p = playerArg(player, "set_choices(player)")
    val dict = changes as? Dict<*, *> ?: evalFail("set_choices(changes): expected a dict")
    val map = mutableMapOf<String, String?>()
    for ((k, v) in dict.entries) {
      val key = str(k, "set_choices(changes)")
      val value = if (isNone(v)) null else str(v, "set_choices(changes)")
      map[key] = value
    }
    val refusal = world.setChoices(p, map)
    return refusal ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "grant_trait",
    doc = "Grants a trait to an actor, returning the refusal message or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "The actor to grant the trait to."),
        Param(name = "name", named = true, doc = "The trait name."),
      ],
  )
  fun grantTrait(actor: Any, name: Any): Any {
    val a = actorArg(actor, "grant_trait(actor)")
    val t = str(name, "grant_trait(name)")
    val refusal = world.grantTrait(a, t)
    return refusal ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "remove_trait",
    doc = "Removes a granted trait from an actor, returning the refusal message or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "The actor to remove the trait from."),
        Param(name = "name", named = true, doc = "The trait name."),
      ],
  )
  fun removeTrait(actor: Any, name: Any): Any {
    val a = actorArg(actor, "remove_trait(actor)")
    val t = str(name, "remove_trait(name)")
    val refusal = world.removeTrait(a, t)
    return refusal ?: Starlark.NONE
  }

  private fun <T> guard(what: String, block: () -> T): T =
    try {
      block()
    } catch (e: IllegalArgumentException) {
      evalFail("$what(): ${e.message}")
    } catch (e: NoSuchElementException) {
      evalFail("$what(): ${e.message}")
    }
}

/** A connected player in a test world: an actor view plus `do` and `output`. */
@StarlarkBuiltin(
  name = "player",
  doc = "A test player: its view fields plus do(line) and output().",
)
class PlayerHandle
internal constructor(
  private val w: WorldHandle,
  val player: Player,
  private val sink: CapturingSink,
) : ActorView(w, player) {
  @StarlarkMethod(
    name = "do",
    doc =
      "Enters a command line, runs pulses until the player's queued actions and casts are done, and returns the output since the last do() or output().",
    parameters =
      [Param(name = "line", named = true, doc = "Command line, as the player would type it.")],
  )
  fun doLine(line: Any): Any {
    val world = w.world
    if (!player.inWorld) evalFail("do(): ${player.name} is no longer in the world")
    world.submit(player, str(line, "do(line)"))
    var pulses = 0
    while (player.inWorld && (player.actionQueue.isNotEmpty() || player.casting != null)) {
      if (pulses++ >= DO_MAX_PULSES)
        evalFail("do(\"$line\"): still busy after $DO_MAX_PULSES pulses")
      world.runPulse()
    }
    return output()
  }

  @StarlarkMethod(name = "output", doc = "Output received since the last do() or output().")
  fun output(): Any {
    w.world.flushOutput()
    return sink.drain()
  }
}

/** A read-only diagnostic for `w.diagnostics()`. */
@StarlarkBuiltin(name = "diagnostic", doc = "A read-only diagnostic entry from w.diagnostics().")
class DiagnosticView internal constructor(private val d: Diagnostic) : StarlarkValue {
  @StarlarkMethod(
    name = "kind",
    structField = true,
    doc = "Diagnostic category, such as 'script_error', 'step_limit' or 'quarantine'.",
  )
  fun kind(): Any = d.kind

  @StarlarkMethod(name = "message", structField = true, doc = "Human-readable diagnostic message.")
  fun message(): Any = d.message

  @StarlarkMethod(
    name = "package",
    structField = true,
    doc = "The package the diagnostic was charged to, or None.",
  )
  fun pkg(): Any = d.pkg ?: Starlark.NONE

  @StarlarkMethod(
    name = "label",
    structField = true,
    doc = "The prototype label involved, or None.",
  )
  fun label(): Any = d.label?.v ?: Starlark.NONE

  @StarlarkMethod(
    name = "hook",
    structField = true,
    doc = "The hook, command or timer name, or None.",
  )
  fun hook(): Any = d.hook ?: Starlark.NONE

  @StarlarkMethod(
    name = "file",
    structField = true,
    doc = "The Starlark file where the error occurred, or None.",
  )
  fun file(): Any = d.file ?: Starlark.NONE

  @StarlarkMethod(name = "line", structField = true, doc = "1-based source line number, or None.")
  fun line(): Any = d.line?.let { StarlarkInt.of(it) } ?: Starlark.NONE

  @StarlarkMethod(
    name = "pulse",
    structField = true,
    doc = "Game pulse when the diagnostic was recorded.",
  )
  fun pulse(): Any = StarlarkInt.of(d.pulse)

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<diagnostic ${d.kind}: ${d.message}>")
  }
}

/** The harness objects (`t`, `w`, `p`, `diagnostic`) for the script API model. */
object HarnessApi {
  /** Each harness class by the name tests know it by. */
  private val NAMED: Map<String, Class<*>> =
    mapOf(
      "t" to TestCtx::class.java,
      "w" to WorldHandle::class.java,
      "p" to PlayerHandle::class.java,
      "diagnostic" to DiagnosticView::class.java,
    )

  val BUILTIN_CLASSES: List<Class<*>> = NAMED.values.toList()

  fun specs(): List<TypeSpec> =
    NAMED.map { (name, cls) -> describeBuiltin(cls, name = name) }.sortedBy { it.name }
}

/** The label an entity view stands for when compared with a label string. */
internal fun labelOf(v: Any?): Label? =
  when (v) {
    is ActorView -> if (v.actor is Player) null else v.actor.protoLabel
    is EntityView -> v.entity.protoLabel
    else -> null
  }
