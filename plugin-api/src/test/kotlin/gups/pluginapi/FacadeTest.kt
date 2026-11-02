package gups.pluginapi

import gups.core.CapturingSink
import gups.core.ClientInputEvent
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.rules.RulesContext
import gups.core.rules.StartingStats
import gups.core.world.LevelingMsg
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.destroy
import gups.core.world.flushOutput
import gups.core.world.handle
import gups.core.world.moveItem
import gups.core.world.quit
import gups.core.world.spawnItem
import gups.core.world.tombstone
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.ItemFacade
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.WorldFacade
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeTest {
  private val w: World = world()
  private val f = WorldFacade(w, "weather") {}
  private val glade = f.room("//zones/z:a")!!
  private val cave = f.room("//zones/z:b")!!
  private val ann =
    ActorFacade(
      w.spawnPlayer(
        "Ann",
        Label("//zones/z:a"),
        overrides = StartingStats(max = mapOf("hp" to 20), current = mapOf("hp" to 20)),
      ),
      w,
    )
  private val wolf =
    ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)

  @Test
  fun `reads`() {
    assertEquals(listOf("//zones/z:a", "//zones/z:b"), f.rooms().map { it.label })
    assertNull(f.room("//zones/z:nope"))
    assertNull(f.room("not a label"))
    assertEquals(listOf("forest", "outdoors", "underground"), f.regions())
    assertEquals("large", glade.size)
    assertEquals(setOf("dead_end_ok"), cave.flags)
    assertEquals(mapOf("north" to "//zones/z:b"), glade.exits)
    assertEquals(listOf("forest", "outdoors"), glade.regions)
    assertEquals(setOf("Ann", "a wolf"), glade.actors.map { it.name }.toSet())
    assertEquals(7L, f.seed)
    assertEquals(w.pulse, f.pulse)
    assertTrue(f.rng.int(1, 6) in 1..6)
    f.rng.chance(50)
    assertTrue(ann.isPlayer)
    assertNull(ann.label)
    assertEquals("//zones/z:wolf", wolf.label)
    assertEquals(glade, wolf.room)
    val stick =
      ItemFacade(
        w.spawnItem(
          Label("//zones/z:stick"),
          ItemLocation.InRoom(w.room(Label("//zones/z:a")).id, 0),
        ),
        w,
      )
    assertEquals(listOf("stick", "wood"), stick.keywords)
    assertEquals(listOf("a stick"), glade.items.map { it.name })
  }

  @Test
  fun `verbs`() {
    f.send(ann, "Hello.")
    f.echo(glade, "Thunder.", exclude = listOf(wolf))
    f.damage(ann, 5, "fire", "hp", source = wolf)
    assertEquals(15, ann.vital("hp"))
    f.heal(ann, 3, "hp")
    assertEquals(18, ann.vital("hp"))
    assertFailsWith<IllegalArgumentException> { f.damage(ann, -1, "fire", "hp") }
    f.applyAffect(ann, "soaked", 10, statDeltas = mapOf("str" to -1), flags = setOf("wet"))
    assertTrue(ann.hasAffect("soaked"))
    assertEquals(listOf("soaked"), ann.affects)
    assertTrue(f.removeAffect(ann, "soaked"))
    assertFalse(f.removeAffect(ann, "soaked"))
    f.teleport(ann, cave)
    assertEquals(cave, ann.room)
    assertEquals(20, ann.maxVital("hp"))
    assertEquals(1, ann.level)
  }

  @Test
  fun `verbs refuse entities that have left the world`() {
    val stick =
      ItemFacade(
        w.spawnItem(
          Label("//zones/z:stick"),
          ItemLocation.InRoom(w.room(Label("//zones/z:a")).id, 0),
        ),
        w,
      )
    w.quit(ann.actor as Player)
    w.destroy(stick.item)
    assertFalse(ann.actor.inWorld)
    assertEquals(
      "teleport(actor): Ann is no longer in the world",
      assertFailsWith<IllegalArgumentException> { f.teleport(ann, cave) }.message,
    )
    assertFailsWith<IllegalArgumentException> { f.echo(glade, "Thunder.", exclude = listOf(ann)) }
    assertFailsWith<IllegalArgumentException> { f.setAttr(ann, "x", 1) }
    assertEquals(
      "get_attr(entity): ${stick.name} is no longer in the world",
      assertFailsWith<IllegalArgumentException> { f.getAttr(stick, "x") }.message,
    )
    assertFailsWith<IllegalArgumentException> { f.awardXp(ann, 10) }
    assertFailsWith<IllegalArgumentException> { f.takeXp(ann, 10) }
    assertFailsWith<IllegalArgumentException> { f.levelUp(ann) }
    assertFailsWith<IllegalArgumentException> { f.levelDown(ann, 1) }
  }

  @Test
  fun `leveling verbs in auto mode`() {
    val lw = world(LEVELING_FILES)
    val lf = WorldFacade(lw, "weather") {}
    val bob = ActorFacade(lw.spawnPlayer("Bob", Label("//zones/z:a")), lw)
    val firstThreshold = lw.rules.xpToLevel(RulesContext(lw, bob.actor), 1)
    assertEquals(1, bob.level)
    assertEquals(0, bob.pendingLevels)

    lf.awardXp(bob, firstThreshold - 1)
    assertEquals(1, bob.level)
    assertEquals(0, bob.pendingLevels)

    assertEquals(200L, lf.takeXp(bob, 200))
    assertEquals(firstThreshold - 201, lf.takeXp(bob, firstThreshold))
    assertEquals(0L, lf.takeXp(bob, 100))

    val baseHp = bob.maxVital("hp")
    lf.awardXp(bob, firstThreshold)
    assertEquals(2, bob.level)
    assertEquals(0, bob.pendingLevels)
    assertEquals(baseHp + 5, bob.maxVital("hp"))

    assertFalse(lf.levelUp(bob))

    assertEquals(1, lf.levelDown(bob, 1))
    assertEquals(1, bob.level)
    assertEquals(baseHp, bob.maxVital("hp"))
    assertEquals(firstThreshold - 1, (bob.actor as Player).xp)

    assertEquals(0, lf.levelDown(bob, 1))
  }

  @Test
  fun `leveling verbs in deferred mode`() {
    val deferredFiles =
      ZONE + mapOf("MODULE.gups" to TestWorlds.module("level_mode = \"deferred\""))
    val dw = world(deferredFiles)
    val df = WorldFacade(dw, "weather") {}
    val dan = dw.spawnPlayer("Dan", Label("//zones/z:a"))
    val sink = connect(dw, dan)
    val player = ActorFacade(dan, dw)
    val c = RulesContext(dw, dan)
    assertEquals(1, player.level)
    assertEquals(0, player.pendingLevels)

    df.awardXp(player, dw.rules.xpToLevel(c, 2))
    assertEquals(1, player.level)
    assertEquals(2, player.pendingLevels)
    dw.flushOutput()
    assertEquals(1, sink.drain().split(READY).size - 1)

    assertTrue(df.levelUp(player))
    assertEquals(2, player.level)
    assertEquals(1, player.pendingLevels)
    dw.flushOutput()
    assertContains(sink.drain(), READY)

    assertTrue(df.levelUp(player))
    assertEquals(3, player.level)
    assertEquals(0, player.pendingLevels)
    dw.flushOutput()
    val last = sink.drain()
    assertContains(last, "level 3")
    assertFalse(READY in last)

    assertFalse(df.levelUp(player))
  }

  @Test
  fun `leveling verbs work on a mob`() {
    val lw = world(LEVELING_FILES)
    val lf = WorldFacade(lw, "weather") {}
    val wolf = ActorFacade(lw.spawnMob(Label("//zones/z:wolf"), lw.room(Label("//zones/z:a"))), lw)
    val baseHp = wolf.maxVital("hp")

    lf.awardXp(wolf, lw.rules.xpToLevel(RulesContext(lw, wolf.actor), 2))
    assertEquals(3, wolf.level)
    assertEquals(baseHp + 10, wolf.maxVital("hp"))
    assertEquals(setOf(2, 3), wolf.actor.levelGains.keys)

    assertEquals(100L, lf.takeXp(wolf, 100))
    assertEquals(2, lf.levelDown(wolf, 5))
    assertEquals(1, wolf.level)
    assertEquals(baseHp, wolf.maxVital("hp"))
    assertTrue(wolf.actor.levelGains.isEmpty())
  }

  @Test
  fun `leveling facade members give the same results as the ctx verbs`() {
    val script =
      """
      def _go(ctx):
          wolf = [x for x in ctx.room.actors() if not x.is_player][0]
          out = []
          for a in [ctx.actor, wolf]:
              ctx.award_xp(a, 3500)
              out.append(str(ctx.take_xp(a, 200)))
              out.append(str(ctx.level_up(a)))
              out.append(str(ctx.level_down(a, 5)))
              ctx.award_xp(a, 3500)
              out.append(str(ctx.level_down(a, 1)))
          ctx.send(ctx.actor, "results: " + " ".join(out))
    """
    fun files(withCommand: Boolean): Map<String, String> {
      val rooms = ZONE.getValue("zones/z/rooms.star").trimIndent()
      val exits = "exits = [exit(DIR.north, \":b\")]"
      val wired =
        if (withCommand) rooms.replace(exits, "$exits, commands = {\"go\": _go}") else rooms
      return LEVELING_FILES + ("zones/z/rooms.star" to script.trimIndent() + "\n" + wired)
    }

    val sw = world(files(withCommand = true))
    val sAnn = sw.spawnPlayer("Ann", Label("//zones/z:a"))
    val sWolf = sw.spawnMob(Label("//zones/z:wolf"), sw.room(Label("//zones/z:a")))
    val sSink = connect(sw, sAnn)
    sw.handle(ClientInputEvent(sAnn.session!!, "go", 1_000_000_000))
    sw.runPulse()
    sw.flushOutput()
    val scriptOut = sSink.drain()

    val fw = world(files(withCommand = false))
    val fAnn = fw.spawnPlayer("Ann", Label("//zones/z:a"))
    val fWolf = fw.spawnMob(Label("//zones/z:wolf"), fw.room(Label("//zones/z:a")))
    val fSink = connect(fw, fAnn)
    val ff = WorldFacade(fw, "weather") {}
    val results = mutableListOf<String>()
    for (a in listOf(ActorFacade(fAnn, fw), ActorFacade(fWolf, fw))) {
      ff.awardXp(a, 3500)
      results += ff.takeXp(a, 200).toString()
      results += if (ff.levelUp(a)) "True" else "False"
      results += ff.levelDown(a, 5).toString()
      ff.awardXp(a, 3500)
      results += ff.levelDown(a, 1).toString()
    }
    fw.flushOutput()
    val facadeOut = fSink.drain()

    assertContains(scriptOut, "results: " + results.joinToString(" "))
    assertContains(scriptOut, "You have fallen to level 1.")
    fun leveling(out: String) = out.lines().filter { it.startsWith("You ") }
    assertEquals(leveling(scriptOut), leveling(facadeOut))
    for ((s, f) in listOf(sAnn to fAnn, sWolf to fWolf)) {
      assertEquals(2, s.level)
      assertEquals(setOf(2), s.levelGains.keys)
      assertEquals(s.level, f.level)
      assertEquals(s.xp, f.xp)
      assertEquals(s.levelGains, f.levelGains)
      assertEquals(s.baseStats, f.baseStats)
      assertEquals(s.max("hp"), f.max("hp"))
      assertEquals(s.current("hp"), f.current("hp"))
    }
  }

  @Test
  fun `leveling verbs reject non-positive arguments`() {
    assertFailsWith<IllegalArgumentException> { f.awardXp(ann, 0) }
    assertFailsWith<IllegalArgumentException> { f.awardXp(ann, -1) }
    assertFailsWith<IllegalArgumentException> { f.takeXp(ann, 0) }
    assertFailsWith<IllegalArgumentException> { f.takeXp(ann, -1) }
    assertFailsWith<IllegalArgumentException> { f.levelDown(ann, 0) }
    assertFailsWith<IllegalArgumentException> { f.levelDown(ann, -1) }
  }

  @Test
  fun `attributes live in the plugin's namespace`() {
    f.setAttr(glade, "visits", 2)
    f.setAttr(glade, "seen", listOf("Ann", true, mapOf("n" to 1L)))
    assertEquals(2L, f.getAttr(glade, "visits"))
    assertEquals(2L, f.getAttr(glade, "weather:visits"))
    assertEquals(listOf("Ann", true, mapOf("n" to 1L)), f.getAttr(glade, "seen"))
    assertEquals("dflt", f.getAttr(glade, "missing", "dflt"))
    assertTrue("weather:visits" in w.room(Label("//zones/z:a")).customAttributes)
    f.delAttr(glade, "visits")
    assertNull(f.getAttr(glade, "visits"))
    f.setAttr(glade, "seen", null)
    assertNull(f.getAttr(glade, "seen"))
    assertFailsWith<IllegalArgumentException> { f.setAttr(glade, "other:x", 1) }
    assertFailsWith<IllegalArgumentException> { f.setAttr(glade, "x", 1.5) }
    assertFailsWith<IllegalArgumentException> { f.setAttr(glade, "bad key", 1) }
    val raw = WorldFacade(w, null) {}
    assertFailsWith<IllegalArgumentException> { raw.getAttr(glade, "x") }
    f.setAttr(glade, "y", 1)
    assertEquals(1L, raw.getAttr(glade, "weather:y"))
  }

  @Test
  fun `diagnose and log`() {
    val lines = mutableListOf<String>()
    WorldFacade(w, "weather") { lines += it }.log("hi")
    f.diagnose("odd")
    assertEquals(listOf("plugin 'weather': hi"), lines)
    assertTrue(w.diagnostics.any { it.kind == "plugin" && it.message == "plugin 'weather': odd" })
  }

  @Test
  fun `actor reads go by schema name`() {
    assertEquals(10, ann.stat("dex"))
    assertEquals(10, ann.baseStat("dex"))
    f.applyAffect(ann, "quick", 10, statDeltas = mapOf("dex" to 3))
    assertEquals(13, ann.stat("dex"))
    assertEquals(10, ann.baseStat("dex"))
    assertEquals(20, ann.vital("hp"))
    assertEquals(20, ann.maxVital("mana"))
    assertEquals(0, ann.resist("fire"))
  }

  @Test
  fun `an undeclared name throws with a suggestion`() {
    assertEquals(
      "unknown stat 'dxe' (did you mean 'dex'?)",
      assertFailsWith<IllegalArgumentException> { ann.stat("dxe") }.message,
    )
    assertFailsWith<IllegalArgumentException> { ann.vital("health") }
    assertFailsWith<IllegalArgumentException> { ann.resist("acid") }
    assertEquals(
      "damage(vital): unknown vital 'hpp' (did you mean 'hp'?)",
      assertFailsWith<IllegalArgumentException> { f.damage(ann, 1, "fire", "hpp") }.message,
    )
    assertContains(
      assertFailsWith<IllegalArgumentException> { f.damage(ann, 1, "fier", "hp") }.message!!,
      "unknown damage type 'fier' (did you mean 'fire'?)",
    )
    assertFailsWith<IllegalArgumentException> { f.heal(ann, 1, "mnaa") }
    assertContains(
      assertFailsWith<IllegalArgumentException> {
          f.applyAffect(ann, "weak", 5, statDeltas = mapOf("strength" to -1))
        }
        .message!!,
      "unknown stat 'strength'",
    )
  }

  @Test
  fun `the schema lists names and labels in declaration order`() {
    val s = f.schema
    assertEquals(listOf("str", "dex", "con", "int", "wis", "armor"), s.stats.map { it.name })
    assertEquals(PluginNamed("hp", "Hit points"), s.vitals.first())
    assertEquals(listOf("hp", "mana", "move"), s.vitals.map { it.name })
    assertEquals(listOf("bludgeon", "slash", "pierce", "fire"), s.damageTypes)
  }

  @Test
  fun `containment filter blocks carried affects behind lead container and not elsewhere`() {
    val plugin = TestFilterPlugin()
    val testWorld = world(files = ITEM_FILES, plugins = PluginHost.of(plugin))
    val player =
      ActorFacade(
        testWorld.spawnPlayer(
          "Ann",
          Label("//zones/z:a"),
          overrides = StartingStats(max = mapOf("hp" to 20), current = mapOf("hp" to 20)),
        ),
        testWorld,
      )
    assertEquals(10, player.stat("dex"))

    // 1. In plain container: affect reaches holder
    val bag = testWorld.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(player.actor.id))
    val charm = testWorld.spawnItem(Label("//zones/z:charm"), ItemLocation.Contained(bag.id))
    assertEquals(12, player.stat("dex"))
    assertTrue(plugin.callLog.isNotEmpty())
    val call = plugin.callLog.last()
    assertEquals(bag.id.v, call.container.id)
    assertEquals(charm.id.v, call.item.id)
    assertEquals("luck", call.affect.name)
    assertFalse(call.container.hasTag("lead"))

    // 2. Behind container tagged "lead": affect is blocked
    val leadBox =
      testWorld.spawnItem(Label("//zones/z:lead_box"), ItemLocation.Carried(player.actor.id))
    testWorld.moveItem(charm, ItemLocation.Contained(leadBox.id))
    assertEquals(10, player.stat("dex"))
    assertTrue(plugin.callLog.last().container.hasTag("lead"))

    // 3. Directly carried: not behind a container, so affect reaches holder and filter is not
    // called
    val callsBefore = plugin.callLog.size
    testWorld.moveItem(charm, ItemLocation.Carried(player.actor.id))
    assertEquals(12, player.stat("dex"))
    assertEquals(callsBefore, plugin.callLog.size)
  }

  @Test
  fun `throwing containment filter records a plugin diagnostic and does not block`() {
    val plugin = TestFilterPlugin(throwOnContainer = "lead")
    val testWorld = world(files = ITEM_FILES, plugins = PluginHost.of(plugin))
    val player =
      ActorFacade(
        testWorld.spawnPlayer(
          "Ann",
          Label("//zones/z:a"),
          overrides = StartingStats(max = mapOf("hp" to 20), current = mapOf("hp" to 20)),
        ),
        testWorld,
      )
    val leadBox =
      testWorld.spawnItem(Label("//zones/z:lead_box"), ItemLocation.Carried(player.actor.id))
    val charm = testWorld.spawnItem(Label("//zones/z:charm"), ItemLocation.Contained(leadBox.id))

    // Filter threw, so it did not block; carried affect reaches holder
    assertEquals(12, player.stat("dex"))
    assertTrue(plugin.callLog.isNotEmpty())
    assertTrue(
      testWorld.diagnostics.any {
        it.kind == "plugin" &&
          "containment filter failed" in it.message &&
          "Filter exploded on lead" in it.message
      }
    )
  }

  @Test
  fun `facade addTag and removeTag on container refresh holder and flip stat`() {
    val plugin = TestFilterPlugin()
    val testWorld = world(files = ITEM_FILES, plugins = PluginHost.of(plugin))
    val testFacade = WorldFacade(testWorld, "filtertest") {}
    val player =
      ActorFacade(
        testWorld.spawnPlayer(
          "Ann",
          Label("//zones/z:a"),
          overrides = StartingStats(max = mapOf("hp" to 20), current = mapOf("hp" to 20)),
        ),
        testWorld,
      )
    val bag = testWorld.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(player.actor.id))
    val bagItem = ItemFacade(bag, testWorld)
    val charm = testWorld.spawnItem(Label("//zones/z:charm"), ItemLocation.Contained(bag.id))

    assertEquals(12, player.stat("dex"))
    assertFalse(bagItem.hasTag("lead"))

    // Add "lead" tag: filter now blocks, holder's dex flips to 10
    assertTrue(testFacade.addTag(bagItem, "lead"))
    assertTrue(bagItem.hasTag("lead"))
    assertContains(bagItem.tags, "lead")
    assertEquals(10, player.stat("dex"))

    // Adding again returns false and keeps state
    assertFalse(testFacade.addTag(bagItem, "lead"))
    assertEquals(10, player.stat("dex"))

    // Remove "lead" tag: filter unblocks, holder's dex flips back to 12
    assertTrue(testFacade.removeTag(bagItem, "lead"))
    assertFalse(bagItem.hasTag("lead"))
    assertEquals(12, player.stat("dex"))

    // Removing again returns false
    assertFalse(testFacade.removeTag(bagItem, "lead"))
    assertEquals(12, player.stat("dex"))
  }

  @Test
  fun `transform through the facade swaps prototype and keeps id and attributes`() {
    val testWorld = world(files = ITEM_FILES)
    val testFacade = WorldFacade(testWorld, "weather") {}
    val charm =
      testWorld.spawnItem(
        Label("//zones/z:charm"),
        ItemLocation.InRoom(testWorld.room(Label("//zones/z:a")).id, 0),
      )
    val charmItem = ItemFacade(charm, testWorld)
    val idBefore = charmItem.id

    testFacade.setAttr(charmItem, "enchanted", true)
    assertEquals("//zones/z:charm", charmItem.label)
    assertEquals("a lucky charm", charmItem.name)

    testFacade.transform(charmItem, "//zones/z:fancy_charm")
    assertEquals(idBefore, charmItem.id)
    assertEquals("//zones/z:fancy_charm", charmItem.label)
    assertEquals("a fancy charm", charmItem.name)
    assertEquals(true, testFacade.getAttr(charmItem, "enchanted"))

    assertFailsWith<IllegalArgumentException> {
      testFacade.transform(charmItem, "//zones/z:nonexistent")
    }
  }

  @Test
  fun `PluginItem affects shows prototype entry and instance entry with right origin`() {
    val testWorld = world(files = ITEM_FILES)
    val testFacade = WorldFacade(testWorld, "weather") {}
    val charm =
      testWorld.spawnItem(
        Label("//zones/z:charm"),
        ItemLocation.InRoom(testWorld.room(Label("//zones/z:a")).id, 0),
      )
    val charmItem = ItemFacade(charm, testWorld)

    // Prototype affect
    assertEquals(1, charmItem.affects.size)
    val protoAff = charmItem.affects[0]
    assertEquals("luck", protoAff.name)
    assertEquals("carried", protoAff.applies)
    assertEquals("proto", protoAff.origin)
    assertNull(protoAff.expiresAt)
    assertEquals(mapOf("dex" to 2), protoAff.statDeltas)

    // Apply instance affect through facade
    testFacade.applyItemAffect(
      charmItem,
      "keen",
      duration = 50,
      applies = "worn",
      statDeltas = mapOf("str" to 1),
      flags = setOf("sharp"),
    )
    assertEquals(2, charmItem.affects.size)
    val instAff = charmItem.affects[1]
    assertEquals("keen", instAff.name)
    assertEquals("worn", instAff.applies)
    assertEquals("instance", instAff.origin)
    assertEquals(testWorld.pulse + 50, instAff.expiresAt)
    assertEquals(mapOf("str" to 1), instAff.statDeltas)
    assertEquals(listOf("sharp"), instAff.flags)

    // Permanent instance affect
    testFacade.applyItemAffect(charmItem, "glow", duration = null, applies = "carried")
    assertEquals(3, charmItem.affects.size)
    val permAff = charmItem.affects.last()
    assertEquals("glow", permAff.name)
    assertEquals("instance", permAff.origin)
    assertNull(permAff.expiresAt)

    // Remove instance affect
    assertTrue(testFacade.removeItemAffect(charmItem, "keen"))
    assertFalse(testFacade.removeItemAffect(charmItem, "keen"))
    assertEquals(2, charmItem.affects.size)

    // Tombstone prototype returns empty affects list
    charm.proto = tombstone(charm.proto)
    assertTrue(charmItem.affects.isEmpty())
  }

  @Test
  fun `PluginItem custodian is null on ground and actor for carried, worn, and nested items`() {
    val testWorld = world(files = ITEM_FILES)
    val player =
      ActorFacade(
        testWorld.spawnPlayer(
          "Ann",
          Label("//zones/z:a"),
          overrides = StartingStats(max = mapOf("hp" to 20), current = mapOf("hp" to 20)),
        ),
        testWorld,
      )

    // Ground item
    val groundStick =
      ItemFacade(
        testWorld.spawnItem(
          Label("//zones/z:stick"),
          ItemLocation.InRoom(testWorld.room(Label("//zones/z:a")).id, 0),
        ),
        testWorld,
      )
    assertNull(groundStick.custodian)

    // Carried item
    val carriedStick =
      ItemFacade(
        testWorld.spawnItem(Label("//zones/z:stick"), ItemLocation.Carried(player.actor.id)),
        testWorld,
      )
    assertEquals(player.id, carriedStick.custodian?.id)
    assertEquals("Ann", carriedStick.custodian?.name)

    // Worn item
    val wornBackpack =
      ItemFacade(
        testWorld.spawnItem(
          Label("//zones/z:backpack"),
          ItemLocation.Equipped(player.actor.id, "held"),
        ),
        testWorld,
      )
    assertEquals(player.id, wornBackpack.custodian?.id)

    // Nested container inside worn backpack
    val innerBag =
      ItemFacade(
        testWorld.spawnItem(Label("//zones/z:bag"), ItemLocation.Contained(wornBackpack.item.id)),
        testWorld,
      )
    val nestedCharm =
      ItemFacade(
        testWorld.spawnItem(Label("//zones/z:charm"), ItemLocation.Contained(innerBag.item.id)),
        testWorld,
      )
    assertEquals(player.id, innerBag.custodian?.id)
    assertEquals(player.id, nestedCharm.custodian?.id)

    // Contents inspection
    assertEquals(listOf(innerBag.id), wornBackpack.contents.map { it.id })
    assertEquals(listOf(nestedCharm.id), innerBag.contents.map { it.id })
    assertTrue(nestedCharm.contents.isEmpty())

    // Variants and tags inspection
    assertEquals(mapOf("fancy" to "//zones/z:fancy_charm"), nestedCharm.variants)
  }

  private class TestFilterPlugin(
    val throwOnContainer: String? = null,
    val callLog: MutableList<PluginContainmentCall> = mutableListOf(),
  ) : GupsPlugin {
    override val id = "filtertest"
    override val pluginApiVersion = PluginApi.VERSION

    override fun register(r: PluginRegistry) {
      r.addContainmentFilter("Blocks carried affects behind lead containers.") { call ->
        callLog.add(call)
        if (throwOnContainer != null && call.container.hasTag(throwOnContainer)) {
          throw RuntimeException("Filter exploded on $throwOnContainer")
        }
        call.container.hasTag("lead")
      }
    }
  }

  private fun connect(w: World, p: Player): CapturingSink {
    val id = SessionId(1)
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(id, sink), 0)
    w.attach(id, p)
    w.flushOutput()
    sink.drain()
    return sink
  }

  private companion object {
    val READY = LevelingMsg.READY.default

    /** A world whose `on_level_up` adds 5 max hp per level. */
    val LEVELING_FILES =
      ZONE +
        mapOf(
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to
            TestWorlds.rules("def on_level_up(ctx):\n    return struct(max = vitals(hp = 5))\n"),
        )

    val ITEM_FILES =
      ZONE +
        mapOf(
          "zones/z/items.star" to
            """
            item("charm", name = "a lucky charm", keywords = ["charm"], affects = [item_affect("luck", applies = "carried", stat_deltas = stats(dex = 2))], variants = {"fancy": ":fancy_charm"})
            item("fancy_charm", name = "a fancy charm", keywords = ["charm"])
            item("bag", name = "a bag", keywords = ["bag"], container = container())
            item("lead_box", name = "a lead box", keywords = ["box"], tags = ["lead"], container = container())
            item("backpack", name = "a backpack", keywords = ["backpack"], wear = "held", container = container())
            """
        )
  }
}
