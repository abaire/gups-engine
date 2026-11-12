package gups.core.world

import gups.core.CASCADE_MAX_DEPTH
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Effect
import gups.core.model.EquipAffect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.model.Trait
import gups.core.model.TraitEvent
import gups.core.model.TraitHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AffectTraitEventsTest {
  class Call(val bearer: Actor, val event: TraitEvent, val fields: Map<String, Any?>)

  private val calls = mutableListOf<Call>()

  private val cap = Label("//t:cap")
  private val timedCap = Label("//t:timed_cap")

  private fun Fixture.trait(
    name: String,
    vararg events: TraitEvent,
    also: (World, Actor, Map<String, Any?>) -> Unit = { _, _, _ -> },
  ) {
    val handler = TraitHandler { world, bearer, event, fields ->
      calls += Call(bearer, event, fields)
      also(world, bearer, fields)
    }
    w.traits = w.traits + (name to Trait(name, handlers = events.associateWith { handler }))
  }

  private fun Fixture.of(event: TraitEvent) = calls.filter { it.event == event }

  private fun gearFixture(host: ScriptHost = ScriptHost.NONE) =
    Fixture(
      scripts = host,
      protos =
        Prototypes(
          T.rooms,
          T.mobs,
          T.items +
            listOf(
              ItemProto(
                cap,
                "a cap",
                listOf("cap"),
                wear = "head",
                equipAffects = listOf(EquipAffect("no_regen")),
              ),
              ItemProto(
                timedCap,
                "a timed cap",
                listOf("tcap"),
                wear = "head",
                equipAffects = listOf(EquipAffect("no_regen", pulses = 50)),
              ),
            ),
          T.skills,
        ),
    )

  private fun Fixture.wearing(who: Actor, label: Label): Item {
    val item = w.spawnItem(label, ItemLocation.Carried(who.id))
    w.moveItem(item, ItemLocation.Equipped(who.id, "head"))
    return item
  }

  @Test
  fun `gained fires once for a new affect with every field`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.AFFECT_GAINED)
    val ann = f.player("Ann")
    val dee = f.player("Dee", index = 1)
    f.w.grantTrait(ann, "watcher")
    f.w.applyAffect(ann, AffectSpec("ward", 40, flags = setOf("zeta", "alpha"), source = dee))
    val call = f.of(TraitEvent.AFFECT_GAINED).single()
    assertEquals(ann, call.bearer)
    assertEquals("ward", call.fields["name"])
    assertEquals(listOf("alpha", "zeta"), call.fields["flags"])
    assertSame(dee, call.fields["source"])
    assertNull(call.fields["source_item"])
    assertEquals(100, call.fields["power"])
    assertEquals(f.w.pulse + 40, call.fields["expires_at"])
    assertEquals(false, call.fields["replaced"])
  }

  @Test
  fun `a reapplication that doesn't last longer fires nothing`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.AFFECT_GAINED, TraitEvent.AFFECT_LOST)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.applyAffect(ann, AffectSpec("ward", 40))
    calls.clear()
    f.w.applyAffect(ann, AffectSpec("ward", 40))
    f.w.applyAffect(ann, AffectSpec("ward", 10))
    assertTrue(calls.isEmpty(), "${calls.size} calls")
  }

  @Test
  fun `a longer reapplication fires one gained with replaced and no lost`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.AFFECT_GAINED, TraitEvent.AFFECT_LOST)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.applyAffect(ann, AffectSpec("ward", 40))
    calls.clear()
    f.w.applyAffect(ann, AffectSpec("ward", 90))
    val call = calls.single()
    assertEquals(TraitEvent.AFFECT_GAINED, call.event)
    assertEquals(true, call.fields["replaced"])
  }

  @Test
  fun `the gained handler sees the final state`() {
    val f = Fixture()
    var seen: Boolean? = null
    f.trait("watcher", TraitEvent.AFFECT_GAINED) { _, bearer, _ ->
      seen = bearer.hasFlag("glowing")
    }
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.applyAffect(ann, AffectSpec("glow", 40, flags = setOf("glowing")))
    assertEquals(true, seen)
  }

  @Test
  fun `expiry fires lost with reason expired after on_expire`() {
    val host = RecordingHost()
    var marker = false
    host.refHandlers["done"] = { _, _, _ -> marker = true }
    val f = Fixture(scripts = host)
    var seen: Boolean? = null
    f.trait("watcher", TraitEvent.AFFECT_LOST) { _, _, _ -> seen = marker }
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.applyAffect(ann, AffectSpec("ward", 5, onExpire = HookRef(Label("//t:t.star"), "done")))
    f.run(10)
    val call = f.of(TraitEvent.AFFECT_LOST).single()
    assertEquals("ward", call.fields["name"])
    assertEquals("expired", call.fields["reason"])
    assertEquals(100, call.fields["power"])
    assertEquals(true, seen)
  }

  @Test
  fun `removeAffect before expiry fires lost with reason removed`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.AFFECT_LOST)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.applyAffect(ann, AffectSpec("ward", 500))
    f.w.removeAffect(ann, "ward")
    assertEquals("removed", f.of(TraitEvent.AFFECT_LOST).single().fields["reason"])
  }

  @Test
  fun `a dispel of two affects fires two lost events in removal order`() {
    val f = Fixture()
    f.trait("watcher", TraitEvent.AFFECT_LOST)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.w.applyAffect(ann, AffectSpec("hex_a", 500, flags = setOf("curse")))
    f.w.applyAffect(ann, AffectSpec("hex_b", 500, flags = setOf("curse")))
    f.w.applyEffect(Effect.Dispel(flags = setOf("curse")), ann, ann)
    assertEquals(listOf("hex_a", "hex_b"), f.of(TraitEvent.AFFECT_LOST).map { it.fields["name"] })
  }

  @Test
  fun `a summoned mob gets lost before it leaves the world`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val pet = f.w.summon(ann, Effect.Summon(T.DUMMY, 1, 500)).first()
    var inWorld: Boolean? = null
    f.trait("watcher", TraitEvent.AFFECT_LOST) { _, bearer, _ -> inWorld = bearer.inWorld }
    f.w.grantTrait(pet, "watcher")
    f.w.removeAffect(pet, "summoned")
    assertEquals(pet, f.of(TraitEvent.AFFECT_LOST).single().bearer)
    assertEquals(true, inWorld)
    assertTrue(!pet.inWorld)
  }

  @Test
  fun `wearing and removing gear fires both events with the item`() {
    val f = gearFixture()
    f.trait("watcher", TraitEvent.AFFECT_GAINED, TraitEvent.AFFECT_LOST)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    val item = f.wearing(ann, cap)
    val gained = f.of(TraitEvent.AFFECT_GAINED).single()
    assertSame(item, gained.fields["source_item"])
    assertNull(gained.fields["expires_at"])
    f.w.moveItem(item, ItemLocation.Carried(ann.id))
    val lost = f.of(TraitEvent.AFFECT_LOST).single()
    assertSame(item, lost.fields["source_item"])
    assertEquals("removed", lost.fields["reason"])
  }

  @Test
  fun `a lost handler sees the finished move and can put the gear back on`() {
    val f = gearFixture()
    var seen: ItemLocation? = null
    var snapBack = true
    f.trait("watcher", TraitEvent.AFFECT_LOST) { w, bearer, fields ->
      val cap = fields["source_item"] as Item
      seen = cap.location
      if (snapBack) {
        snapBack = false
        w.moveItem(cap, ItemLocation.Equipped(bearer.id, "head"))
      }
    }
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    val item = f.wearing(ann, cap)
    f.w.moveItem(item, ItemLocation.Carried(ann.id))
    assertEquals(ItemLocation.Carried(ann.id), seen)
    assertEquals(ItemLocation.Equipped(ann.id, "head"), item.location)
    assertSame(item, ann.equipmentIndex["head"])
    assertTrue(item.id !in ann.inventoryIndex, "the cap must not be indexed twice")
    assertTrue(ann.hasFlag("no_regen"))
  }

  @Test
  fun `a timed equip affect fires with no source item`() {
    val f = gearFixture()
    f.trait("watcher", TraitEvent.AFFECT_GAINED)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.wearing(ann, timedCap)
    val gained = f.of(TraitEvent.AFFECT_GAINED).single()
    assertNull(gained.fields["source_item"])
    assertEquals(f.w.pulse + 50, gained.fields["expires_at"])
  }

  @Test
  fun `settling restored holdings fires nothing`() {
    val f = gearFixture()
    f.trait("watcher", TraitEvent.AFFECT_GAINED, TraitEvent.AFFECT_LOST)
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "watcher")
    f.wearing(ann, cap)
    ann.affects.clear()
    calls.clear()
    f.w.settleRestoredHoldings(ann)
    assertTrue(ann.hasFlag("no_regen"), "the test needs the equip affect rebuilt")
    assertTrue(calls.isEmpty(), "${calls.size} calls")
  }

  @Test
  fun `a mob that dies in its gear gets no lost while the gear moves to the corpse`() {
    val f = gearFixture()
    f.trait("watcher", TraitEvent.AFFECT_LOST)
    val goblin = f.mob()
    f.w.grantTrait(goblin, "watcher")
    f.wearing(goblin, cap)
    f.hurt(goblin, goblin.current(f.fs.health) + 1)
    f.run(2)
    assertTrue(!goblin.inWorld, "the test needs the mob dead")
    assertTrue(calls.isEmpty(), "${calls.size} calls")
  }

  @Test
  fun `a dormant mob with the trait gets no call`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    f.trait("watcher", TraitEvent.AFFECT_GAINED)
    val rat = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    f.w.grantTrait(rat, "watcher")
    f.run(2)
    assertTrue(f.w.room(C.r(9)).isDormant)
    f.w.applyAffect(rat, AffectSpec("ward", 40))
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `an affect loop is cut off at the cascade depth`() {
    val f = Fixture()
    val handlers =
      mapOf(
        TraitEvent.AFFECT_GAINED to
          TraitHandler { w, bearer, event, fields ->
            calls += Call(bearer, event, fields)
            w.removeAffect(bearer, "loop")
          },
        TraitEvent.AFFECT_LOST to
          TraitHandler { w, bearer, event, fields ->
            calls += Call(bearer, event, fields)
            w.applyAffect(bearer, AffectSpec("loop", 10))
          },
      )
    f.w.traits = f.w.traits + ("looper" to Trait("looper", handlers = handlers))
    val ann = f.player("Ann")
    f.w.grantTrait(ann, "looper")
    f.w.applyAffect(ann, AffectSpec("loop", 10))
    assertTrue(f.w.diagnostics.any { it.kind == "cascade" }, f.w.diagnostics.toString())
    assertTrue(calls.size <= 2 * CASCADE_MAX_DEPTH, "${calls.size} calls")
  }

  @Test
  fun `an affect that ran out while its room slept fires lost when the room wakes`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    f.trait("watcher", TraitEvent.AFFECT_LOST)
    val rat = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    f.w.grantTrait(rat, "watcher")
    f.w.applyAffect(rat, AffectSpec("ward", 100))
    f.runTo(400)
    assertTrue(calls.isEmpty(), "nothing fires while dormant")
    f.player("Ann", at = C.r(9))
    f.run()
    val call = f.of(TraitEvent.AFFECT_LOST).single()
    assertEquals("expired", call.fields["reason"])
  }
}
