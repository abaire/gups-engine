package gups.sample.weather

import gups.core.fixtures.TempFiles
import gups.harness.Status
import gups.harness.TestRunner
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginDoor
import gups.pluginapi.PluginEffect
import gups.pluginapi.PluginEffectCall
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginExit
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRestrictionCall
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginSchema
import gups.pluginapi.PluginTickCall
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld
import gups.pluginapi.host.PluginLoader
import gups.script.WorldDir
import gups.script.WorldModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.starlark.java.eval.StarlarkValue

class WeatherPluginTest {
  // ---- Fakes of the plugin API: unused members TODO() ---------------------------------------

  private class FakeRegistry : PluginRegistry {
    val builtins = mutableMapOf<String, (PluginWorld, List<Any?>, Map<String, Any?>) -> Any?>()
    val commands = mutableMapOf<String, (PluginCommandCall) -> Unit>()
    val effects = mutableMapOf<String, Pair<List<PluginParam>, PluginEffect>>()
    val gmcp = mutableMapOf<String, (PluginWorld, PluginActor) -> Any?>()
    val gmcpEvents = mutableMapOf<String, String>()
    val gmcpInputs = mutableMapOf<String, (PluginGmcpCall) -> Unit>()
    val started = mutableListOf<(PluginWorld) -> Unit>()
    val chainThinks = mutableListOf<(gups.pluginapi.PluginChainThinkCall) -> Boolean>()

    data class Every(val pulses: Int, val doc: String, val fn: (PluginTickCall) -> Unit)

    val every = mutableListOf<Every>()

    override fun addBuiltin(name: String, value: StarlarkValue, doc: String) = TODO()

    override fun addBuiltin(
      name: String,
      doc: String,
      params: List<PluginParam>,
      returnType: String,
      call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
    ) {
      builtins[name] = call
    }

    override fun addCommand(
      name: String,
      lane: PluginLane,
      role: PluginRole,
      doc: String,
      run: (PluginCommandCall) -> Unit,
    ) {
      commands[name] = run
    }

    override fun addEffectPrimitive(
      name: String,
      doc: String,
      params: List<PluginParam>,
      apply: PluginEffect,
    ) {
      effects[name] = params to apply
    }

    override fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?) {
      gmcp[pkg] = produce
    }

    override fun addGmcpEvent(pkg: String, doc: String) {
      gmcpEvents[pkg] = doc
    }

    override fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit) {
      gmcpInputs[pkg] = handle
    }

    override fun addHook(
      name: String,
      doc: String,
      handle: (gups.pluginapi.PluginHookCall) -> Any?,
    ) = TODO()

    override fun addTrait(trait: PluginTrait) = TODO()

    override fun setCreation(creation: PluginCreation) = TODO()

    override fun addRestriction(
      point: PluginRestrictionPoint,
      doc: String,
      check: (PluginRestrictionCall) -> String?,
    ) = TODO()

    override fun addContainmentFilter(
      doc: String,
      blocks: (gups.pluginapi.PluginContainmentCall) -> Boolean,
    ) = TODO()

    override fun addTargetOrder(
      doc: String,
      choose: (gups.pluginapi.PluginTargetOrderCall) -> gups.pluginapi.PluginTargetChoice,
    ) = TODO()

    override fun addChainThink(
      doc: String,
      decide: (gups.pluginapi.PluginChainThinkCall) -> Boolean,
    ) {
      chainThinks += decide
    }

    override fun addOwnedBehaviors(
      doc: String,
      adjust: (gups.pluginapi.PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>,
    ) = TODO()

    override fun addDispositionModifier(
      doc: String,
      adjust: (gups.pluginapi.PluginDispositionCall) -> Int,
    ) = TODO()

    override fun addFactions(
      doc: String,
      adjust: (gups.pluginapi.PluginFactionsCall) -> List<String>,
    ) = TODO()

    override fun onWorldStarted(fn: (PluginWorld) -> Unit) {
      started += fn
    }

    override fun every(pulses: Int, doc: String, fn: (PluginTickCall) -> Unit) {
      every += Every(pulses, doc, fn)
    }
  }

  /** A door's key and its mutable state; the same instance backs both sides of a passage. */
  private class Door(val key: String?, var state: String)

  private class FakeRoom(
    override val regions: List<String>,
    override val label: String = "//zones/valley:x",
    override val name: String = "X",
    /** Direction to destination label, for each exit the room has. */
    private val exitTo: Map<String, String> = emptyMap(),
    /** The doors of the exits, by direction; an exit not listed has no door. */
    private val doors: Map<String, Door> = emptyMap(),
  ) : PluginRoom {
    override val id = 1L
    override val flags: Set<String>
      get() = TODO()

    override val size: String
      get() = TODO()

    override val exits: Map<String, String>
      get() = exitTo

    override val actors: List<PluginActor>
      get() = TODO()

    override val items: List<PluginItem>
      get() = TODO()

    override val tags: List<String>
      get() = emptyList()

    override fun exit(dir: String): PluginExit? {
      val to = exitTo[dir] ?: return null
      val door = doors[dir]
      return PluginExit(
        dir,
        to,
        emptyList(),
        door?.let { PluginDoor(it.state, it.key, emptyList()) },
        door?.key,
        true,
        false,
      )
    }

    /** The mutable door the exit in [dir] has, or null when it has none. */
    fun doorOf(dir: String): Door? = doors[dir]
  }

  private class FakeActor(override val room: PluginRoom) : PluginActor {
    override val id = 2L
    override val label: String? = null
    override val name = "Ann"
    override val isPlayer = true

    override fun stat(name: String): Int = TODO()

    override fun baseStat(name: String): Int = TODO()

    override fun vital(name: String): Int = TODO()

    override fun maxVital(name: String): Int = TODO()

    override fun baseMaxVital(name: String): Int = TODO()

    override fun resist(type: String): Int = TODO()

    override fun baseResist(type: String): Int = TODO()

    override val level
      get() = TODO()

    override val pendingLevels: Int
      get() = TODO()

    override val affects: List<String>
      get() = TODO()

    override fun hasAffect(name: String): Boolean = TODO()

    override fun hasTrait(name: String): Boolean = false

    override val role: PluginRole?
      get() = PluginRole.PLAYER

    override val flags: Set<String>
      get() = emptySet()

    override val choices: Map<String, String>
      get() = emptyMap()

    override val traits: List<String>
      get() = emptyList()

    override val grantedTraits: Set<String>
      get() = emptySet()

    override val skills: Map<String, Int>
      get() = emptyMap()

    override val inventory: List<PluginItem>
      get() = emptyList()

    override val equipment: Map<String, PluginItem>
      get() = emptyMap()
  }

  private class FakeWorld(
    override val seed: Long = 42,
    override var pulse: Long = 0,
    private val regionList: List<String> = emptyList(),
    /** The rooms of the world, already in label order, as the engine's returns them. */
    val rooms: List<PluginRoom> = emptyList(),
  ) : PluginWorld {
    val sent = mutableListOf<String>()
    val gmcpSent = mutableListOf<Pair<String, Any?>>()
    val affects = mutableListOf<List<Any>>()
    val logs = mutableListOf<String>()
    val echoes = mutableListOf<Pair<PluginRoom, String>>()
    val lockedDoors = mutableListOf<Pair<String, String>>()
    val unlockedDoors = mutableListOf<Pair<String, String>>()
    private val attrs = HashMap<Any, MutableMap<String, Any?>>()

    override fun send(target: PluginActor, text: String) {
      sent += text
    }

    override fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean {
      gmcpSent += pkg to data
      return true
    }

    override fun applyAffect(
      target: PluginActor,
      name: String,
      duration: Int,
      statDeltas: Map<String, Int>,
      flags: Set<String>,
      maxVitalDeltas: Map<String, Int>,
      resistDeltas: Map<String, Int>,
    ) {
      affects += listOf(target, name, duration, statDeltas)
    }

    override fun log(message: String) {
      logs += message
    }

    override fun regions(): List<String> = regionList

    override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) {
      echoes += room to text
    }

    override fun lockDoor(room: PluginRoom, dir: String) {
      val door =
        (room as FakeRoom).doorOf(dir)
          ?: throw IllegalArgumentException("lockDoor(): ${room.label} has no door $dir")
      check(door.key != null) { "lockDoor(): ${room.label} door $dir has no lock" }
      door.state = "locked"
      lockedDoors += room.label to dir
    }

    override fun unlockDoor(room: PluginRoom, dir: String) {
      val door =
        (room as FakeRoom).doorOf(dir)
          ?: throw IllegalArgumentException("unlockDoor(): ${room.label} has no door $dir")
      if (door.state == "locked") door.state = "closed"
      unlockedDoors += room.label to dir
    }

    override fun damage(
      target: PluginActor,
      amount: Int,
      type: String,
      vital: String,
      source: PluginActor?,
    ) = TODO()

    override fun heal(target: PluginActor, amount: Int, vital: String, source: PluginActor?) =
      TODO()

    override val schema: PluginSchema
      get() = TODO()

    override fun removeAffect(target: PluginActor, name: String): Boolean = TODO()

    override fun applyItemAffect(
      item: PluginItem,
      name: String,
      duration: Int?,
      applies: String,
      statDeltas: Map<String, Int>,
      flags: Set<String>,
      senseDeltas: Map<String, Int>,
      maxVitalDeltas: Map<String, Int>,
      resistDeltas: Map<String, Int>,
    ) = TODO()

    override fun removeItemAffect(item: PluginItem, name: String): Boolean = TODO()

    override fun addTag(item: PluginItem, tag: String): Boolean = TODO()

    override fun removeTag(item: PluginItem, tag: String): Boolean = TODO()

    override fun transform(item: PluginItem, label: String) = TODO()

    override fun setCharges(item: PluginItem, charges: Int) = TODO()

    override fun teleport(actor: PluginActor, room: PluginRoom): String? = TODO()

    override fun wear(actor: PluginActor, item: PluginItem, wield: Boolean): String? = TODO()

    override fun removeWorn(actor: PluginActor, item: PluginItem): String? = TODO()

    override fun mayWear(actor: PluginActor, item: PluginItem, wield: Boolean): String? = TODO()

    override fun mayRemove(actor: PluginActor, item: PluginItem): String? = TODO()

    override fun mayDrop(actor: PluginActor, item: PluginItem): String? = TODO()

    override fun mayGive(actor: PluginActor, item: PluginItem, to: PluginActor): String? = TODO()

    override fun mayPut(actor: PluginActor, item: PluginItem, container: PluginItem): String? =
      TODO()

    override fun mayEnter(actor: PluginActor, room: PluginRoom): String? = TODO()

    override fun mayLearn(actor: PluginActor, skill: String, proficiency: Int): String? = TODO()

    override fun fits(item: PluginItem, to: PluginEntity, count: Int?): Int = TODO()

    override fun fits(label: String, to: PluginEntity, count: Int?): Int = TODO()

    override fun spawnItem(label: String, to: PluginEntity, count: Int): List<PluginItem> = TODO()

    override fun moveItem(item: PluginItem, to: PluginEntity, count: Int?): PluginItem = TODO()

    override fun destroy(item: PluginItem, count: Int?) = TODO()

    override fun mergeStack(item: PluginItem): PluginItem = TODO()

    override fun getAttr(entity: PluginEntity, key: String, default: Any?): Any? =
      attrs[entity]?.getOrElse(key) { default } ?: default

    override fun setAttr(entity: PluginEntity, key: String, value: Any?) {
      attrs.getOrPut(entity) { mutableMapOf() }[key] = value
    }

    override fun delAttr(entity: PluginEntity, key: String) {
      attrs[entity]?.remove(key)
    }

    override val rng: PluginRng
      get() = TODO()

    override fun rooms(): List<PluginRoom> = rooms

    override fun room(label: String): PluginRoom? = TODO()

    override fun diagnose(message: String) = TODO()

    override fun setChoices(actor: PluginActor, changes: Map<String, String?>): String? = TODO()

    override fun grantTrait(actor: PluginActor, trait: String): String? = TODO()

    override fun removeTrait(actor: PluginActor, trait: String): String? = TODO()

    override fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String? = TODO()

    override fun removeSkill(actor: PluginActor, skill: String): Boolean = TODO()

    override fun awardXp(actor: PluginActor, amount: Long) = TODO()

    override fun takeXp(actor: PluginActor, amount: Long): Long = TODO()

    override fun levelUp(actor: PluginActor): Boolean = TODO()

    override fun levelDown(actor: PluginActor, count: Int): Int = TODO()

    override fun disposition(holder: PluginActor, subject: PluginActor): Int = TODO()

    override fun standing(holder: Any, subject: Any): Int = TODO()

    override fun factionsOf(actor: PluginActor): List<String> = TODO()

    override fun raiseStandingEvent(
      affected: PluginActor,
      actor: PluginActor,
      event: String,
      amount: Long,
      item: PluginItem?,
    ) = TODO()

    override fun adjustStanding(holder: Any, subject: Any, amount: Int) = TODO()

    override fun memories(
      mob: PluginActor,
      subject: PluginActor?,
      event: String?,
      since: Long,
    ): List<gups.pluginapi.PluginMemory> = TODO()

    override fun remember(
      mob: PluginActor,
      subject: PluginActor,
      event: String,
      skill: String?,
      amount: Long,
      target: PluginActor?,
    ) = TODO()

    override fun forget(mob: PluginActor, subject: PluginActor?, event: String?): Int = TODO()
  }

  private val registry = FakeRegistry().also { WeatherPlugin().register(it) }
  private val meadow = FakeRoom(listOf("meadow", "valley"))
  private val cabin = FakeRoom(emptyList())

  // ---- Unit tests ---------------------------------------------------------------------------

  @Test
  fun `stateFor is a pure function of seed, pulse window and region`() {
    val w = FakeWorld(seed = 42, pulse = 10)
    val state = WeatherPlugin.stateFor(w, meadow)
    assertTrue(state in listOf("clear", "rain", "storm"))
    assertEquals(
      state,
      WeatherPlugin.stateFor(FakeWorld(seed = 42, pulse = 239), meadow),
      "same one-minute window",
    )
    assertEquals(
      state,
      WeatherPlugin.stateFor(w, FakeRoom(listOf("valley", "meadow"))),
      "the first tag in sorted order",
    )
    assertEquals("clear", WeatherPlugin.stateFor(w, cabin))
    val seen =
      (0L until 50L)
        .map { WeatherPlugin.stateFor(FakeWorld(seed = 42, pulse = it * 240), meadow) }
        .toSet()
    assertEquals(setOf("clear", "rain", "storm"), seen, "windows vary")
    assertEquals(
      state,
      registry.builtins.getValue("now")(w, listOf(meadow), mapOf("room" to meadow)),
    )
  }

  @Test
  fun `a storm breaks a chase, and other weather leaves the answer alone`() {
    val decide = registry.chainThinks.single()
    fun pulseWith(state: String) =
      (0L until 100L)
        .map { it * 240 }
        .first { WeatherPlugin.stateFor(FakeWorld(pulse = it), meadow) == state }
    fun ask(pulse: Long, room: PluginRoom, chained: Boolean) =
      decide(
        gups.pluginapi.PluginChainThinkCall(
          FakeWorld(pulse = pulse),
          FakeActor(room),
          "pursue",
          "east",
          "east",
          chain = 0,
          chained = chained,
        )
      )
    val storm = pulseWith("storm")
    val clear = pulseWith("clear")
    assertEquals(false, ask(storm, meadow, chained = true), "the storm washes the trail out")
    assertEquals(true, ask(clear, meadow, chained = true), "clear weather keeps the chase")
    assertEquals(true, ask(storm, cabin, chained = true), "indoors the storm doesn't reach")
    assertEquals(false, ask(clear, meadow, chained = false), "weather never starts a chain")
  }

  @Test
  fun `the weather command`() {
    val w = FakeWorld()
    val run = registry.commands.getValue("weather")
    run(PluginCommandCall(w, FakeActor(cabin), ""))
    run(PluginCommandCall(w, FakeActor(meadow), ""))
    assertEquals(
      listOf("You are indoors.", "The weather here is ${WeatherPlugin.stateFor(w, meadow)}."),
      w.sent,
    )
    assertEquals(
      listOf<Pair<String, Any?>>(
        "Gups.Forecast" to WeatherPlugin.forecast(w, cabin),
        "Gups.Forecast" to WeatherPlugin.forecast(w, meadow),
      ),
      w.gmcpSent,
    )
  }

  @Test
  fun `the forecast is the weather now and in the next window`() {
    val forecast = WeatherPlugin.forecast(FakeWorld(seed = 42, pulse = 10), meadow)
    assertEquals(WeatherPlugin.stateFor(FakeWorld(seed = 42, pulse = 10), meadow), forecast["now"])
    assertEquals(
      WeatherPlugin.stateFor(FakeWorld(seed = 42, pulse = 250), meadow),
      forecast["next"],
    )
  }

  @Test
  fun `Gups Weather Get sends the forecast or runs the weather command`() {
    val w = FakeWorld()
    val commands = mutableListOf<String>()
    fun call(body: Any?) =
      object : PluginGmcpCall {
        override val world = w
        override val actor = FakeActor(meadow)
        override val pkg = "Gups.Weather.Get"
        override val data = body

        override fun command(line: String): Boolean {
          commands += line
          return true
        }
      }
    val handle = registry.gmcpInputs.getValue("Gups.Weather.Get")
    handle(call(null))
    assertEquals(
      listOf<Pair<String, Any?>>("Gups.Forecast" to WeatherPlugin.forecast(w, meadow)),
      w.gmcpSent,
    )
    assertEquals(emptyList<String>(), commands)
    handle(call(mapOf("describe" to true)))
    assertEquals(listOf("weather"), commands)
    assertEquals(1, w.gmcpSent.size, "the weather command sends the forecast itself")
    assertEquals(setOf("Gups.Forecast"), registry.gmcpEvents.keys)
  }

  @Test
  fun `soak applies soaked, and does nothing without a target`() {
    val w = FakeWorld()
    val (params, soak) = registry.effects.getValue("soak")
    assertEquals(2L, params.single().defaultValue)
    val target = FakeActor(meadow)
    soak.apply(PluginEffectCall(w, FakeActor(cabin), target, mapOf("pulses" to 3L)))
    soak.apply(PluginEffectCall(w, FakeActor(cabin), null, mapOf("pulses" to 3L)))
    assertEquals(listOf(listOf<Any>(target, "soaked", 3, mapOf("dex" to -1))), w.affects)
  }

  @Test
  fun `gmcp and the start log`() {
    val w = FakeWorld(regionList = listOf("meadow", "valley"))
    val produce = registry.gmcp.getValue("Gups.Weather")
    assertEquals(
      mapOf("state" to WeatherPlugin.stateFor(w, meadow), "region" to "meadow"),
      produce(w, FakeActor(meadow)),
    )
    assertEquals(mapOf("state" to "clear", "region" to null), produce(w, FakeActor(cabin)))
    registry.started.single()(w)
    assertEquals(listOf("weather: tracking 2 region(s): meadow, valley"), w.logs)
  }

  // ---- The every handler: storms lock and unlock keyed outdoor doors -------------------------

  /**
   * The first window from the first one on (the every handler first runs at pulse 240) in which the
   * region storms and the next window does not, so the storm is one the handler can lock and one it
   * can unlock.
   */
  private fun stormWindow(world: PluginWorld, room: PluginRoom): Long {
    var k = 1L
    while (
      WeatherPlugin.stateFor(world, room, k * 240) != "storm" ||
        WeatherPlugin.stateFor(world, room, (k + 1) * 240) == "storm"
    ) {
      k++
    }
    return k
  }

  @Test
  fun `the every handler is registered for the weather window`() {
    val every = registry.every.single()
    assertEquals(240, every.pulses)
    assertEquals(
      "Storms slam and lock outdoor doors; they unlock when the storm passes.",
      every.doc,
    )
  }

  @Test
  fun `a storm slams a keyed open door and locks it`() {
    val door = Door(":cabin_key", "open")
    val field: PluginRoom =
      FakeRoom(
        listOf("meadow"),
        "//zones/valley:field",
        "Field",
        mapOf("south" to ":cabin"),
        mapOf("south" to door),
      )
    val cabin =
      FakeRoom(
        emptyList(),
        "//zones/valley:cabin",
        "Cabin",
        mapOf("north" to ":field"),
        mapOf("north" to door),
      )
    val world = FakeWorld(seed = 0, rooms = listOf(field, cabin))
    world.pulse = stormWindow(world, field) * 240
    registry.every.single().fn(PluginTickCall(world, emptyList()))
    assertEquals("locked", field.exit("south")!!.door!!.state)
    assertEquals(listOf(field to "The storm slams the door to the south shut."), world.echoes)
    assertEquals(listOf("south"), world.getAttr(field, "locked"))
  }

  @Test
  fun `a keyed closed door is locked silently`() {
    val door = Door(":cabin_key", "closed")
    val field =
      FakeRoom(
        listOf("meadow"),
        "//zones/valley:field",
        "Field",
        mapOf("south" to ":cabin"),
        mapOf("south" to door),
      )
    val world = FakeWorld(seed = 0, rooms = listOf(field))
    world.pulse = stormWindow(world, field) * 240
    registry.every.single().fn(PluginTickCall(world, emptyList()))
    assertEquals("locked", field.exit("south")!!.door!!.state)
    assertEquals(
      emptyList<Pair<PluginRoom, String>>(),
      world.echoes,
      "a closed door is locked without a slam",
    )
    assertEquals(listOf("south"), world.getAttr(field, "locked"))
  }

  @Test
  fun `unkeyed and already-locked doors are left alone`() {
    val keyed = Door(":key", "open")
    val unkeyed = Door(null, "open")
    val preLocked = Door(":other_key", "locked")
    val field: PluginRoom =
      FakeRoom(
        listOf("meadow"),
        "//zones/valley:field",
        "Field",
        mapOf("south" to ":a", "east" to ":b", "north" to ":c"),
        mapOf("south" to keyed, "east" to unkeyed, "north" to preLocked),
      )
    val world = FakeWorld(seed = 0, rooms = listOf(field))
    world.pulse = stormWindow(world, field) * 240
    registry.every.single().fn(PluginTickCall(world, emptyList()))
    assertEquals("open", field.exit("east")!!.door!!.state, "the unkeyed door has no lock")
    assertEquals("locked", field.exit("north")!!.door!!.state, "the pre-locked door is left alone")
    assertEquals("locked", field.exit("south")!!.door!!.state, "the keyed door is locked")
    assertEquals(
      listOf("south"),
      world.getAttr(field, "locked"),
      "only the plugin's door is listed",
    )
    assertEquals(
      listOf(field to "The storm slams the door to the south shut."),
      world.echoes,
      "only the open keyed door slams",
    )
  }

  @Test
  fun `the storm unlocks only the doors it locked, when it passes`() {
    val pluginDoor = Door(":plugin_key", "open")
    val playerDoor = Door(":player_key", "locked")
    val field =
      FakeRoom(
        listOf("meadow"),
        "//zones/valley:field",
        "Field",
        mapOf("south" to ":a", "east" to ":b"),
        mapOf("south" to pluginDoor, "east" to playerDoor),
      )
    val world = FakeWorld(seed = 0, rooms = listOf(field))
    val k = stormWindow(world, field)
    world.pulse = k * 240
    registry.every.single().fn(PluginTickCall(world, emptyList()))
    assertEquals("locked", field.exit("south")!!.door!!.state, "the plugin locked its door")
    assertEquals(
      "locked",
      field.exit("east")!!.door!!.state,
      "the player's lock holds through the storm",
    )
    // A stray entry someone else wrote into the list is skipped, not fatal.
    world.setAttr(field, "locked", listOf(42L) + world.getAttr(field, "locked") as List<*>)
    world.pulse = (k + 1) * 240
    registry.every.single().fn(PluginTickCall(world, emptyList()))
    assertEquals("closed", field.exit("south")!!.door!!.state, "the plugin unlocked its own door")
    assertEquals("locked", field.exit("east")!!.door!!.state, "the player's door stays locked")
    assertNull(world.getAttr(field, "locked"))
  }

  @Test
  fun `an indoor room is never touched`() {
    val indoorDoor = Door(":key", "open")
    val outdoorDoor = Door(":other", "open")
    val cabin =
      FakeRoom(
        emptyList(),
        "//zones/valley:cabin",
        "Cabin",
        mapOf("east" to ":den"),
        mapOf("east" to indoorDoor),
      )
    val field =
      FakeRoom(
        listOf("meadow"),
        "//zones/valley:field",
        "Field",
        mapOf("south" to ":cabin"),
        mapOf("south" to outdoorDoor),
      )
    val world = FakeWorld(seed = 0, rooms = listOf(cabin, field))
    world.pulse = stormWindow(world, field) * 240
    registry.every.single().fn(PluginTickCall(world, emptyList()))
    assertEquals("open", cabin.exit("east")!!.door!!.state, "the indoor door is untouched")
    assertEquals("locked", field.exit("south")!!.door!!.state, "the outdoor door is locked")
    assertEquals(emptyList<Pair<String, String>>(), world.unlockedDoors)
    assertEquals(
      emptyList<Pair<PluginRoom, String>>(),
      world.echoes.filter { it.first === cabin },
      "the indoor room never slams",
    )
  }

  // ---- End to end: the built weather.jar through gups test's TestRunner -----------------------

  private fun write(root: Path, path: String, text: String) {
    val p = root.resolve(path)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }

  /** The valley zone: a cabin and a field behind a keyed door, and its test cases. */
  private fun writeValleyWorld(tmp: Path, lockPulse: Long) {
    write(tmp, "zones/valley/package.star", """package(api_version = 1, kind = "zone")""")
    write(
      tmp,
      "zones/valley/items.star",
      """
            item("cabin_key", name = "a cabin key", desc = "A key to the cabin door.")
            """,
    )
    write(
      tmp,
      "zones/valley/rooms.star",
      """
            def _sky(ctx):
                ctx.send(ctx.actor, "The sky is " + weather.now(ctx.room) + ".")

            room("cabin", name = "Cabin", desc = "A snug cabin.", exits = [exit("north", ":field", door = door(state = DOOR.open, key = ":cabin_key"))])
            room("field", name = "Field", desc = "An open field.", regions = ["meadow"], exits = [exit("south", ":cabin", door = door(state = DOOR.open, key = ":cabin_key"))], on_enter = _sky)
            skill("rain_call", kind = "spell", target = "self", effect = weather.soak(pulses = 3))
            """,
    )
    write(
      tmp,
      "zones/valley/valley_test.star",
      """
            def test_the_sky_shows_on_entering_the_field(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":cabin")
                t.contains(p.do("north"), "The sky is ")

            def test_the_weather_command(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":cabin")
                t.contains(p.do("weather"), "You are indoors.")
                p.do("north")
                t.contains(p.do("weather"), "The weather here is ")

            def test_rain_call_soaks_the_caster(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":field", skills = {":rain_call": 100})
                p.do("cast rain_call")
                w.run(pulses = 2)
                t.true("soaked" in [a.name for a in p.affects])

            def test_a_storm_locks_the_field_door(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":field")
                w.run(pulses = $lockPulse)
                t.contains(p.do("open south"), "The door south is locked.")
            """,
    )
  }

  @Test
  fun `the built jar runs through TestRunner`() {
    val tmp = TempFiles.dir("gups-weather")
    val jar = tmp.resolve("plugins/weather.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.weatherJar")).copyTo(jar)
    val schema = Path.of(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText()
    write(
      tmp,
      "MODULE.gups",
      schema.replaceFirst(
        "module(",
        "module(\n    plugins = {\"weather\": \"${PluginLoader.sha256(jar)}\"},",
      ),
    )
    val rules = Path.of(System.getProperty("gups.templateWorld")).resolve("rules")
    Files.list(rules)
      .use { s -> s.toList() }
      .forEach { it.copyTo(tmp.resolve("rules").createDirectories().resolve(it.fileName)) }
    gups.script.testing.TestWorlds.makeSpellsLand(tmp.resolve("rules"))
    // The harness world runs with seed 0 and the field's region is "meadow". The every(240)
    // handler first runs at pulse 240, so the first storm it can lock is the first storm window
    // from window 1 on; one extra pulse lands past the boundary that locks the door.
    val lockPulse = stormWindow(FakeWorld(seed = 0), FakeRoom(listOf("meadow"))) * 240 + 1
    writeValleyWorld(tmp, lockPulse)
    val logs = mutableListOf<String>()
    val dir = WorldDir(tmp)
    val (module, error) = WorldModule.read(dir)
    assertNull(error)
    PluginLoader.load(dir, module, log = { synchronized(logs) { logs += it } }).use { host ->
      assertEquals(emptyList(), host.warnings)
      val report = TestRunner(dir, plugins = host).run(listOf("//zones/valley"))
      assertEquals(emptyList(), report.problems)
      assertEquals(4, report.results.size, report.results.joinToString("\n"))
      for (r in report.results) assertEquals(Status.PASS, r.status, "${r.name}: ${r.message}")
    }
    assertContains(logs, "plugin 'weather': weather: tracking 1 region(s): meadow")
  }
}
