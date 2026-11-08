package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.DamageType
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.HookName
import gups.core.world.attach
import gups.core.world.awardXp
import gups.core.world.damage
import gups.core.world.groupAdd
import gups.core.world.handle
import gups.core.world.submit
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `r.onEvent` listeners: they run after the script global hooks, carry the event's ctx fields as
 * plain values, and can change the world like a script global hook may.
 */
class EventListenerTest {
  private val zone =
    mapOf(
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to
        """
        room("a", name = "Glade", desc = "A glade.", flags = ["dead_end_ok"])
        mob("wolf", name = "a wolf", desc = "A wolf.", keywords = ["wolf"])
        """,
    )

  private fun eventPlugin(
    id: String,
    listeners: List<Pair<String, (PluginEventCall) -> Unit>>,
  ): GupsPlugin =
    object : GupsPlugin {
      override val id = id
      override val pluginApiVersion = PluginApi.VERSION

      override fun register(r: PluginRegistry) {
        for ((name, fn) in listeners) r.onEvent(name, "Listens to $name.", fn)
      }
    }

  private fun kill(w: gups.core.world.World) {
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val mob = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))
    w.damage(mob, mapOf("hp" to 100), DamageType("fire"), source = p)
  }

  @Test
  fun `an on_death listener runs after the script global hook, with the victim actor and the killer in fields`() {
    var calls = 0
    var seen: String? = null
    var killerSeen: Any? = null
    val plugin =
      eventPlugin(
        "death",
        listOf(
          "on_death" to
            { call ->
              calls++
              seen = call.world.getAttr(call.actor.room, "lib:death_log", "") as String?
              killerSeen = call.fields["killer"]
            }
        ),
      )
    val w =
      world(
        files =
          zone +
            mapOf(
              "lib/package.star" to "package(api_version = 1, kind = \"lib\")",
              "lib/g.star" to
                """
                def _g(ctx):
                    ctx.set_attr(ctx.self.room, "death_log", ctx.get_attr(ctx.self.room, "death_log", "") + "script,")
                global_hook("on_death", _g)
                """,
            ),
        plugins = PluginHost.of(plugin),
      )
    kill(w)
    assertEquals(1, calls, "the on_death listener must run exactly once")
    assertEquals("script,", seen, "the plugin must run after the script global hook")
    assertEquals("Ann", (killerSeen as PluginActor).name)
  }

  @Test
  fun `an on_level_change listener sees old and new level as Longs`() {
    val calls = mutableListOf<Map<String, Any?>>()
    val plugin = eventPlugin("lv", listOf("on_level_change" to { call -> calls += call.fields }))
    val w = world(zone, plugins = PluginHost.of(plugin))
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    w.awardXp(p, 3500)
    assertTrue(calls.isNotEmpty(), "the on_level_change listener must run on a level-up")
    val f = calls.first()
    assertTrue(f["old_level"] is Long, "old_level must be a Long, got ${f["old_level"]?.javaClass}")
    assertTrue(f["new_level"] is Long, "new_level must be a Long, got ${f["new_level"]?.javaClass}")
    assertTrue((f["new_level"] as Long) > (f["old_level"] as Long))
  }

  @Test
  fun `an on_level_change listener registers without leveling but never runs`() {
    var ran = false
    val plugin = eventPlugin("lv", listOf("on_level_change" to { _ -> ran = true }))
    val w =
      world(
        zone,
        plugins = PluginHost.of(plugin),
        features = Features(Feature.entries.filter { it != Feature.LEVELING }.toSet()),
      )
    assertTrue(
      w.pluginEvents[HookName.ON_LEVEL_CHANGE].orEmpty().isNotEmpty(),
      "the listener must be installed",
    )
    assertFalse(ran, "with leveling off the listener must never run")
    assertTrue(
      w.diagnostics.none { it.kind == "plugin" },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `an on_group_change listener carries the group leader and members as PluginActors`() {
    var leaderSeen: PluginActor? = null
    var membersSeen: List<PluginActor>? = null
    val plugin =
      eventPlugin(
        "grp",
        listOf(
          "on_group_change" to
            { call ->
              val group = call.fields["group"] as Map<*, *>
              leaderSeen = group["leader"] as PluginActor
              membersSeen = (group["members"] as List<*>).map { it as PluginActor }
            }
        ),
      )
    val w = world(zone, plugins = PluginHost.of(plugin))
    val alice = w.spawnPlayer("Alice", Label("//zones/z:a"))
    val bob = w.spawnPlayer("Bob", Label("//zones/z:a"))
    w.groupAdd(bob, alice)
    assertTrue(leaderSeen != null, "the group listener must see a leader")
    assertEquals("Alice", leaderSeen.name)
    assertEquals(listOf("Alice", "Bob"), membersSeen!!.map { it.name })
  }

  @Test
  fun `listeners run in plugin-id order, then registration order within a plugin`() {
    val order = mutableListOf<String>()
    val beta =
      eventPlugin(
        "beta",
        listOf("on_death" to { order += "beta-1" }, "on_death" to { order += "beta-2" }),
      )
    val alpha = eventPlugin("alpha", listOf("on_death" to { order += "alpha" }))
    val w = world(zone, plugins = PluginHost.of(beta, alpha))
    kill(w)
    assertEquals(listOf("alpha", "beta-1", "beta-2"), order)
  }

  @Test
  fun `an invalid event name fails register and the plugin is skipped`() {
    var registered = false
    val bad =
      object : GupsPlugin {
        override val id = "bad"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onEvent("on_tick", "Nope.", { _ -> })
          registered = true
        }
      }
    val warnings = mutableListOf<String>()
    val host = PluginHost.of(bad, log = { warnings += it })
    assertFalse(registered, "register must be interrupted at the invalid onEvent")
    assertTrue(host.ids.isEmpty(), "the failing plugin must be skipped")
    assertTrue(
      warnings.any { it.contains("register failed") && it.contains("on_tick") },
      warnings.joinToString("\n"),
    )
  }

  @Test
  fun `a throwing listener records a diagnostic and the next listener still runs`() {
    val order = mutableListOf<String>()
    val plugin =
      eventPlugin(
        "boom",
        listOf(
          "on_death" to
            {
              order += "first"
              error("boom")
            },
          "on_death" to { order += "second" },
        ),
      )
    val w = world(zone, plugins = PluginHost.of(plugin))
    kill(w)
    assertEquals(listOf("first", "second"), order, "the second listener must still run")
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && it.message.contains("on_death") },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `a listener's setAttr on the actor persists`() {
    val plugin =
      eventPlugin(
        "mark",
        listOf("on_level_change" to { call -> call.world.setAttr(call.actor, "seen", true) }),
      )
    val w = world(zone, plugins = PluginHost.of(plugin))
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    w.awardXp(p, 3500)
    val f = WorldFacade(w, "mark") {}
    assertEquals(true, f.getAttr(ActorFacade(p, w), "mark:seen"))
  }

  @Test
  fun `an on_death listener can set an attribute on the dying mob, with no diagnostic`() {
    val plugin =
      eventPlugin(
        "ghost",
        listOf("on_death" to { call -> call.world.setAttr(call.actor, "x", 1L) }),
      )
    val w = world(zone, plugins = PluginHost.of(plugin))
    kill(w)
    assertTrue(
      w.diagnostics.none { it.kind == "plugin" },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `an on_skill_use listener runs after the script global hook, with outcome, skill and the landed actor`() {
    var calls = 0
    var seen: String? = null
    var outcomeSeen: Any? = null
    var skillSeen: Any? = null
    var targetsSeen: List<PluginActor>? = null
    val plugin =
      eventPlugin(
        "skilluse",
        listOf(
          "on_skill_use" to
            { call ->
              calls++
              seen = call.world.getAttr(call.actor.room, "lib:use_log", "") as String?
              outcomeSeen = call.fields["outcome"]
              skillSeen = call.fields["skill"]
              targetsSeen = (call.fields["targets"] as? List<*>)?.map { it as PluginActor }
            }
        ),
      )
    val w =
      world(
        zone +
          mapOf(
            "zones/z/skills.star" to
              """
              skill("mend", target = "self", effect = heal(dice = "2d4", vital = "hp"))
              """,
            "lib/package.star" to "package(api_version = 1, kind = \"lib\")",
            "lib/g.star" to
              """
              def _g(ctx):
                  ctx.set_attr(ctx.self.room, "use_log", ctx.get_attr(ctx.self.room, "use_log", "") + "script," + ctx.outcome)
              global_hook("on_skill_use", _g)
              """,
          ),
        plugins = PluginHost.of(plugin),
      )
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    p.skills[Label("//zones/z:mend")] = 100
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), p)
    w.submit(p, "cast mend")
    w.runPulse()
    assertEquals(1, calls, "the on_skill_use listener must run exactly once")
    assertEquals("script,success", seen, "the plugin must run after the script global hook")
    assertEquals("success", outcomeSeen)
    assertEquals("//zones/z:mend", skillSeen)
    assertEquals(listOf("Ann"), targetsSeen?.map { it.name })
  }

  @Test
  fun `an on_skill_use listener registers without skills but never runs`() {
    var ran = false
    val plugin = eventPlugin("su", listOf("on_skill_use" to { _ -> ran = true }))
    val w =
      world(
        zone,
        plugins = PluginHost.of(plugin),
        features = Features(Feature.entries.filter { it != Feature.SKILLS }.toSet()),
      )
    assertTrue(
      w.pluginEvents[HookName.ON_SKILL_USE].orEmpty().isNotEmpty(),
      "the listener must be installed",
    )
    assertFalse(ran, "with skills off the listener must never run")
    assertTrue(
      w.diagnostics.none { it.kind == "plugin" },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }
}
