package gups.sample.heritage

import gups.core.ClientInputEvent
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.world.DevLogin
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.handle
import gups.harness.Status
import gups.harness.TestRunner
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginEntity
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
import gups.pluginapi.PluginSkill
import gups.pluginapi.PluginStepReply
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld
import gups.pluginapi.host.PluginLoader
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldMessages
import gups.script.WorldModule
import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeritagePluginTest {

  private class FakeRegistry : PluginRegistry {
    val traits = mutableMapOf<String, PluginTrait>()
    private var _creation: PluginCreation? = null
    val creation: PluginCreation?
      get() = _creation

    val restrictions =
      mutableMapOf<
        PluginRestrictionPoint,
        MutableList<Pair<String, (PluginRestrictionCall) -> String?>>,
      >()
    val builtins = mutableMapOf<String, (PluginWorld, List<Any?>, Map<String, Any?>) -> Any?>()
    val commands = mutableMapOf<String, (PluginCommandCall) -> Unit>()

    override fun addTrait(trait: PluginTrait) {
      traits[trait.name] = trait
    }

    override fun setCreation(creation: PluginCreation) {
      this._creation = creation
    }

    override fun addRestriction(
      point: PluginRestrictionPoint,
      doc: String,
      check: (PluginRestrictionCall) -> String?,
    ) {
      restrictions.getOrPut(point) { mutableListOf() }.add(doc to check)
    }

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
    ) = TODO()

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

    override fun addHook(
      name: String,
      doc: String,
      handle: (gups.pluginapi.PluginHookCall) -> Any?,
    ) = TODO()

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

    override fun addBuiltin(
      name: String,
      value: net.starlark.java.eval.StarlarkValue,
      doc: String,
    ) = TODO()

    override fun addEffectPrimitive(
      name: String,
      doc: String,
      params: List<PluginParam>,
      apply: gups.pluginapi.PluginEffect,
    ) = TODO()

    override fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?) =
      TODO()

    override fun addGmcpEvent(pkg: String, doc: String) = TODO()

    override fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit) = TODO()

    override fun onWorldStarted(fn: (PluginWorld) -> Unit) = TODO()
  }

  private class FakeActor(
    override val name: String = "Tester",
    override val isPlayer: Boolean = true,
    override val role: PluginRole = PluginRole.PLAYER,
    val traitSet: Set<String> = emptySet(),
    override val choices: Map<String, String> = emptyMap(),
    override val room: PluginRoom = FakeRoom(),
    val statValue: Int = 10,
  ) : PluginActor {
    override val id = 100L
    override val label: String? = null
    override val flags: Set<String> = emptySet()
    override val traits: List<String> = traitSet.sorted()
    override val grantedTraits: Set<String> = emptySet()
    override val skills: Map<String, Int> = emptyMap()

    override fun hasTrait(name: String) = name in traitSet

    override fun stat(name: String) = statValue

    override fun baseStat(name: String) = 10

    override fun vital(name: String) = 20

    override fun maxVital(name: String) = 20

    override fun baseMaxVital(name: String) = 20

    override fun resist(type: String) = 0

    override fun baseResist(type: String) = 0

    override val level = 1
    override val pendingLevels = 0
    override val affects = emptyList<String>()

    override fun hasAffect(name: String) = false
  }

  private class FakeRoom(override val tags: List<String> = emptyList()) : PluginRoom {
    override val id = 1L
    override val label = "//zones/test:room"
    override val name = "Room"
    override val regions = emptyList<String>()
    override val flags = emptySet<String>()
    override val size = "normal"
    override val exits = emptyMap<String, String>()
    override val actors = emptyList<PluginActor>()
    override val items = emptyList<PluginItem>()
  }

  private class FakeAttempt(
    override val name: String = "Tester",
    override val answers: Map<String, String> = emptyMap(),
    override val seed: Long = 12345L,
    override val state: MutableMap<String, Any?> = mutableMapOf(),
    override val preview: PluginActor = FakeActor(),
  ) : gups.pluginapi.PluginCreationAttempt {
    override fun rng(stream: Int): PluginRng = TODO()
  }

  private class FakeItem(override val tags: List<String> = emptyList()) : PluginItem {
    override val id = 50L
    override val label = "//zones/test:item"
    override val name = "Item"
    override val keywords = listOf("item")
    override val kind = "armor"
    override val wear = "body"
    override val slotCost = 1
    override val weight = 10
    override val value = 0
    override val maxVitalDeltas = emptyMap<String, Int>()
    override val resistDeltas = emptyMap<String, Int>()
    override val inactive = false
    override val plural: String? = null
    override val charges: Int? = null
    override val maxCharges: Int? = null

    override fun hasTag(tag: String): Boolean = tag in tags

    override val variants: Map<String, String> = emptyMap()
    override val affects: List<gups.pluginapi.PluginItemAffect> = emptyList()
    override val contents: List<PluginItem> = emptyList()
    override val custodian: PluginActor? = null
  }

  private class FakeSkill(override val tags: List<String> = emptyList()) : PluginSkill {
    override val label = "//skills:test"
    override val name = "Test"
    override val kind = "spell"
  }

  private class FakeWorld : PluginWorld {
    override val pulse = 0L
    override val seed = 42L
    override val rng: PluginRng
      get() = TODO()

    override val schema: PluginSchema
      get() = TODO()

    val sent = mutableListOf<String>()
    val setChoicesCalls = mutableListOf<Pair<PluginActor, Map<String, String?>>>()
    val setBaseStatCalls = mutableListOf<Triple<PluginActor, String, Int>>()

    override fun send(target: PluginActor, text: String) {
      sent += text
    }

    override fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean = TODO()

    override fun setChoices(actor: PluginActor, changes: Map<String, String?>): String? {
      setChoicesCalls += actor to changes
      return null
    }

    override fun setBaseStat(actor: PluginActor, stat: String, value: Int): String? {
      setBaseStatCalls += Triple(actor, stat, value)
      return null
    }

    override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) = TODO()

    override fun damage(
      target: PluginActor,
      amount: Int,
      type: String,
      vital: String,
      source: PluginActor?,
    ) = TODO()

    override fun heal(target: PluginActor, amount: Int, vital: String) = TODO()

    override fun applyAffect(
      target: PluginActor,
      name: String,
      duration: Int,
      statDeltas: Map<String, Int>,
      flags: Set<String>,
      maxVitalDeltas: Map<String, Int>,
      resistDeltas: Map<String, Int>,
    ) = TODO()

    override fun removeAffect(target: PluginActor, name: String) = TODO()

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
    ): Unit = TODO("not used")

    override fun removeItemAffect(item: PluginItem, name: String): Boolean = TODO("not used")

    override fun addTag(item: PluginItem, tag: String): Boolean = TODO("not used")

    override fun removeTag(item: PluginItem, tag: String): Boolean = TODO("not used")

    override fun transform(item: PluginItem, label: String): Unit = TODO("not used")

    override fun setCharges(item: PluginItem, charges: Int): Unit = TODO("not used")

    override fun teleport(actor: PluginActor, room: PluginRoom) = TODO()

    override fun getAttr(entity: PluginEntity, key: String, default: Any?) = TODO()

    override fun setAttr(entity: PluginEntity, key: String, value: Any?) = TODO()

    override fun delAttr(entity: PluginEntity, key: String) = TODO()

    override fun grantTrait(actor: PluginActor, trait: String): String? = TODO()

    override fun removeTrait(actor: PluginActor, trait: String): String? = TODO()

    override fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String? = null

    override fun removeSkill(actor: PluginActor, skill: String) = TODO()

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

    override fun rooms() = emptyList<PluginRoom>()

    override fun room(label: String): PluginRoom? = null

    override fun regions() = emptyList<String>()

    override fun diagnose(message: String) {}

    override fun log(message: String) {}
  }

  // ---- Unit Tests ---------------------------------------------------------------------------

  @Test
  fun `unit - plugin registers all traits, choices and restrictions`() {
    val plugin = HeritagePlugin()
    val reg = FakeRegistry()
    plugin.register(reg)

    assertEquals(setOf("human", "elf", "dwarf", "warrior", "mage", "cleric"), reg.traits.keys)
    val human = reg.traits.getValue("human")
    assertEquals(
      mapOf("str" to 1, "dex" to 1, "con" to 1, "int" to 1, "wis" to 1),
      human.statDeltas,
    )

    val elf = reg.traits.getValue("elf")
    assertEquals(mapOf("dex" to 2, "con" to -1), elf.statDeltas)
    assertEquals(mapOf("mana" to 10), elf.maxVitalDeltas)
    val dodge = elf.modifiers[gups.pluginapi.PluginModifierPoint.DEFEND_HIT]
    assertNotNull(dodge)
    // The bearer is the defender: its dex (16) counts, not the attacker's (4).
    val dodged =
      dodge.modify(
        gups.pluginapi.PluginModifierCall(
          world = FakeWorld(),
          point = gups.pluginapi.PluginModifierPoint.DEFEND_HIT,
          actor = FakeActor(statValue = 16),
          other = FakeActor(statValue = 4),
          weapon = null,
          room = FakeRoom(),
        ),
        60L,
      )
    assertEquals(52L, dodged)

    val dwarf = reg.traits.getValue("dwarf")
    assertEquals(mapOf("con" to 2), dwarf.statDeltas)
    assertEquals(mapOf("hp" to 5), dwarf.maxVitalDeltas)
    assertEquals(mapOf("fire" to 10), dwarf.resistDeltas)

    val warrior = reg.traits.getValue("warrior")
    assertEquals(mapOf("str" to 2), warrior.statDeltas)
    assertEquals(mapOf("hp" to 10), warrior.maxVitalDeltas)

    val mage = reg.traits.getValue("mage")
    assertEquals(mapOf("int" to 2), mage.statDeltas)
    assertEquals(mapOf("mana" to 20), mage.maxVitalDeltas)

    val cleric = reg.traits.getValue("cleric")
    assertEquals(mapOf("wis" to 2), cleric.statDeltas)
    assertEquals(mapOf("mana" to 10), cleric.maxVitalDeltas)
    val regenMod = cleric.modifiers[gups.pluginapi.PluginModifierPoint.REGEN]
    assertNotNull(regenMod)
    val fakeWorld = FakeWorld()
    val modifiedRegen =
      regenMod.modify(
        gups.pluginapi.PluginModifierCall(
          world = fakeWorld,
          point = gups.pluginapi.PluginModifierPoint.REGEN,
          actor = FakeActor(),
          other = null,
          weapon = null,
          room = FakeRoom(),
        ),
        mapOf("hp" to 1L, "mana" to 2L),
      )
    assertEquals(mapOf("hp" to 1L, "mana" to 3L), modifiedRegen)

    val creation = reg.creation
    assertNotNull(creation)
    assertEquals(listOf("human", "elf", "dwarf"), creation.available(fakeWorld, "race", emptyMap()))
    assertEquals(
      listOf("warrior", "mage", "cleric"),
      creation.available(fakeWorld, "class", mapOf("race" to "human")),
    )
    assertEquals(
      listOf("warrior", "cleric"),
      creation.available(fakeWorld, "class", mapOf("race" to "dwarf")),
    )

    assertNull(creation.validate(fakeWorld, mapOf("race" to "dwarf", "class" to "warrior")))
    assertEquals(
      "Dwarves cannot be mages.",
      creation.validate(fakeWorld, mapOf("race" to "dwarf", "class" to "mage")),
    )

    // Test restrictions
    val wearCheck = reg.restrictions.getValue(PluginRestrictionPoint.WEAR).single().second
    val mageActor = FakeActor(traitSet = setOf("mage"))
    val nonMage = FakeActor(traitSet = setOf("warrior"))
    val heavyArmor = FakeItem(tags = listOf("heavy_armor"))
    val lightArmor = FakeItem(tags = listOf("light_armor"))

    assertEquals(
      "Your spellcraft can't work through that much metal.",
      wearCheck(
        PluginRestrictionCall(fakeWorld, PluginRestrictionPoint.WEAR, mageActor, item = heavyArmor)
      ),
    )
    assertNull(
      wearCheck(
        PluginRestrictionCall(fakeWorld, PluginRestrictionPoint.WEAR, mageActor, item = lightArmor)
      )
    )
    assertNull(
      wearCheck(
        PluginRestrictionCall(fakeWorld, PluginRestrictionPoint.WEAR, nonMage, item = heavyArmor)
      )
    )

    val castCheck = reg.restrictions.getValue(PluginRestrictionPoint.CAST).single().second
    val arcaneSkill = FakeSkill(tags = listOf("arcane"))
    val divineSkill = FakeSkill(tags = listOf("divine"))
    val clericActor = FakeActor(traitSet = setOf("cleric"))

    assertNull(
      castCheck(
        PluginRestrictionCall(
          fakeWorld,
          PluginRestrictionPoint.CAST,
          mageActor,
          skill = arcaneSkill,
        )
      )
    )
    assertEquals(
      "You don't know how to call on that power.",
      castCheck(
        PluginRestrictionCall(fakeWorld, PluginRestrictionPoint.CAST, nonMage, skill = arcaneSkill)
      ),
    )
    assertNull(
      castCheck(
        PluginRestrictionCall(
          fakeWorld,
          PluginRestrictionPoint.CAST,
          clericActor,
          skill = divineSkill,
        )
      )
    )
    assertEquals(
      "You don't know how to call on that power.",
      castCheck(
        PluginRestrictionCall(
          fakeWorld,
          PluginRestrictionPoint.CAST,
          mageActor,
          skill = divineSkill,
        )
      ),
    )

    val enterCheck = reg.restrictions.getValue(PluginRestrictionPoint.ENTER).single().second
    val guildMageRoom = FakeRoom(tags = listOf("guild_mage"))
    assertNull(
      enterCheck(
        PluginRestrictionCall(
          fakeWorld,
          PluginRestrictionPoint.ENTER,
          mageActor,
          room = guildMageRoom,
        )
      )
    )
    assertEquals(
      "Only Mages may enter.",
      enterCheck(
        PluginRestrictionCall(
          fakeWorld,
          PluginRestrictionPoint.ENTER,
          nonMage,
          room = guildMageRoom,
        )
      ),
    )

    val builderActor = FakeActor(role = PluginRole.BUILDER, traitSet = setOf("warrior"))
    assertNull(
      enterCheck(
        PluginRestrictionCall(
          fakeWorld,
          PluginRestrictionPoint.ENTER,
          builderActor,
          room = guildMageRoom,
        )
      )
    )

    // Command heritage
    val heritageCmd = reg.commands.getValue("heritage")
    val p1 = FakeActor(choices = mapOf("race" to "elf", "class" to "mage"))
    heritageCmd(PluginCommandCall(fakeWorld, p1, ""))
    assertEquals(
      listOf(
        "Race: Elf - Graceful and attuned to magic.\nClass: Mage - Scholars of arcane destruction."
      ),
      fakeWorld.sent,
    )
  }

  // ---- End to end fixture tests -------------------------------------------------------------

  private fun write(root: Path, path: String, text: String) {
    val p = root.resolve(path)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }

  @Test
  fun `the built heritage jar runs through TestRunner in fixture world`() {
    val tmp = Files.createTempDirectory("gups-heritage")
    val jar = tmp.resolve("plugins/heritage.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.heritageJar")).copyTo(jar)
    val schema = Path.of(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText()
    write(
      tmp,
      "MODULE.gups",
      schema.replaceFirst(
        "module(",
        "module(\n    plugins = {\"heritage\": \"${PluginLoader.sha256(jar)}\"},",
      ),
    )
    val rules = Path.of(System.getProperty("gups.templateWorld")).resolve("rules")
    Files.list(rules)
      .use { s -> s.toList() }
      .forEach { it.copyTo(tmp.resolve("rules").createDirectories().resolve(it.fileName)) }
    TestWorlds.makeSpellsLand(tmp.resolve("rules"))

    write(
      tmp,
      "rules/respec.star",
      """
      def _respec(ctx):
          heritage.choose(ctx.actor, "class", "cleric")

      global_command("respec", _respec)
      """,
    )

    write(tmp, "skills/package.star", """package(api_version = 1, kind = "skills")""")
    write(
      tmp,
      "skills/skills.star",
      """
      skill("magic_missile", kind = "spell", target = "enemy", tags = ["arcane"])
      skill("minor_heal", kind = "spell", target = "self", tags = ["divine"])
      """,
    )

    write(tmp, "zones/heritage_zone/package.star", """package(api_version = 1, kind = "zone")""")
    write(
      tmp,
      "zones/heritage_zone/content.star",
      """
      room("square", name = "Town Square", desc = "Central square.", exits = [exit("north", ":guild_mage")])
      room("guild_mage", name = "Mages Guild", desc = "A hall of magic.", tags = ["guild_mage"], exits = [exit("south", ":square")])

      item("heavy_plate", name = "Heavy Plate", desc = "Metal plate.", wear = "body", tags = ["heavy_armor"])
      item("cloth_robe", name = "Cloth Robe", desc = "A simple robe.", wear = "body")

      mob("mage_trainer", name = "Mage Trainer", desc = "A wise mage.", traits = ["mage"])
      mob("warrior_guard", name = "Warrior Guard", desc = "A tough guard.", traits = ["warrior"])
      """,
    )
    write(
      tmp,
      "zones/heritage_zone/heritage_test.star",
      """
      def test_mage_restrictions_and_skills(t):
          w = t.world(packages = ["//skills", "//zones/heritage_zone"])
          # Elf mage
          p = w.spawn_player("Ann", at = ":square", choices = {"race": "elf", "class": "mage"})
          t.eq(p.choices["race"], "elf")
          t.eq(p.choices["class"], "mage")
          t.true(p.has_trait("elf"))
          t.true(p.has_trait("mage"))
          # Starting skill granted at 25
          t.eq(p.skills["//skills:magic_missile"], 25)

          # WEAR restriction
          w.spawn(":heavy_plate", at = p)
          w.spawn(":cloth_robe", at = p)
          out = p.do("wear plate")
          t.contains(out, "Your spellcraft can't work through that much metal.")
          t.eq(p.equipped("body"), None)
          p.do("wear robe")
          t.ne(p.equipped("body"), None)

          # ENTER restriction
          out = p.do("north")
          t.contains(out, "Mages Guild")
          t.eq(p.room, ":guild_mage")

      def test_warrior_cannot_enter_mage_guild(t):
          w = t.world()
          p = w.spawn_player("Bob", at = ":square", choices = {"race": "human", "class": "warrior"})
          out = p.do("north")
          t.contains(out, "Only Mages may enter.")
          t.eq(p.room, ":square")

      def test_mob_mage_and_warrior_enter(t):
          w = t.world()
          # Mobs carry prototype traits
          trainer = w.spawn(":mage_trainer", at = ":square")
          guard = w.spawn(":warrior_guard", at = ":square")
          t.true(trainer.has_trait("mage"))
          t.true(guard.has_trait("warrior"))

      def test_heritage_choose_repick(t):
          w = t.world(packages = ["//skills", "//zones/heritage_zone"])
          p = w.spawn_player("Clara", at = ":square", choices = {"race": "human", "class": "warrior"})
          t.true(p.has_trait("warrior"))
          # Builtin choose called via in-game command
          p.do("respec")
          t.true(p.has_trait("cleric"))
          t.false(p.has_trait("warrior"))
          # Also test w.set_choices
          w.set_choices(p, {"class": "warrior"})
          t.true(p.has_trait("warrior"))
          t.false(p.has_trait("cleric"))
      """,
    )

    val dir = WorldDir(tmp)
    val (module, error) = WorldModule.read(dir)
    assertNull(error)
    PluginLoader.load(dir, module).use { host ->
      assertEquals(emptyList(), host.warnings)
      val report = TestRunner(dir, plugins = host).run(listOf("//zones/heritage_zone"))
      assertEquals(emptyList(), report.problems)
      assertEquals(4, report.results.size, report.results.joinToString("\n"))
      for (r in report.results) assertEquals(Status.PASS, r.status, "${r.name}: ${r.message}")
    }
  }

  // ---- Telnet Creation Flow Test ------------------------------------------------------------

  private class PromptSink : OutputSink {
    val all = StringBuilder()
    var closed: String? = null

    override fun send(text: String) {
      all.append(text).append("\n")
    }

    override fun prompt(text: String) {
      all.append("[$text]")
    }

    override fun close(reason: String) {
      closed = reason
    }
  }

  @Test
  fun `telnet creation flow creates character with choices`() {
    val tmp = Files.createTempDirectory("gups-heritage-telnet")
    val jar = tmp.resolve("plugins/heritage.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.heritageJar")).copyTo(jar)
    val schema = Path.of(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText()
    write(
      tmp,
      "MODULE.gups",
      schema.replaceFirst(
        "module(",
        "module(\n    plugins = {\"heritage\": \"${PluginLoader.sha256(jar)}\"},",
      ),
    )
    val rules = Path.of(System.getProperty("gups.templateWorld")).resolve("rules")
    Files.list(rules)
      .use { s -> s.toList() }
      .forEach { it.copyTo(tmp.resolve("rules").createDirectories().resolve(it.fileName)) }
    TestWorlds.makeSpellsLand(tmp.resolve("rules"))

    write(tmp, "zones/start/package.star", """package(api_version = 1, kind = "zone")""")
    write(
      tmp,
      "zones/start/rooms.star",
      """
      room("hall", name = "The Hall", desc = "A spacious entrance hall.")
      """,
    )

    val dir = WorldDir(tmp)
    val (module, modErr) = WorldModule.read(dir)
    assertNull(modErr)
    val loader = ScriptLoader(dir, ScriptLimits.HARNESS)
    val loadRes = loader.load(dir.packages())
    assertTrue(loadRes.ok, loadRes.errors.joinToString())
    val host = StarlarkHost(loadRes, ScriptLimits.HARNESS, loader)
    val world =
      World(
        loadRes.prototypes(),
        loader.schema,
        host.rules(),
        host,
        module.config(WorldConfig(seed = 42, bubble = false), WorldMessages.read(dir).first),
      )
    val pluginHost = PluginLoader.load(dir, module)
    pluginHost.install(world)
    host.install(world)
    world.start()
    world.login = DevLogin(gups.core.Label("//zones/start:hall"))

    val sink = PromptSink()
    val sess = SessionId(10)
    world.handle(WorldControlEvent.Connect(sess, sink, "127.0.0.1", "telnet"))

    assertContains(sink.all, "[By what name do you wish to be known? ]")
    world.handle(ClientInputEvent(sess, "Eldrin", 0))

    // Prompts for race
    assertContains(sink.all, "Choose your race:")
    assertContains(sink.all, "1. Human")
    assertContains(sink.all, "2. Elf")
    assertContains(sink.all, "3. Dwarf")

    world.handle(ClientInputEvent(sess, "elf", 0))

    // Prompts for class
    assertContains(sink.all, "Choose your class:")
    assertContains(sink.all, "1. Warrior")
    assertContains(sink.all, "2. Mage")
    assertContains(sink.all, "3. Cleric")

    world.handle(ClientInputEvent(sess, "mage", 0))

    // Prompts for stats step
    assertContains(sink.all, "Str 10 → 10 (+0)")
    assertContains(sink.all, "Points left: 6.")
    world.handle(ClientInputEvent(sess, "done", 0))

    // Prompts for confirmation
    assertContains(sink.all, "Create this character? (yes, back or cancel)")
    assertContains(sink.all, "Stats Str 10 Dex 10 Con 10 Int 10 Wis 10 Cha 10")
    world.handle(ClientInputEvent(sess, "yes", 0))

    // Player enters
    val player = world.players.singleOrNull()
    assertNotNull(player)
    assertEquals("Eldrin", player.name)
    assertEquals("elf", player.choices["race"])
    assertEquals("mage", player.choices["class"])
    assertTrue(player.hasTrait("elf"))
    assertTrue(player.hasTrait("mage"))
    assertContains(sink.all, "Welcome, Eldrin.")
  }

  @Test
  fun `stats step parsing, adjustments, limits, reset, done, summary and prepare`() {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)
    val creation = reg.creation!!
    val step = creation.steps.single() as StatsStep
    val fw = FakeWorld()

    val attempt = FakeAttempt()

    // initial show
    val initialView = step.show(fw, attempt)
    assertContains(initialView.text, "Str 10 → 10 (+0)")
    assertContains(initialView.text, "Points left: 6.")

    // parsing str +2
    val r1 = step.input(fw, attempt, "str +2")
    assertTrue(r1 is PluginStepReply.Stay)
    assertNull(r1.message)
    assertEquals(mapOf("str" to 2), attempt.state["spend"])

    // parsing case-insensitive STR+1
    val r2 = step.input(fw, attempt, "STR+1")
    assertTrue(r2 is PluginStepReply.Stay)
    assertEquals(mapOf("str" to 3), attempt.state["spend"])

    // prefix co +1
    val r3 = step.input(fw, attempt, "co +1")
    assertTrue(r3 is PluginStepReply.Stay)
    assertEquals(mapOf("str" to 3, "con" to 1), attempt.state["spend"])

    // refund wis -1
    val r4 = step.input(fw, attempt, "wis -1")
    assertTrue(r4 is PluginStepReply.Stay)
    assertEquals(mapOf("str" to 3, "con" to 1, "wis" to -1), attempt.state["spend"])

    // overspending refused (spent 3, budget 6, 3 left; dex +4 exceeds budget)
    val rOver = step.input(fw, attempt, "dex +4")
    assertTrue(rOver is PluginStepReply.Stay)
    assertContains(rOver.message.orEmpty(), "Not enough points")

    // cap refused: str is +3; cap is 4; +2 would be +5 > CAP
    val rCap = step.input(fw, attempt, "str +2")
    assertTrue(rCap is PluginStepReply.Stay)
    assertContains(rCap.message.orEmpty(), "Cannot raise str above +4")

    // floor refused: wis is -1; floor is -2; -2 would be -3 < FLOOR
    val rFloor = step.input(fw, attempt, "wis -2")
    assertTrue(rFloor is PluginStepReply.Stay)
    assertContains(rFloor.message.orEmpty(), "Cannot lower wis below -2")

    // summary text
    assertEquals("Str 13 Dex 10 Con 11 Int 10 Wis 9 Cha 10", step.summary(fw, attempt))

    // reset
    val rReset = step.input(fw, attempt, "reset")
    assertTrue(rReset is PluginStepReply.Stay)
    assertTrue(attempt.state["spend"] == null || (attempt.state["spend"] as Map<*, *>).isEmpty())

    // re-add some points and done
    step.input(fw, attempt, "str +2")
    step.input(fw, attempt, "int +3")
    val rDone = step.input(fw, attempt, "done")
    assertEquals(PluginStepReply.Done, rDone)

    // prepare calls setBaseStat with the right values
    val actor = FakeActor(statValue = 10)
    creation.prepare(fw, actor, attempt)
    assertEquals(
      listOf<Triple<PluginActor, String, Int>>(Triple(actor, "str", 12), Triple(actor, "int", 13)),
      fw.setBaseStatCalls,
    )
  }
}
