package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Room

/**
 * The `fits` query for an existing [item]: how many of [count] (by default the whole stack) would
 * fit at [to], worked out as the commands do. Changes nothing and runs no hook; whether a container
 * is open or locked isn't considered.
 */
fun World.fits(item: Item, to: Entity, count: Int = item.count): Int {
  require(count in 1..item.count) { "fits: count must be between 1 and ${item.count}, got $count" }
  return fitsAt(Fitting(item), to, count)
}

/**
 * The `fits` query for a prototype [label] no item exists for yet: how many of [count] would fit at
 * [to]. Nothing is spawned; the prototype supplies the weight and whether it stacks.
 */
fun World.fits(label: Label, to: Entity, count: Int = 1): Int {
  val proto = requireNotNull(protos.items[label]) { "fits: there is no item $label" }
  require(count >= 1) { "fits: count must be at least 1, got $count" }
  return fitsAt(Fitting(proto, proto.weight), to, count)
}

private fun World.fitsAt(what: Fitting, to: Entity, count: Int): Int =
  when (to) {
    is Item -> {
      require(to.container != null) { "fits: ${to.name} is not a container" }
      BoxRoom(this, to, what).fit(count)
    }
    is Actor -> minOf(count, carryFit(to, what, count))
    is Room -> count
  }
