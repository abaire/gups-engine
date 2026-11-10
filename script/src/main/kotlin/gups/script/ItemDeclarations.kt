package gups.script

import gups.core.Label
import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.Effect
import gups.core.model.EmptyAction
import gups.core.model.ItemProto
import gups.core.model.TAG_REGEX
import gups.core.model.TargetKind
import gups.core.model.WorldSchema
import gups.core.world.Features
import net.starlark.java.eval.StarlarkThread

/** Turns the validated arguments of an `item(...)` call into an [ItemProto]. */
internal class ItemDeclParser(
  private val d: ParsedDecl,
  private val ctx: LoadCtx,
  private val thread: StarlarkThread,
  private val schema: WorldSchema,
  private val features: Features,
) {
  private val pkg = ctx.pkg.path

  private class Charges(val charges: Int?, val stackable: Boolean, val empty: EmptyAction)

  private class Use(val effect: Effect?, val target: TargetKind, val lag: Int)

  fun parse(): ItemProto {
    val id = declName("item", d, ctx)
    val label = Label("//$pkg:$id")
    val name = d.str("name") ?: displayName(id)
    val plural = d.str("plural")
    if (plural != null && plural.isBlank()) fail("item(plural): can't be blank")
    val wear = wearSlot(label)
    val slotCost = slotCost(wear)
    val container = container()
    val affects = affects()
    val variants = variants()
    val charges = charges(variants)
    val use = use()
    val procs = procList(d["procs"], "item(procs)")
    val def = ItemProto(label, name, emptyList())
    val proto =
      ItemProto(
        label = label,
        name = name,
        keywords = d["keywords"]?.let { d.strList("keywords") } ?: defaultKeywords(name, id),
        desc = d.str("desc") ?: "",
        kind = d.str("kind")?.lowercase() ?: def.kind,
        range = d["range"]?.let { band(it, d.what("range")) } ?: def.range,
        weight = d.int("weight") ?: def.weight,
        value = d.int("value") ?: def.value,
        wear = wear,
        slotCost = slotCost,
        dice = d["dice"]?.let { dice(it, d.what("dice")) },
        damageType = damageType(),
        statDeltas = typed(d["stat_deltas"], TypedKind.STATS, d.what("stat_deltas")),
        senseDeltas = senses(d["sense_deltas"], d.what("sense_deltas"), schema, true),
        maxVitalDeltas = typed(d["max_vital_deltas"], TypedKind.VITALS, d.what("max_vital_deltas")),
        resistDeltas = resistDeltas(),
        tags = parseTags(d),
        stackable = charges.stackable,
        decayPulses = d.int("decay_pulses"),
        container = container,
        commands = commands(d["commands"], d.what("commands"), pkg),
        equipAffects = equipAffects(d["equip_affects"], d.what("equip_affects")),
        affects = affects,
        variants = variants,
        modifiers = modifiers(d["modifiers"], d.what("modifiers"), features),
        procs = procs,
        version = version(d),
        plural = plural,
        charges = charges.charges,
        empty = charges.empty,
        useEffect = use.effect,
        useTarget = use.target,
        useLag = use.lag,
      )
    validate(proto)
    return proto
  }

  private fun damageType(): DamageType? =
    d.str("damage_type")?.let {
      DamageType(
        checkName(it.lowercase(), schema.damageTypes, "damage type", d.what("damage_type"))
      )
    }

  private fun resistDeltas(): Map<DamageType, Int> =
    typed(d["resist_deltas"], TypedKind.RESISTS, d.what("resist_deltas")).mapKeys { (k, _) ->
      DamageType(k)
    }

  private fun wearSlot(label: Label): String? {
    val wear = d.str("wear")
    val wield = d.str("wield")
    if (wear != null && wield != null) fail("item(): give either wear or wield, not both")
    if (wear != null) ctx.wearRef(label, "wear", wear, thread)
    if (wield != null) ctx.wearRef(label, "wield", wield, thread)
    return wear ?: wield
  }

  private fun slotCost(slot: String?): Int {
    val cost = d.int("slot_cost") ?: 1
    if (cost < 1) fail("item(slot_cost): must be at least 1, got $cost")
    if (cost != 1 && slot == null) {
      fail(
        "item(slot_cost): slot_cost = $cost needs wear or wield; an item that isn't worn takes up no slots"
      )
    }
    return cost
  }

  private fun container() =
    d["container"]
      ?.let {
        (it as? ContainerDecl)?.proto
          ?: fail("item(container): expected container(...), got ${typeName(it)}")
      }
      ?.also { proto -> proto.key?.let { ctx.ref(it, RefKind.ITEM, thread) } }

  private fun affects() =
    d.list("affects")
      .map {
        (it as? ItemAffectDecl)?.affect
          ?: fail("item(affects): expected item_affect(...), got ${typeName(it)}")
      }
      .also { affects ->
        val seen = mutableSetOf<String>()
        for (affect in affects) {
          if (!seen.add(affect.name)) fail("item(affects): '${affect.name}' appears twice")
        }
      }

  private fun variants(): Map<String, Label> {
    val out = LinkedHashMap<String, Label>()
    for ((k, v) in d.dict("variants")) {
      if (!TAG_REGEX.matches(k)) fail("item(variants): '$k' is not a valid variant name")
      val target = label(str(v, d.what("variants")), pkg, d.what("variants"))
      ctx.ref(target, RefKind.ITEM, thread)
      out[k] = target
    }
    return out
  }

  private fun charges(variants: Map<String, Label>): Charges {
    val charges = d.int("charges")
    val empty = d.str("empty")
    checkCharges(charges, empty)
    val stackable = d.bool("stackable") ?: false
    if (stackable) checkStackable(charges, empty)
    val action = emptyAction(empty)
    if (action is EmptyAction.Variant && action.name !in variants) {
      fail("item(empty): '$empty' is not 'destroy', 'keep' or one of this item's variants")
    }
    return Charges(charges, stackable, action)
  }

  private fun checkCharges(charges: Int?, empty: String?) {
    if (charges != null && charges < 1) fail("item(charges): must be at least 1")
    if (replacesItem(empty) && charges == null) fail("item(empty): needs charges")
  }

  private fun replacesItem(empty: String?) = empty != null && empty != "destroy"

  private fun checkStackable(charges: Int?, empty: String?) {
    if (charges != null && charges != 1) {
      fail("item(charges): a stackable item can only have 1 charge")
    }
    if (empty == "keep") fail("item(empty): a stackable item can't keep an empty item")
  }

  private fun emptyAction(empty: String?): EmptyAction =
    when (empty) {
      null,
      "destroy" -> EmptyAction.Destroy
      "keep" -> EmptyAction.Keep
      else -> EmptyAction.Variant(empty)
    }

  private fun use(): Use {
    val effect = d["use_effect"]?.let { effectValue(it, d.what("use_effect")) }
    effect?.let { EffectChecks.refs(it, ctx, thread) }
    val target = d.choice("use_target")?.let { TargetKind.valueOf(it.uppercase()) }
    if (target == TargetKind.ROOM) fail("item(use_target): 'room' is not a valid use target")
    val lag = d.int("use_lag") ?: 0
    if (needsUseEffect(target) && effect == null) fail("item(use_target): needs use_effect")
    if (lag < 0) fail("item(use_lag): can't be negative")
    val useTarget = target ?: TargetKind.SELF
    effect?.let { checkUseEffect(it, useTarget) }
    return Use(effect, useTarget, lag)
  }

  private fun needsUseEffect(target: TargetKind?) = target != null && target != TargetKind.SELF

  private fun checkUseEffect(effect: Effect, target: TargetKind) =
    EffectChecks.checkTarget(
      effect,
      target,
      EffectSite(
        "item(use_effect)",
        "an item use_effect",
        "for use_target = 'item'",
        "with use_target = 'item'",
      ),
    )

  private fun validate(proto: ItemProto) {
    if (proto.weight < 0) fail("item(weight): can't be negative")
    if (proto.value < 0) fail("item(value): must be at least 0")
    if (proto.keywords.isEmpty()) fail("item(keywords): an item needs at least one keyword")
    if (proto.wear == null) checkWornEffects(proto)
  }

  private fun checkWornEffects(proto: ItemProto) {
    val worn =
      listOf(
        proto.equipAffects.isNotEmpty(),
        proto.modifiers.isNotEmpty(),
        proto.maxVitalDeltas.isNotEmpty(),
        proto.resistDeltas.isNotEmpty(),
        proto.procs.isNotEmpty(),
      )
    if (worn.any { it }) {
      fail(
        "item(): equip_affects, modifiers, max_vital_deltas, resist_deltas and procs apply while the item is worn, so it needs a wear slot"
      )
    }
    val wornAffects = proto.affects.filter { it.applies == AffectScope.WORN }
    if (wornAffects.any { it.modifiers.isNotEmpty() }) {
      fail("item(): a worn item_affect with modifiers needs a wear slot")
    }
    if (wornAffects.any { it.procs.isNotEmpty() }) {
      fail("item(): a worn item_affect with procs needs a wear slot")
    }
  }
}
