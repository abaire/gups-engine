package gups.script

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import gups.core.model.ContainerProto
import gups.core.model.DoorProto
import gups.core.model.Effect
import gups.core.model.ExitProto
import gups.core.model.ItemAffect
import gups.core.model.LootRule
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.Prototypes
import gups.core.model.SpawnRule
import gups.core.model.StandingDelta
import gups.core.model.StandingHolder
import gups.core.model.StandingSubject
import gups.core.model.didYouMean
import gups.core.world.Behavior
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.eval.Structure

// ---- Small declaration values -------------------------------------------------------------------

/** Base for values such as `exit(...)` that only make sense inside another declaration. */
internal abstract class DeclValue(private val kind: String) : StarlarkValue {
  override fun repr(printer: Printer) {
    printer.append("<$kind>")
  }

  override fun isImmutable(): Boolean = true
}

internal class ExitDecl(val proto: ExitProto) : DeclValue("exit")

internal class DoorDecl(val proto: DoorProto) : DeclValue("door")

internal class SpawnDecl(val rule: SpawnRule) : DeclValue("spawn")

internal class LootDecl(val rule: LootRule) : DeclValue("loot")

internal class ProcDecl(val proc: Proc) : DeclValue("proc")

internal class OutcomeDecl(val outcome: ProcOutcome) : DeclValue("outcome")

internal class ContainerDecl(val proto: ContainerProto) : DeclValue("container")

internal class ItemAffectDecl(val affect: ItemAffect) : DeclValue("item_affect")

internal class EffectDecl(val effect: Effect) : DeclValue("effect")

/** An effect value for a plugin effect primitive, usable wherever an engine effect is. */
fun pluginEffect(pluginId: String, name: String, args: Map<String, Any?>): StarlarkValue =
  EffectDecl(Effect.Plugin(pluginId, name, args))

internal class BehaviorDecl(val behavior: Behavior) : DeclValue("behavior")

/** What `struct(**fields)` returns: read-only named fields. */
internal class StructValue(val fields: Map<String, Any>) : Structure {
  override fun getValue(name: String): Any? = fields[name]

  override fun getFieldNames(): ImmutableCollection<String> = ImmutableList.copyOf(fields.keys)

  override fun getErrorMessageForUnknownField(field: String): String =
    "struct has no field '$field'${didYouMean(field, fields.keys)}"

  override fun isImmutable(): Boolean = fields.values.all { Starlark.isImmutable(it) }

  override fun repr(printer: Printer) {
    printer.append("struct(")
    fields.entries.forEachIndexed { i, (k, v) ->
      if (i > 0) printer.append(", ")
      printer.append(k).append(" = ").repr(v)
    }
    printer.append(")")
  }
}

/** Marks a rules call that may build behaviors, in the rules package [pkg]. */
internal class OwnedBehaviorsMarker(val pkg: String)

/** Marks a rules call that may build standing deltas, in the rules package [pkg]. */
internal class StandingChangeMarker(val pkg: String, val protos: Prototypes)

@StarlarkBuiltin(name = "standing_delta", doc = "A standing change delta.")
class StandingDeltaValue(val delta: StandingDelta) : StarlarkValue {
  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append(toString())
  }

  override fun toString(): String {
    val holder =
      when (val h = delta.holder) {
        is StandingHolder.Personal -> "#${h.mob.id.v}"
        is StandingHolder.Faction -> "'${h.label.v}'"
      }
    val subject =
      when (val s = delta.subject) {
        is StandingSubject.Of -> "#${s.actor.v}"
        is StandingSubject.Faction -> "'${s.label.v}'"
      }
    return "standing_delta($holder, $subject, ${delta.amount})"
  }
}
