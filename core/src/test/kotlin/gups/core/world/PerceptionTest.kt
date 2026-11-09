package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Channel
import gups.core.model.ContainerProto
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.Trait
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.rules.StartingStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerceptionTest {
  private val ear = Label("//t:ear")
  private val eye = Label("//t:eye")
  private val deaf = Label("//t:deaf")
  private val blindDeaf = Label("//t:blind_deaf")
  private val chest = Label("//t:chest")
  private val lens =
    ItemProto(
      Label("//t:lens"),
      "a lens",
      listOf("lens"),
      wear = "wield",
      senseDeltas = mapOf(Channel("spell") to 1),
    )

  private val spell = Channel("spell")

  private class Heard(
    val mob: String,
    val event: String,
    val via: String?,
    val from: String,
    val text: String?,
    val channel: String? = null,
    val intensity: Int? = null,
    val cost: Int? = null,
  ) {
    override fun toString() = "$mob:$event:${via ?: "-"}:$from" + (text?.let { ":$it" } ?: "")
  }

  private val none = mapOf(Channel.SIGHT to 0, Channel.SOUND to 0)

  private fun FixtureSchema.ranked(order: List<String>) =
    FixtureSchema(
      name,
      schema.copy(channelPriority = order),
      health,
      energy,
      stamina,
      agility,
      strength,
      toughness,
      mind,
      armor,
      speed,
      magic,
      playerMax,
      mobMax,
      startsEmpty,
    )

  /** Rules that tell a player of a hit on one world channel and of nothing else on it. */
  private class PainRules(private val base: RulesEngine, private val channel: Channel) :
    RulesEngine by base {
    override fun perceiveMessage(c: RulesContext): String? =
      if (c.channel == channel && c.event == "combat") "You feel a stab of pain." else null
  }

  private fun Fixture.listener(name: String, senses: Map<Channel, Int>): Player =
    w.spawnPlayer(name, T.HALL, overrides = StartingStats(senses = senses)).also { connect(it) }

  private fun fixture(
    bubble: Boolean = false,
    sealedVault: Boolean = false,
    senseCost: ((RulesContext) -> Int)? = null,
    rooms: List<RoomProto> = T.rooms,
    order: List<String> = emptyList(),
    wrap: ((RulesEngine) -> RulesEngine)? = null,
  ): Pair<Fixture, MutableList<Heard>> {
    val heard = mutableListOf<Heard>()
    val host = RecordingHost()
    host.handlers[HookName.ON_PERCEIVE] = { _, owner, ctx ->
      heard +=
        Heard(
          owner.name,
          ctx.event!!,
          ctx.viaExit?.name,
          ctx.sourceRoom!!.label.name,
          ctx.text,
          ctx.channel,
          ctx.intensity,
          ctx.cost,
        )
      null
    }
    val mobs =
      listOf(
        MobProto(ear, "ear", listOf("ear"), senses = none + (Channel.SOUND to 1)),
        MobProto(eye, "eye", listOf("eye"), senses = none + (Channel.SIGHT to 1)),
        MobProto(deaf, "deaf", listOf("deaf"), senses = none + (Channel.SIGHT to 1)),
        MobProto(blindDeaf, "blind_deaf", listOf("blind_deaf"), senses = none),
      )
    val protos =
      Prototypes(
        if (!sealedVault) rooms
        else
          rooms.map { r ->
            if (r.label != T.VAULT) r
            else r.copy(exits = r.exits.map { it.copy(tags = setOf("sealed")) })
          },
        T.mobs + mobs,
        T.items +
          ItemProto(
            chest,
            "a chest",
            listOf("chest"),
            container = ContainerProto(state = DoorState.CLOSED),
            tags = setOf("heavy"),
          ) +
          lens,
        T.skills,
      )
    val fs = FixtureSchemas.current.let { if (order.isEmpty()) it else it.ranked(order) }
    val base = FixedRules(fs).also { it.senseCostOf = senseCost }
    val rules = wrap?.invoke(base) ?: base
    return Fixture(fs = fs, rules = rules, scripts = host, protos = protos, bubble = bubble) to
      heard
  }

  private fun Fixture.perceive(
    event: String,
    channel: Channel,
    at: Label,
    intensity: Int,
    text: String? = null,
  ) = w.perceive(event, channel, w.room(at), null, text, intensity)

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
  fun `a closed door muffles a quiet sound but not a loud one`() {
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
  }

  @Test
  fun `a sealed exit carries nothing, even through an open door`() {
    val (f, heard) = fixture(sealedVault = true)
    val p = f.player("Ann")
    f.mob(ear, at = T.VAULT)
    f.w.setDoor(f.w.room(T.HALL), Direction.SOUTH, DoorState.OPEN)
    f.input(p, "shout HELLO")
    f.run()
    assertEquals("[]", heard.toString())
  }

  @Test
  fun `a free hop costs nothing and still carries the event`() {
    val (f, heard) = fixture(senseCost = { 0 })
    f.mob(ear, at = T.YARD)
    f.perceive("ping", Channel.SOUND, T.HALL, intensity = 1)
    f.run()
    assertEquals(listOf(0), heard.map { it.cost })
  }

  @Test
  fun `sight carries movement, sound doesn't, and a sense of 0 hears nothing in the same room`() {
    val (f, heard) = fixture()
    val p = f.player("Ann", at = T.YARD)
    f.mob(ear)
    f.mob(eye)
    f.mob(deaf, at = T.YARD)
    f.input(p, "south")
    f.run()
    // Leaving the yard is seen by eye (via its north exit); arriving in the hall is seen by eye
    // there and by deaf from the yard next door; deaf also sees Ann leave its own room. Ear
    // can't see, so it notices neither.
    assertEquals(
      setOf("deaf:exit:-:yard", "eye:exit:north:yard", "eye:enter:-:hall", "deaf:enter:south:hall"),
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
  fun `combat makes sound when it starts, every round, and at a death`() {
    val (f, heard) = fixture()
    val p = f.player("Ann")
    val g = f.mob(T.GOBLIN)
    f.mob(ear, at = T.YARD)
    f.input(p, "kill goblin")
    f.runTo(8)
    // The goblin hears its own fight too: on_perceive covers the mob's own room.
    assertEquals(
      "[a goblin:combat_start:-:hall, ear:combat_start:south:hall, a goblin:combat:-:hall, ear:combat:south:hall]",
      heard.toString(),
    )
    f.hurt(g, 100, p)
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

  @Test
  fun `an event spreads over several rooms until intensity plus sense no longer beats the cost`() {
    val (f, heard) = fixture()
    f.mob(ear, at = T.HALL)
    f.mob(ear, at = T.CLOSET)
    // From the yard the hall costs 2, since large rooms muffle sound, and the closet 3 in all.
    f.perceive("ping", Channel.SOUND, T.YARD, intensity = 2)
    f.run()
    assertEquals(listOf(2), heard.map { it.cost })
    heard.clear()
    f.perceive("ping", Channel.SOUND, T.YARD, intensity = 3)
    f.run()
    assertEquals(listOf(2, 3), heard.map { it.cost })
  }

  @Test
  fun `a cheaper path that is too long does not hide a dearer one within range`() {
    val s = Label("//t:s")
    val p = Label("//t:p")
    val q = Label("//t:q")
    val x = Label("//t:x")
    val y = Label("//t:y")
    val rooms =
      listOf(
        RoomProto(
          s,
          "S",
          exits = listOf(ExitProto(Direction.EAST, x), ExitProto(Direction.NORTH, p)),
        ),
        RoomProto(
          p,
          "P",
          exits = listOf(ExitProto(Direction.SOUTH, s), ExitProto(Direction.NORTH, q)),
        ),
        RoomProto(
          q,
          "Q",
          exits = listOf(ExitProto(Direction.SOUTH, p), ExitProto(Direction.NORTH, x)),
        ),
        RoomProto(
          x,
          "X",
          exits =
            listOf(
              ExitProto(Direction.WEST, s),
              ExitProto(Direction.SOUTH, q),
              ExitProto(Direction.EAST, y),
            ),
        ),
        RoomProto(y, "Y", exits = listOf(ExitProto(Direction.WEST, x))),
      )
    val (f, heard) =
      fixture(
        rooms = rooms,
        senseCost = {
          val dear = it.room!!.label == x && it.exit!!.direction == Direction.WEST
          val last = it.room.label == y
          if (dear) 5 else if (last) 1 else 0
        },
      )
    f.mob(ear, at = y)
    // X is free by the long way round (3 exits) and costs 5 directly; only the direct way leaves
    // the exits to carry the event on to Y.
    f.perceive("ping", Channel.SOUND, s, intensity = 10)
    f.run()
    assertEquals(listOf(6), heard.map { it.cost })
  }

  @Test
  fun `a closed door muffles an event that an open one lets through`() {
    val (f, heard) = fixture()
    f.mob(ear, at = T.VAULT)
    f.perceive("thud", Channel.SOUND, T.HALL, intensity = 1)
    f.run()
    assertEquals(emptyList(), heard.toList().map { it.toString() }, "1 + 1 does not beat 2")
    f.w.setDoor(f.w.room(T.HALL), Direction.SOUTH, DoorState.OPEN)
    f.perceive("thud", Channel.SOUND, T.HALL, intensity = 1)
    f.run()
    assertEquals("[ear:thud:north:hall]", heard.toString())
  }

  @Test
  fun `a distant player hears a shout once, from perceive_message, and the room hears it directly`() {
    val (f, _) = fixture()
    val ann = f.player("Ann")
    val bob = f.player("Bob", at = T.YARD)
    val cat = f.player("Cat")
    f.input(ann, "shout HELLO")
    f.run(2)
    val far = f.out(bob)
    val near = f.out(cat)
    assertEquals(1, "Someone shouts from the south, 'HELLO'".toRegex().findAll(far).count(), far)
    assertTrue("shouts" in near, near)
    assertFalse("from the" in near, near)
  }

  @Test
  fun `a player who cannot hear is not told what is said in the room, nor one who cannot see an emote`() {
    val (f, _) = fixture()
    val ann = f.player("Ann")
    val deafPlayer = f.listener("Dee", mapOf(Channel.SOUND to 0))
    val blindPlayer = f.listener("Bli", mapOf(Channel.SIGHT to 0))
    f.out(ann)
    f.out(deafPlayer)
    f.out(blindPlayer)
    f.input(ann, "say hello")
    f.w.emote(ann, "waves")
    f.run()
    val deafOut = f.out(deafPlayer)
    val blindOut = f.out(blindPlayer)
    assertFalse("hello" in deafOut, deafOut)
    assertTrue("waves" in deafOut, deafOut)
    assertTrue("hello" in blindOut, blindOut)
    assertFalse("waves" in blindOut, blindOut)
  }

  @Test
  fun `a player who perceives no channel is told nothing`() {
    val (f, _) = fixture()
    val ann = f.player("Ann")
    val numb = f.listener("Num", none)
    f.w.setDoor(f.w.room(T.HALL), Direction.SOUTH, DoorState.CLOSED)
    f.out(numb)
    f.input(ann, "open south")
    f.run(3)
    assertEquals("", f.out(numb))
  }

  @Test
  fun `the world's channel priority decides which rendering a player is told`() {
    for ((order, expected) in
      listOf(
        emptyList<String>() to "Ann opens the door south.",
        listOf("sound") to "You hear a door opening.",
      )) {
      val (f, _) = fixture(order = order)
      val ann = f.player("Ann")
      val cat = f.player("Cat")
      f.w.setDoor(f.w.room(T.HALL), Direction.SOUTH, DoorState.CLOSED)
      f.out(cat)
      f.input(ann, "open south")
      f.run(3)
      assertEquals(expected, f.out(cat).trim(), "order $order")
    }
  }

  @Test
  fun `a blind psionic is told of a hit by the world's channel`() {
    val pain = Channel(FixtureSchemas.current.schema.channels.first())
    val (f, _) = fixture(order = listOf(pain.name, "sight", "sound")) { PainRules(it, pain) }
    val ann = f.player("Ann")
    f.mob(T.GOBLIN)
    val psionic = f.listener("Psi", mapOf(Channel.SIGHT to 0, Channel.SOUND to 0, pain to 1))
    f.out(psionic)
    f.input(ann, "kill goblin")
    f.run(8)
    val out = f.out(psionic)
    assertTrue("You feel a stab of pain." in out, out)
    assertFalse("hits" in out, out)
  }

  @Test
  fun `a sighted psionic falls through to sight when the world channel has no text`() {
    val pain = Channel(FixtureSchemas.current.schema.channels.first())
    val (f, _) = fixture(order = listOf(pain.name, "sight", "sound")) { PainRules(it, pain) }
    val ann = f.player("Ann")
    f.mob(T.GOBLIN)
    val psionic = f.listener("Psi", mapOf(pain to 1))
    f.out(psionic)
    f.input(ann, "kill goblin")
    f.run(8)
    val out = f.out(psionic)
    assertTrue("are fighting!" in out, "the fight's start has no pain text, so sight is used: $out")
    assertTrue("You feel a stab of pain." in out, out)
    assertFalse("hits" in out, "a hit is told by the world channel, not by sight too: $out")
  }

  @Test
  fun `on_perceive tells the mob the channel, the starting intensity and the cost`() {
    val (f, heard) = fixture()
    val p = f.player("Ann")
    f.mob(ear, at = T.YARD)
    f.input(p, "shout HELLO")
    f.run()
    val h = heard.single()
    assertEquals("sound", h.channel)
    assertEquals(3, h.intensity)
    assertEquals(1, h.cost)
  }

  @Test
  fun `a blind and deaf mob perceives nothing, not even in its own room`() {
    val (f, heard) = fixture()
    val p = f.player("Ann")
    f.mob(blindDeaf)
    f.input(p, "say hi")
    f.input(p, "shout HEY")
    f.input(p, "emote waves")
    f.run()
    assertEquals(emptyList(), heard.toList().map { it.toString() })
  }

  @Test
  fun `sense deltas from affects, traits and equipment make a custom channel perceivable`() {
    val (f, heard) = fixture()
    val viaAffect = f.mob(deaf)
    val viaTrait = f.mob(deaf)
    val viaItem = f.mob(deaf)
    f.perceive("whisper_of_stars", spell, T.HALL, intensity = 1)
    f.run()
    assertEquals(emptyList(), heard.toList().map { it.toString() }, "nobody senses spell yet")
    f.w.applyAffect(viaAffect, "attuned", 100, senseDeltas = mapOf(spell to 1))
    f.w.traits = mapOf("seer" to Trait("seer", senseDeltas = mapOf(spell to 1)))
    viaTrait.grantedTraits = setOf("seer")
    viaTrait.recomputeTraits(f.w)
    f.w.spawnItem(lens.label, ItemLocation.Equipped(viaItem.id, "wield"))
    for (m in listOf(viaAffect, viaTrait, viaItem)) assertEquals(1, m.sense(spell), m.name)
    assertEquals(1, viaAffect.sense(Channel.SIGHT), "built-in senses are untouched")
    f.perceive("whisper_of_stars", spell, T.HALL, intensity = 1)
    f.run()
    assertEquals(3, heard.size)
  }

  @Test
  fun `senses never drop below 0`() {
    val (f, _) = fixture()
    val m = f.mob(ear)
    f.w.applyAffect(m, "stuffed", 100, senseDeltas = mapOf(Channel.SOUND to -5))
    assertEquals(0, m.sense(Channel.SOUND))
    assertEquals(1, m.baseSenses[Channel.SOUND])
  }

  @Test
  fun `opening a door runs before_open, which can veto, then on_open, and the sound reaches both sides`() {
    val (f, heard) = fixture()
    val host = f.w.scripts as RecordingHost
    val seen = mutableListOf<String>()
    var veto = true
    host.handlers[HookName.BEFORE_OPEN] = { _, owner, ctx ->
      seen += "before_open:${owner.name}:${ctx.direction}"
      if (veto) HookOutcome.Deny("It won't budge.") else null
    }
    host.handlers[HookName.ON_OPEN] = { _, owner, ctx ->
      seen += "on_open:${owner.name}:${ctx.direction}"
      null
    }
    val p = f.player("Ann")
    f.w.setDoor(f.w.room(T.HALL), Direction.SOUTH, DoorState.CLOSED)
    f.mob(ear, at = T.VAULT)
    f.mob(ear, at = T.YARD)
    f.input(p, "open south")
    f.run(3)
    assertTrue("It won't budge." in f.out(p))
    assertEquals(0, p.waitState)
    assertEquals(DoorState.CLOSED, f.w.room(T.HALL).exits.getValue(Direction.SOUTH).door!!.state)
    assertTrue(seen.none { it.startsWith("on_open") })
    assertTrue(heard.isEmpty())
    veto = false
    f.input(p, "open south")
    f.run(3)
    assertEquals(DoorState.OPEN, f.w.room(T.HALL).exits.getValue(Direction.SOUTH).door!!.state)
    assertTrue(seen.any { it.startsWith("on_open") && it.endsWith(":south") })
    // Both sides of the doorway are origins of one event: the vault's ear hears it in its own room,
    // not a second time from the hall, and the yard hears it from the hall.
    assertEquals(
      setOf("ear:open:-:vault", "ear:open:south:hall"),
      heard.map { it.toString() }.toSet(),
    )
  }

  @Test
  fun `a player on the far side of a door is told once, by the door itself`() {
    val (f, _) = fixture()
    val ann = f.player("Ann")
    val inside = f.player("Vic", at = T.VAULT)
    val neighbour = f.player("Yan", at = T.YARD)
    val bystander = f.player("Bea")
    f.w.setDoor(f.w.room(T.HALL), Direction.SOUTH, DoorState.CLOSED)
    f.out(inside)
    f.input(ann, "open south")
    f.run(3)
    val far = f.out(inside)
    assertTrue("opens" in far, far)
    assertFalse("You hear" in far, far)
    assertFalse("You hear" in f.out(bystander))
    assertEquals(1, Regex("You hear something open to the south").findAll(f.out(neighbour)).count())
  }

  @Test
  fun `containers run the same hooks with the item, and make sound only in their own room`() {
    val (f, heard) = fixture()
    val host = f.w.scripts as RecordingHost
    val seen = mutableListOf<String>()
    for (hook in
      listOf(HookName.BEFORE_OPEN, HookName.ON_OPEN, HookName.BEFORE_CLOSE, HookName.ON_CLOSE)) {
      host.handlers[hook] = { _, owner, ctx ->
        seen += "${hook.scriptName}:${owner.name}:${ctx.item?.name}:${ctx.direction}"
        null
      }
    }
    val p = f.player("Ann")
    f.mob(ear, at = T.YARD)
    f.w.spawnItem(chest, ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    f.input(p, "open chest")
    f.run(3)
    assertTrue(seen.contains("before_open:a chest:a chest:null"), seen.toString())
    assertTrue(seen.contains("on_open:a chest:a chest:null"), seen.toString())
    // A heavy container is loud: intensity 2, and the yard is one quiet hop away.
    assertEquals("[ear:open:south:hall]", heard.toString())
    heard.clear()
    f.input(p, "close chest")
    f.run(3)
    assertTrue(seen.contains("on_close:a chest:a chest:null"), seen.toString())
  }
}
