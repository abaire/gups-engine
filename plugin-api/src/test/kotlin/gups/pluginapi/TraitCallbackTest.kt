package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.grantTrait
import gups.core.world.handle
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
}
