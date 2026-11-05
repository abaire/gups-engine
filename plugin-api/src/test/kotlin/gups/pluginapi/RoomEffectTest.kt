package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.handle
import gups.core.world.submit
import gups.pluginapi.host.PluginHost
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A plugin whose effect primitive records the [PluginEffectCall] it is handed. */
class RoomEffectTest {
  private val calls = mutableListOf<PluginEffectCall>()

  /** A world declaring [rooms], with the recorder plugin (and optionally a throwing effect). */
  private fun worldOf(rooms: String, rules: String, throwing: Boolean): World {
    val host =
      PluginHost.of(
        object : GupsPlugin {
          override val id = "recorder"
          override val pluginApiVersion = PluginApi.VERSION

          override fun register(r: PluginRegistry) {
            r.addEffectPrimitive("record", "Records the call.", emptyList()) { call ->
              if (throwing) error("boom")
              calls += call
            }
          }
        }
      )
    return world(
      files =
        ZONE +
          mapOf(
            "zones/z/rooms.star" to rooms,
            "rules/package.star" to TestWorlds.RULES_PACKAGE,
            "rules/combat.star" to rules,
          ),
      plugins = host,
    )
  }

  private fun connect(w: World, p: Player) {
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), p)
  }

  @Test
  fun `a room skill's plugin primitive gets the caster's room and a null target and targetItem`() {
    val w =
      worldOf(
        """
        skill("ward", target = "room", effect = recorder.record())
        room("a", name = "Glade", desc = "A glade.", size = SIZE.large)
        room("b", name = "Cave", desc = "A cave.")
        """,
        TestWorlds.rules("def skill_power(ctx):\n    return 100"),
        false,
      )
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    p.skills[Label("//zones/z:ward")] = 100
    connect(w, p)
    w.submit(p, "cast ward")
    w.runPulse()
    val call = assertNotNull(calls.single())
    assertNull(call.target, "a room skill has no actor target")
    assertNull(call.targetItem, "a room skill has no item target")
    assertTrue(call.targetRoom?.label == "//zones/z:a", "the room skill targets the caster's room")
    assertEquals(p.id.v, call.caster.id)
    assertEquals(100, call.power, "a cast at default power runs at 100")
  }

  @Test
  fun `a room skill's plugin primitive runs at the cast's power`() {
    val w =
      worldOf(
        """
        skill("ward", target = "room", effect = recorder.record())
        room("a", name = "Glade", desc = "A glade.", size = SIZE.large)
        """,
        TestWorlds.rules("def skill_power(ctx):\n    return 150"),
        false,
      )
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    p.skills[Label("//zones/z:ward")] = 100
    connect(w, p)
    w.submit(p, "cast ward")
    w.runPulse()
    assertEquals(150, calls.single().power, "a cast at power 150 runs the primitive at 150")
  }

  @Test
  fun `a room skill's sequence runs the plugin primitive and the apply_affect, in order`() {
    val w =
      worldOf(
        """
        skill("seq", target = "room", effect = sequence(recorder.record(), apply_affect("h", 10, add_tags = ["x"])))
        room("a", name = "Glade", desc = "A glade.", size = SIZE.large)
        """,
        TestWorlds.rules("def skill_power(ctx):\n    return 100"),
        false,
      )
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    p.skills[Label("//zones/z:seq")] = 100
    connect(w, p)
    w.submit(p, "cast seq")
    w.runPulse()
    assertEquals(1, calls.size, "the plugin primitive ran")
    val a = w.room(Label("//zones/z:a"))
    assertTrue("x" in a.tagsAt(w.pulse), "the room affect ran after the plugin primitive")
  }

  @Test
  fun `the same primitive in an enemy skill gets a null room and its target`() {
    val w =
      worldOf(
        """
        skill("zap", target = "enemy", effect = recorder.record())
        room("a", name = "Glade", desc = "A glade.", size = SIZE.large)
        mob("wolf", name = "a wolf", desc = "A wolf.", keywords = ["wolf"])
        """,
        TestWorlds.rules("def skill_power(ctx):\n    return 100"),
        false,
      )
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    p.skills[Label("//zones/z:zap")] = 100
    val wolf = w.spawnMob(Label("//zones/z:wolf"), p.room)
    connect(w, p)
    w.submit(p, "cast zap wolf")
    w.runPulse()
    val call = assertNotNull(calls.single())
    assertTrue(call.targetRoom == null, "an enemy skill does not target a room")
    assertTrue(call.target?.id == wolf.id.v, "the enemy skill still targets its victim")
  }

  @Test
  fun `a plugin effect that throws is guarded and the cast goes on`() {
    val w =
      worldOf(
        """
        skill("ward", target = "room", effect = recorder.record())
        room("a", name = "Glade", desc = "A glade.", size = SIZE.large)
        """,
        TestWorlds.rules("def skill_power(ctx):\n    return 100"),
        throwing = true,
      )
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    p.skills[Label("//zones/z:ward")] = 100
    connect(w, p)
    w.submit(p, "cast ward")
    w.runPulse()
    assertTrue(calls.isEmpty(), "the throwing effect recorded nothing")
    assertTrue(w.diagnostics.any { "boom" in it.message }, "the throw is diagnosed")
  }
}
