package gups.core.world

import gups.core.Label
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The `may_*` queries for drop, give, put, enter and learn: they ask, and change nothing. */
class RestrictionQueriesTest {
  private val host = RecordingHost()
  private val f = Fixture(scripts = host)
  private val p = f.player("Ann")
  private val asked = mutableListOf<RestrictionRequest>()

  init {
    for (hook in listOf(HookName.BEFORE_DROP, HookName.ON_DROP, HookName.ON_GIVE)) {
      host.handlers[hook] = { _, _, _ -> null }
    }
  }

  private fun carry(who: Player, label: Label = T.SWORD) =
    f.w.spawnItem(label, ItemLocation.Carried(who.id))

  private fun refuse(point: RestrictionPoint, message: String) {
    f.w.restrictions =
      f.w.restrictions +
        (point to
          listOf(
            Restriction { _, r ->
              asked += r
              message
            }
          ))
  }

  private fun assertQuietAt(item: gups.core.model.Item, where: ItemLocation) {
    assertEquals(where, item.location)
    assertEquals(emptyList(), host.calls, "no hook ran")
  }

  @Test
  fun `mayDrop allows a carried item and moves nothing`() {
    val sword = carry(p)
    assertNull(f.w.mayDrop(p, sword))
    assertQuietAt(sword, ItemLocation.Carried(p.id))
  }

  @Test
  fun `mayDrop asks the drop restriction`() {
    val sword = carry(p)
    refuse(RestrictionPoint.DROP, "It sticks to your hand.")
    assertEquals("It sticks to your hand.", f.w.mayDrop(p, sword))
    assertEquals(RestrictionPoint.DROP, asked.single().point)
    assertQuietAt(sword, ItemLocation.Carried(p.id))
  }

  @Test
  fun `mayDrop needs a carried item`() {
    val bob = f.player("Bob")
    assertFailsWith<IllegalArgumentException> { f.w.mayDrop(p, carry(bob)) }
    val worn = carry(p)
    f.w.wearItem(p, worn)
    assertFailsWith<IllegalArgumentException> { f.w.mayDrop(p, worn) }
  }

  @Test
  fun `mayGive allows a receiver in the room and moves nothing`() {
    val bob = f.player("Bob")
    val sword = carry(p)
    assertNull(f.w.mayGive(p, sword, bob))
    assertQuietAt(sword, ItemLocation.Carried(p.id))
    assertNull(f.w.mayGive(p, sword, f.mob()))
  }

  @Test
  fun `mayGive asks the give restriction with the receiver as target`() {
    val bob = f.player("Bob")
    refuse(RestrictionPoint.GIVE, "You can't part with it.")
    assertEquals("You can't part with it.", f.w.mayGive(p, carry(p), bob))
    assertEquals(bob, asked.single().target)
  }

  @Test
  fun `mayGive refuses a receiver who isn't here or is the giver, with the command's messages`() {
    val sword = carry(p)
    val away = f.player("Bob", at = T.YARD)
    assertEquals("They aren't here.", f.w.mayGive(p, sword, away))
    assertEquals("You already have it.", f.w.mayGive(p, sword, p))
    assertQuietAt(sword, ItemLocation.Carried(p.id))
  }

  @Test
  fun `mayGive needs a carried item`() {
    val bob = f.player("Bob")
    assertFailsWith<IllegalArgumentException> { f.w.mayGive(p, carry(bob), bob) }
  }

  @Test
  fun `mayPut allows an open container and moves nothing`() {
    val bag = carry(p, T.BAG)
    val sword = carry(p)
    assertNull(f.w.mayPut(p, sword, bag))
    assertQuietAt(sword, ItemLocation.Carried(p.id))
  }

  @Test
  fun `mayPut asks the put restriction with the container as target`() {
    val bag = carry(p, T.BAG)
    refuse(RestrictionPoint.PUT, "Not in there.")
    assertEquals("Not in there.", f.w.mayPut(p, carry(p), bag))
    assertEquals(bag, asked.single().target)
  }

  @Test
  fun `mayPut refuses a closed container and the item itself`() {
    val bag = carry(p, T.BAG)
    assertEquals("You can't put something inside itself.", f.w.mayPut(p, bag, bag))
    bag.container!!.state = DoorState.CLOSED
    assertEquals("A bag is closed.", f.w.mayPut(p, carry(p), bag))
  }

  @Test
  fun `mayPut needs a carried item and a container`() {
    val bag = carry(p, T.BAG)
    val sword = carry(p)
    assertFailsWith<IllegalArgumentException> { f.w.mayPut(p, sword, carry(p)) }
    assertFailsWith<IllegalArgumentException> { f.w.mayPut(p, carry(f.player("Bob")), bag) }
  }

  @Test
  fun `mayEnter asks only the enter restriction with no direction, from the actor's room`() {
    val yard = f.w.room(T.YARD)
    assertNull(f.w.mayEnter(p, yard))
    refuse(RestrictionPoint.ENTER, "Staff only.")
    assertEquals("Staff only.", f.w.mayEnter(p, yard))
    val request = asked.single()
    assertEquals(yard, request.room)
    assertEquals(f.w.room(T.HALL), request.from)
    assertNull(request.direction)
    assertEquals(f.w.room(T.HALL), p.room, "the actor didn't move")
    assertEquals(emptyList(), host.calls, "no hook ran")
  }

  @Test
  fun `mayLearn answers as setSkill would and sets nothing`() {
    f.w.restrictions =
      f.w.restrictions +
        (RestrictionPoint.LEARN to
          listOf(Restriction { _, r -> if ((r.proficiency ?: 0) > 10) "Too advanced." else null }))
    assertEquals("Too advanced.", f.w.mayLearn(p, T.BOLT, 50))
    assertNull(f.w.mayLearn(p, T.BOLT, 10))
    assertNull(p.skills[T.BOLT])
    assertEquals(f.w.mayLearn(p, T.BOLT, 50), f.w.setSkill(p, T.BOLT, 50))
  }

  @Test
  fun `mayLearn rejects an unknown skill and a proficiency out of range`() {
    assertFailsWith<IllegalArgumentException> { f.w.mayLearn(p, Label("//t:nope"), 5) }
    assertFailsWith<IllegalArgumentException> { f.w.mayLearn(p, T.BOLT, 101) }
  }
}
