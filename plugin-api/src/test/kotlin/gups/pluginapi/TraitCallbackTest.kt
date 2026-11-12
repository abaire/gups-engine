package gups.pluginapi

import gups.core.CASCADE_MAX_DEPTH
import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.grantTrait
import gups.core.world.handle
import gups.core.world.spawnItem
import gups.core.world.submit
import gups.pluginapi.host.traitAdapter
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraitCallbackTest {
  private val bolt = Label("//zones/z:bolt")

  private val files =
    ZONE +
      mapOf(
        "zones/z/skills.star" to
          """
            skill("bolt", kind = "spell", target = "enemy", tags = ["blue", "arcane"], effect = damage(dice = "1d4"))
          """,
        "zones/z/items.star" to
          """
            item("cap", name = "a cap", desc = "A cap.", keywords = ["cap"], wear = "held", equip_affects = ["no_regen"])
          """,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules("def recognizes_skill(ctx):\n    return True"),
      )

  private class Scene(val w: World, val ann: Player, val bea: Player, val sink: CapturingSink)

  private fun scene(vararg traits: PluginTrait): Scene {
    val w = world(files)
    w.traits = traits.associate { it.name to traitAdapter("bluemage", it) }
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val bea = w.spawnPlayer("Bea", Label("//zones/z:a"))
    ann.skills[bolt] = 100
    w.spawnMob(Label("//zones/z:wolf"), ann.room)
    for (t in traits) w.grantTrait(bea, t.name)
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), ann)
    return Scene(w, ann, bea, sink)
  }

  private fun Scene.cast() {
    w.submit(ann, "cast bolt wolf")
    repeat(6) { w.runPulse() }
  }

  @Test
  fun `a plugin trait handler fires through a real cast with plugin typed fields`() {
    var call: PluginTraitCall? = null
    val s =
      scene(
        PluginTrait(
          "watcher",
          on = mapOf(PluginTraitEvent.SKILL_RECOGNIZED to PluginTraitHandler { call = it }),
        )
      )
    s.cast()
    val c = assertNotNull(call)
    assertEquals(PluginTraitEvent.SKILL_RECOGNIZED, c.event)
    assertEquals("Bea", c.bearer.name)
    assertEquals("Ann", assertIs<PluginActor>(c.fields["source"]).name)
    assertEquals(bolt.v, c.fields["skill"])
    assertEquals("success", c.fields["outcome"])
    assertEquals("a wolf", assertIs<PluginActor>(c.fields["target"]).name)
    assertIs<Long>(c.fields["power"])
  }

  @Test
  fun `a handler may change the world through its PluginWorld`() {
    val s =
      scene(
        PluginTrait(
          "watcher",
          on =
            mapOf(
              PluginTraitEvent.SKILL_RECOGNIZED to
                PluginTraitHandler { it.world.send(it.bearer, "You study the bolt.") }
            ),
        )
      )
    val beaSink = CapturingSink()
    s.w.handle(WorldControlEvent.Connect(SessionId(2), beaSink))
    s.w.attach(SessionId(2), s.bea)
    s.cast()
    assertTrue("You study the bolt." in beaSink.drain())
  }

  @Test
  fun `a throwing handler records a plugin diagnostic`() {
    val s =
      scene(
        PluginTrait(
          "watcher",
          on = mapOf(PluginTraitEvent.SKILL_RECOGNIZED to PluginTraitHandler { error("boom") }),
        )
      )
    s.cast()
    val plugin = s.w.diagnostics.filter { it.kind == "plugin" }.map { it.message }
    assertTrue(
      plugin.any {
        "plugin 'bluemage': trait 'watcher' handler SKILL_RECOGNIZED threw: boom" in it
      },
      "$plugin",
    )
  }

  @Test
  fun `skill returns a loaded skill's view, and null for an unknown or malformed label`() {
    val w = world(files)
    val facade = gups.pluginapi.host.WorldFacade(w, "bluemage") {}
    val view = assertNotNull(facade.skill("//zones/z:bolt"))
    assertEquals("spell", view.kind)
    assertEquals(listOf("arcane", "blue"), view.tags)
    assertEquals("//zones/z:bolt", view.label)
    assertNull(facade.skill("//zones/z:nope"))
    assertNull(facade.skill("not a label"))
  }

  private class Rec(val calls: MutableList<PluginTraitCall> = mutableListOf()) {
    fun of(event: PluginTraitEvent) = calls.filter { it.event == event }
  }

  private fun recorder(
    rec: Rec,
    vararg events: PluginTraitEvent,
    extra: (PluginTraitCall) -> Unit = {},
  ) =
    PluginTrait(
      "recorder",
      on =
        events.associateWith {
          PluginTraitHandler { c ->
            rec.calls += c
            extra(c)
          }
        },
    )

  private fun Scene.wolf() = w.actors.filterIsInstance<gups.core.model.Mob>().first()

  @Test
  fun `DAMAGED through a real attack carries plugin typed fields`() {
    val rec = Rec()
    val s = scene(recorder(rec, PluginTraitEvent.DAMAGED))
    val wolf = s.wolf()
    s.w.grantTrait(wolf, "recorder")
    s.w.submit(s.ann, "kill wolf")
    repeat(60) { s.w.runPulse() }
    val c = rec.of(PluginTraitEvent.DAMAGED).first { it.bearer.name == "a wolf" }
    assertEquals("Ann", assertIs<PluginActor>(c.fields["source"]).name)
    assertIs<Long>(c.fields["amount"])
    assertIs<String>(c.fields["type"])
    val losses = assertIs<Map<*, *>>(c.fields["losses"])
    assertTrue(losses.isNotEmpty() && losses.values.all { it is Long })
    val parts = assertIs<List<*>>(c.fields["parts"])
    assertTrue(parts.isNotEmpty())
    for (part in parts) {
      val m = assertIs<Map<*, *>>(part)
      assertIs<String>(m["type"])
      assertTrue(assertIs<Map<*, *>>(m["losses"]).values.all { it is Long })
    }
  }

  @Test
  fun `HEALED through PluginWorld heal has Long amounts and no source`() {
    val rec = Rec()
    val s =
      scene(
        recorder(rec, PluginTraitEvent.HEALED, PluginTraitEvent.DAMAGED) { c ->
          if (c.event == PluginTraitEvent.DAMAGED) c.world.heal(c.bearer, 1, "hp")
        }
      )
    val facade = gups.pluginapi.host.WorldFacade(s.w, "bluemage") {}
    facade.damage(gups.pluginapi.host.ActorFacade(s.bea, s.w), 3, "fire", "hp")
    val c = rec.of(PluginTraitEvent.HEALED).single()
    assertEquals(1L, c.fields["amount"])
    assertEquals(1L, c.fields["requested"])
    assertEquals("hp", c.fields["vital"])
    assertNull(c.fields["source"])
  }

  @Test
  fun `DIED and KILLED through a real kill carry actors`() {
    val rec = Rec()
    val s = scene(recorder(rec, PluginTraitEvent.DIED, PluginTraitEvent.KILLED))
    val wolf = s.wolf()
    s.w.grantTrait(wolf, "recorder")
    s.w.grantTrait(s.ann, "recorder")
    val facade = gups.pluginapi.host.WorldFacade(s.w, "bluemage") {}
    facade.damage(
      gups.pluginapi.host.ActorFacade(wolf, s.w),
      10_000,
      "fire",
      "hp",
      source = gups.pluginapi.host.ActorFacade(s.ann, s.w),
    )
    repeat(4) { s.w.runPulse() }
    val died = rec.of(PluginTraitEvent.DIED).single()
    assertEquals("a wolf", died.bearer.name)
    assertEquals("Ann", assertIs<PluginActor>(died.fields["killer"]).name)
    val killed = rec.of(PluginTraitEvent.KILLED).single()
    assertEquals("Ann", killed.bearer.name)
    assertEquals("a wolf", assertIs<PluginActor>(killed.fields["victim"]).name)
  }

  @Test
  fun `LEVEL_CHANGED through levelUp has Long levels`() {
    val rec = Rec()
    val s = scene(recorder(rec, PluginTraitEvent.LEVEL_CHANGED))
    val facade = gups.pluginapi.host.WorldFacade(s.w, "bluemage") {}
    val bea = gups.pluginapi.host.ActorFacade(s.bea, s.w)
    val need = s.w.rules.xpToLevel(gups.core.rules.RulesContext(s.w, s.bea), 1)
    facade.awardXp(bea, need)
    val c = rec.of(PluginTraitEvent.LEVEL_CHANGED).single()
    assertEquals(1L, c.fields["old_level"])
    assertEquals(2L, c.fields["new_level"])
  }

  @Test
  fun `ROOM_ENTERED through a walk and a teleport`() {
    val rec = Rec()
    val s = scene(recorder(rec, PluginTraitEvent.ROOM_ENTERED))
    s.w.submit(s.bea, "north")
    repeat(6) { s.w.runPulse() }
    val walk = rec.of(PluginTraitEvent.ROOM_ENTERED).single()
    assertEquals("Glade", assertIs<PluginRoom>(walk.fields["from_room"]).name)
    assertEquals("north", walk.fields["direction"])

    val facade = gups.pluginapi.host.WorldFacade(s.w, "bluemage") {}
    facade.teleport(
      gups.pluginapi.host.ActorFacade(s.bea, s.w),
      gups.pluginapi.host.RoomFacade(s.w.room(Label("//zones/z:a")), s.w),
    )
    repeat(4) { s.w.runPulse() }
    val tp = rec.of(PluginTraitEvent.ROOM_ENTERED).last()
    assertEquals(2, rec.of(PluginTraitEvent.ROOM_ENTERED).size)
    assertEquals("Cave", assertIs<PluginRoom>(tp.fields["from_room"]).name)
    assertNull(tp.fields["direction"])
  }

  @Test
  fun `handlers that damage each other are cut off by the cascade depth`() {
    val rec = Rec()
    val s =
      scene(
        recorder(rec, PluginTraitEvent.DAMAGED) { c ->
          val other = if (c.bearer.name == "Bea") "Ann" else "Bea"
          val target = (c.fields["source"] as? PluginActor) ?: return@recorder
          if (target.name == other) c.world.damage(target, 1, "fire", "hp", source = c.bearer)
        }
      )
    s.w.grantTrait(s.ann, "recorder")
    val facade = gups.pluginapi.host.WorldFacade(s.w, "bluemage") {}
    facade.damage(
      gups.pluginapi.host.ActorFacade(s.bea, s.w),
      1,
      "fire",
      "hp",
      source = gups.pluginapi.host.ActorFacade(s.ann, s.w),
    )
    assertTrue(s.w.diagnostics.any { it.kind == "cascade" }, "${s.w.diagnostics}")
    assertTrue(rec.calls.size <= CASCADE_MAX_DEPTH, "${rec.calls.size}")
  }

  @Test
  fun `a mob bearer receives trait events`() {
    val rec = Rec()
    val s = scene(recorder(rec, PluginTraitEvent.DAMAGED))
    val wolf = s.wolf()
    s.w.grantTrait(wolf, "recorder")
    val facade = gups.pluginapi.host.WorldFacade(s.w, "bluemage") {}
    facade.damage(gups.pluginapi.host.ActorFacade(wolf, s.w), 1, "fire", "hp")
    assertEquals(
      "a wolf",
      rec.of(PluginTraitEvent.DAMAGED).first { it.bearer.name == "a wolf" }.bearer.name,
    )
  }

  @Test
  fun `AFFECT_GAINED and AFFECT_LOST through PluginWorld applyAffect and removeAffect carry plugin typed fields`() {
    val rec = Rec()
    val s = scene(recorder(rec, PluginTraitEvent.AFFECT_GAINED, PluginTraitEvent.AFFECT_LOST))
    val facade = gups.pluginapi.host.WorldFacade(s.w, "bluemage") {}
    val bea = gups.pluginapi.host.ActorFacade(s.bea, s.w)
    facade.applyAffect(bea, "ward", 40)
    facade.removeAffect(bea, "ward")
    assertEquals(
      listOf(PluginTraitEvent.AFFECT_GAINED, PluginTraitEvent.AFFECT_LOST),
      rec.calls.map { it.event },
    )
    val gained = rec.of(PluginTraitEvent.AFFECT_GAINED).single()
    assertEquals("ward", gained.fields["name"])
    assertIs<Long>(gained.fields["power"])
    assertIs<Long>(gained.fields["expires_at"])
    assertEquals(false, gained.fields["replaced"])
    assertNull(gained.fields["source_item"])
    assertEquals("removed", rec.of(PluginTraitEvent.AFFECT_LOST).single().fields["reason"])
  }

  @Test
  fun `AFFECT_GAINED through wearing gear carries a PluginItem source_item`() {
    val rec = Rec()
    val s = scene(recorder(rec, PluginTraitEvent.AFFECT_GAINED))
    s.w.spawnItem(Label("//zones/z:cap"), ItemLocation.Carried(s.bea.id))
    s.w.submit(s.bea, "wear cap")
    repeat(6) { s.w.runPulse() }
    val c = rec.of(PluginTraitEvent.AFFECT_GAINED).single()
    assertIs<PluginItem>(c.fields["source_item"])
    assertNull(c.fields["expires_at"])
  }
}
