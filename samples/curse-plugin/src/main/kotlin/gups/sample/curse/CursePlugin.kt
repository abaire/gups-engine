package gups.sample.curse

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRestrictionPoint

/**
 * Sample plugin implementing curses as content: clinging items, warded containers, lift and bestow.
 */
class CursePlugin : GupsPlugin {
  override val id = "curse"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures: Set<String> = emptySet()
  override val doc = "Curses as content: clinging items, warded containers, lift and bestow."

  override fun register(r: PluginRegistry) {
    // ---- Restrictions -----------------------------------------------------------------------

    r.addRestriction(
      PluginRestrictionPoint.REMOVE,
      "Refuses removing a worn item tagged cursed.",
    ) { call ->
      // Taking a worn item off only moves it to the inventory, so it never leaves the wearer's
      // custody, and carried curses inside it keep applying; refusing a worn backpack that merely
      // holds a cursed amulet would gain nothing. What REMOVE protects is the cursed item's own
      // worn affects, which would end the moment it came off: the classic 'the cursed item clings
      // to you'. The engine runs this check for wear/wield swaps too, so wielding a new weapon
      // can't swap out a cursed one.
      if (call.item?.hasTag("cursed") == true) "It won't budge. It must be cursed!" else null
    }

    r.addRestriction(PluginRestrictionPoint.DROP, "Refuses dropping an item bearing a curse.") {
      call ->
      if (call.item?.let(::bearsCurse) == true) "It won't budge. It must be cursed!" else null
    }

    r.addRestriction(PluginRestrictionPoint.GIVE, "Refuses giving an item bearing a curse.") { call
      ->
      if (call.item?.let(::bearsCurse) == true) "It won't budge. It must be cursed!" else null
    }

    r.addRestriction(
      PluginRestrictionPoint.PUT,
      "Refuses putting an item bearing a curse into a container outside the actor's custody.",
    ) { call ->
      val dest = call.target as? PluginItem
      if (call.item?.let(::bearsCurse) == true && dest?.custodian?.id != call.actor.id) {
        "It won't budge. It must be cursed!"
      } else {
        null
      }
    }

    // ---- Effect Primitives ------------------------------------------------------------------

    r.addEffectPrimitive(
      name = "bestow",
      doc = "Bestows a curse on an item by tagging it cursed.",
      params = emptyList(),
    ) { call ->
      val item = call.targetItem ?: return@addEffectPrimitive
      call.world.addTag(item, "cursed")
    }

    r.addEffectPrimitive(
      name = "lift",
      doc =
        "Lifts a curse from an item: transforms to its uncursed variant, or removes the cursed tag.",
      params = emptyList(),
    ) { call ->
      val item = call.targetItem ?: return@addEffectPrimitive
      val clean = item.variants["uncursed"]
      if (clean != null) {
        call.world.transform(item, clean)
      } else {
        call.world.removeTag(item, "cursed")
      }
    }

    // ---- Containment Filters ----------------------------------------------------------------

    r.addContainmentFilter("Blocks carried affects behind warded containers.") { call ->
      call.container.hasTag("warded") && call.item.hasTag("cursed")
    }
  }
}

/**
 * True if [item] carries a curse to whoever holds it: it is cursed, or holds one not shut away by a
 * ward.
 *
 * A cursed item is always cursed, even when tagged warded itself. A warded container that isn't
 * cursed never bears a curse, whatever it holds, because the ward stops propagation.
 */
internal fun bearsCurse(item: PluginItem): Boolean =
  item.hasTag("cursed") || (!item.hasTag("warded") && item.contents.any(::bearsCurse))
