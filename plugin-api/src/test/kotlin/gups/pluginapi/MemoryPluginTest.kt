package gups.pluginapi

import gups.core.Label
import gups.pluginapi.host.PluginHost
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MemoryPluginTest {
  private val memoryFiles =
    mapOf(
      "MODULE.gups" to TestWorlds.module("memory_pulses = 600"),
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to
        """
        room("a", name = "A")
        mob("guard", name = "a guard", skills = {":hex": 100})
        skill("hex")
        """,
    )

  @Test
  fun `remember then memories round-trips`() {
    var capturedWorld: PluginWorld? = null
    val plugin =
      object : GupsPlugin {
        override val id = "mem"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onWorldStarted { pw -> capturedWorld = pw }
        }
      }

    val w = world(files = memoryFiles, plugins = PluginHost.of(plugin))
    val pw = checkNotNull(capturedWorld)
    val room = w.room(Label("//zones/z:a"))
    w.spawnMob(Label("//zones/z:guard"), room)
    w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    w.spawnPlayer("Bob", at = Label("//zones/z:a"))

    val guardFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "a guard" }!!
    val aliceFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "Alice" }!!
    val bobFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "Bob" }!!

    pw.remember(
      mob = guardFacade,
      subject = aliceFacade,
      event = "taunt",
      skill = "//zones/z:hex",
      amount = 15,
      target = bobFacade,
    )

    val list = pw.memories(guardFacade, subject = aliceFacade, event = "taunt")
    assertEquals(1, list.size)
    val mem = list.single()
    assertEquals(aliceFacade, mem.subject)
    assertEquals("taunt", mem.event)
    assertEquals(1, mem.count)
    assertEquals(15L, mem.amount)
    assertEquals("//zones/z:hex", mem.skill)
    assertEquals(bobFacade, mem.target)
    assertNull(mem.channel)
  }

  @Test
  fun `forget returns the count and removes only the matching memories`() {
    var capturedWorld: PluginWorld? = null
    val plugin =
      object : GupsPlugin {
        override val id = "mem"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onWorldStarted { pw -> capturedWorld = pw }
        }
      }

    val w = world(files = memoryFiles, plugins = PluginHost.of(plugin))
    val pw = checkNotNull(capturedWorld)
    val room = w.room(Label("//zones/z:a"))
    w.spawnMob(Label("//zones/z:guard"), room)
    w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    w.spawnPlayer("Bob", at = Label("//zones/z:a"))

    val guardFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "a guard" }!!
    val aliceFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "Alice" }!!
    val bobFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "Bob" }!!

    pw.remember(guardFacade, aliceFacade, "taunt")
    pw.remember(guardFacade, aliceFacade, "bribe")
    pw.remember(guardFacade, bobFacade, "taunt")

    val removed = pw.forget(guardFacade, subject = aliceFacade, event = "taunt")
    assertEquals(1, removed)
    assertEquals(0, pw.memories(guardFacade, subject = aliceFacade, event = "taunt").size)
    assertEquals(1, pw.memories(guardFacade, subject = aliceFacade, event = "bribe").size)
    assertEquals(1, pw.memories(guardFacade, subject = bobFacade, event = "taunt").size)
  }

  @Test
  fun `an invalid event throws IllegalArgumentException`() {
    var capturedWorld: PluginWorld? = null
    val plugin =
      object : GupsPlugin {
        override val id = "mem"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onWorldStarted { pw -> capturedWorld = pw }
        }
      }

    val w = world(files = memoryFiles, plugins = PluginHost.of(plugin))
    val pw = checkNotNull(capturedWorld)
    val room = w.room(Label("//zones/z:a"))
    w.spawnMob(Label("//zones/z:guard"), room)
    w.spawnPlayer("Alice", at = Label("//zones/z:a"))

    val guardFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "a guard" }!!
    val aliceFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "Alice" }!!

    assertFailsWith<IllegalArgumentException> { pw.remember(guardFacade, aliceFacade, "Bad-Event") }
    assertFailsWith<IllegalArgumentException> {
      pw.remember(guardFacade, aliceFacade, "taunt", amount = -1)
    }
    assertFailsWith<IllegalArgumentException> {
      pw.remember(guardFacade, aliceFacade, "taunt", skill = "//zones/z:unknown_skill")
    }
    assertFailsWith<IllegalArgumentException> { pw.memories(aliceFacade) }
    pw.remember(guardFacade, aliceFacade, "taunt")
    assertEquals(pw.memories(guardFacade), pw.memories(guardFacade, since = -1))
  }
}
