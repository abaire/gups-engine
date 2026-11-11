package gups.sample.heritage

import gups.core.ClientInputEvent
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.fixtures.TempFiles
import gups.core.world.DevLogin
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.handle
import gups.harness.Status
import gups.harness.TestRunner
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginRestrictionCall
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginStepReply
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

  // ---- Unit Tests ---------------------------------------------------------------------------

  @Test
  fun `unit - every trait registers its stats, vitals, resistances and modifiers`() {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)

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
    val modifiedRegen =
      regenMod.modify(
        gups.pluginapi.PluginModifierCall(
          world = FakeWorld(),
          point = gups.pluginapi.PluginModifierPoint.REGEN,
          actor = FakeActor(),
          other = null,
          weapon = null,
          room = FakeRoom(),
        ),
        mapOf("hp" to 1L, "mana" to 2L),
      )
    assertEquals(mapOf("hp" to 1L, "mana" to 3L), modifiedRegen)
  }

  @Test
  fun `unit - creation offers the right choices and rejects invalid race-class combos`() {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)
    val fakeWorld = FakeWorld()
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
  }

  @Test
  fun `unit - the wear restriction blocks heavy armor on mages`() {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)
    val fakeWorld = FakeWorld()
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
  }

  @Test
  fun `unit - the cast restriction blocks a skill a trait cannot call`() {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)
    val fakeWorld = FakeWorld()
    val castCheck = reg.restrictions.getValue(PluginRestrictionPoint.CAST).single().second
    val mageActor = FakeActor(traitSet = setOf("mage"))
    val nonMage = FakeActor(traitSet = setOf("warrior"))
    val clericActor = FakeActor(traitSet = setOf("cleric"))
    val arcaneSkill = FakeSkill(tags = listOf("arcane"))
    val divineSkill = FakeSkill(tags = listOf("divine"))

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
  }

  @Test
  fun `unit - the enter restriction keeps out non-mages and non-builders`() {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)
    val fakeWorld = FakeWorld()
    val enterCheck = reg.restrictions.getValue(PluginRestrictionPoint.ENTER).single().second
    val mageActor = FakeActor(traitSet = setOf("mage"))
    val nonMage = FakeActor(traitSet = setOf("warrior"))
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
  }

  @Test
  fun `unit - the heritage command reports a player's race and class`() {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)
    val fakeWorld = FakeWorld()
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
    val tmp = TempFiles.dir("gups-heritage")
    writeFixtureWorld(tmp)
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

  /** Writes a fixture world that pins the heritage jar and carries the zone, skills and rules. */
  private fun writeFixtureWorld(root: Path) {
    val jar = root.resolve("plugins/heritage.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.heritageJar")).copyTo(jar)
    val schema = Path.of(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText()
    write(
      root,
      "MODULE.gups",
      schema.replaceFirst(
        "module(",
        "module(\n    plugins = {\"heritage\": \"${PluginLoader.sha256(jar)}\"},",
      ),
    )
    val rules = Path.of(System.getProperty("gups.templateWorld")).resolve("rules")
    Files.list(rules)
      .use { s -> s.toList() }
      .forEach { it.copyTo(root.resolve("rules").createDirectories().resolve(it.fileName)) }
    TestWorlds.makeSpellsLand(root.resolve("rules"))

    write(
      root,
      "rules/respec.star",
      """
      def _respec(ctx):
          heritage.choose(ctx.actor, "class", "cleric")

      global_command("respec", _respec)
      """,
    )

    writeFixtureSkills(root)
    writeFixtureZone(root)
  }

  /** Writes the skills package the fixture zone depends on, with an arcane and a divine skill. */
  private fun writeFixtureSkills(root: Path) {
    write(root, "skills/package.star", """package(api_version = 1, kind = "skills")""")
    write(
      root,
      "skills/skills.star",
      """
      skill("magic_missile", kind = "spell", target = "enemy", tags = ["arcane"])
      skill("minor_heal", kind = "spell", target = "self", tags = ["divine"])
      """,
    )
  }

  /** Writes the heritage zone's rooms, items and mobs plus the scenarios that exercise it. */
  private fun writeFixtureZone(root: Path) {
    write(root, "zones/heritage_zone/package.star", """package(api_version = 1, kind = "zone")""")
    write(
      root,
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
      root,
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
  }

  // ---- Telnet Creation Flow Test ------------------------------------------------------------

  @Test
  fun `telnet creation flow creates character with choices`() {
    val tmp = TempFiles.dir("gups-heritage-telnet")
    val world = bootTelnetWorld(tmp)

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

  /** Builds and boots a world that pins the heritage jar, ready for a telnet client to log in. */
  private fun bootTelnetWorld(tmp: Path): World {
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
    return world
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
