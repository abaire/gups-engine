package gups.core.world

import gups.core.Label
import gups.core.model.Channel
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.MobProto
import gups.core.model.Sense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerceptionTest {
  private val ear = Label("//t:ear")
  private val eye = Label("//t:eye")
  private val deaf = Label("//t:deaf")

  private class Heard(
    val mob: String,
    val event: String,
    val via: String?,
    val from: String,
    val text: String?,
  ) {
    override fun toString() = "$mob:$event:${via ?: "-"}:$from" + (text?.let { ":$it" } ?: "")
  }

  private fun fixture(bubble: Boolean = false): Pair<Fixture, MutableList<Heard>> {
    val heard = mutableListOf<Heard>()
    val host = RecordingHost()
    host.handlers[HookName.ON_NEARBY] = { _, owner, ctx ->
      heard +=
        Heard(owner.name, ctx.event!!, ctx.viaExit?.name, ctx.sourceRoom!!.label.name, ctx.text)
      null
    }
    val mobs =
      listOf(
        MobProto(ear, "ear", listOf("ear"), senses = mapOf(Sense.HEARING to 1)),
        MobProto(eye, "eye", listOf("eye"), senses = mapOf(Sense.SIGHT to 1)),
        MobProto(deaf, "deaf", listOf("deaf")),
      )
    return Fixture(scripts = host, protos = T.protos(extraMobs = mobs), bubble = bubble) to heard
  }

  @Test
  fun `sounds carry through an open exit, and are delivered in phase 5`() {
    val (f, heard) = fixture()
    val p = f.player("Ann")
    f.mob(ear, at = T.YARD)
    f.mob(deaf, at = T.YARD)
    f.input(p, "say hello")
    assertTrue(heard.isEmpty(), "deferred until phase 5")
    f.run()
    assertEquals("[ear:say:south:hall:hello]", heard.toString())
  }

  @Test
  fun `a closed door stops sight and sound but not noise, and ALL stops everything`() {
    val (f, heard) = fixture()
    val p = f.player("Ann")
    f.mob(ear, at = T.VAULT)
    f.input(p, "say hello")
    f.input(p, "shout HELLO")
    f.run()
    assertEquals("[ear:shout:north:hall:HELLO]", heard.toString())
    heard.clear()
    f.w.setDoor(f.w.room(T.HALL), Direction.SOUTH, DoorState.OPEN)
    f.input(p, "say again")
    f.run()
    assertEquals("[ear:say:north:hall:again]", heard.toString())
    heard.clear()
    f.w.setExitBlocks(f.w.room(T.VAULT), Direction.NORTH, Channel.ALL)
    f.input(p, "shout HELLO")
    f.run()
    assertEquals("[]", heard.toString())
  }

  @Test
  fun `sight carries movement, hearing doesn't, and the mob's own room needs no sense`() {
    val (f, heard) = fixture()
    val p = f.player("Ann", at = T.YARD)
    f.mob(ear)
    f.mob(eye)
    f.mob(deaf, at = T.YARD)
    f.input(p, "south")
    f.run()
    // Leaving the yard is seen by eye (via its north exit); arriving in the hall is seen by
    // everyone there; deaf in the yard sees Ann leave its own room.
    assertEquals(
      setOf("deaf:exit:-:yard", "eye:exit:north:yard", "ear:enter:-:hall", "eye:enter:-:hall"),
      heard.map { it.toString() }.toSet(),
    )
  }

  @Test
  fun `events of one type from one room reach a mob once a pulse, and whispers never`() {
    val (f, heard) = fixture()
    val p = f.player("Ann")
    f.player("Bob")
    f.mob(ear, at = T.YARD)
    f.input(p, "say one")
    f.input(p, "say two")
    f.input(p, "whisper bob psst")
    f.input(p, "tell bob hi")
    f.run()
    assertEquals("[ear:say:south:hall:one]", heard.toString())
    f.input(p, "say three")
    f.run()
    assertEquals("ear:say:south:hall:three", heard.last().toString())
  }

  @Test
  fun `combat makes noise when it starts, every round, and at a death`() {
    val (f, heard) = fixture()
    val p = f.player("Ann")
    val g = f.mob(T.GOBLIN)
    f.mob(ear, at = T.YARD)
    f.input(p, "kill goblin")
    f.runTo(8)
    // The goblin hears its own fight too: on_nearby covers the mob's own room.
    assertEquals(
      "[a goblin:combat_start:-:hall, ear:combat_start:south:hall, a goblin:combat:-:hall, ear:combat:south:hall]",
      heard.toString(),
    )
    f.w.damage(g, 100, gups.core.model.DamageType.SLASH, p)
    f.run()
    assertEquals("ear:death:south:hall", heard.last().toString())
  }

  @Test
  fun `dormant mobs perceive nothing`() {
    val (f, heard) = fixture(bubble = true)
    val p = f.player("Ann")
    f.mob(ear, at = T.YARD)
    f.input(p, "say early")
    f.run() // the yard wakes in phase 6, after this pulse's events were delivered
    f.input(p, "say late")
    f.run()
    assertEquals("[ear:say:south:hall:late]", heard.toString())
  }
}
