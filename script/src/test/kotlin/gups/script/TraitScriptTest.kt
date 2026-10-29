package gups.script

import gups.core.Label
import gups.core.WorldControlEvent
import gups.core.model.CreationChoice
import gups.core.model.CreationOption
import gups.core.model.CreationProvider
import gups.core.model.DamageType
import gups.core.model.Player
import gups.core.model.Trait
import gups.core.rules.RulesContext
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.grantTrait
import gups.core.world.handle
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraitScriptTest {
  /** Race then class; dwarves can't be mages. */
  private class Heritage : CreationProvider {
    override val choices =
      listOf(
        CreationChoice(
          "race",
          "Race",
          options =
            listOf(
              CreationOption("elf", "Elf", ""),
              CreationOption("dwarf", "Dwarf", "", traits = listOf("dwarf")),
            ),
        ),
        CreationChoice(
          "class",
          "Class",
          options =
            listOf(CreationOption("warrior", "Warrior", ""), CreationOption("mage", "Mage", "")),
        ),
      )

    override fun available(world: World, choice: String, answers: Map<String, String>) =
      choices.first { it.id == choice }.options.map { it.id } -
        if (choice == "class" && answers["race"] == "dwarf") setOf("mage") else emptySet()

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) = Unit
  }

  /** What a heritage plugin would wire in: its traits and, optionally, its creation provider. */
  private class Wiring(
    private val known: Map<String, Trait>,
    private val creation: CreationProvider? = null,
  ) : WorldPluginWiring {
    override val globals: Map<String, Any> = emptyMap()
    override val traits: Set<String>
      get() = known.keys

    override fun install(world: World) {
      world.traits = known
      world.creation = creation
    }

    override fun started(world: World) = Unit
  }

  private val traits =
    mapOf(
      "dwarf" to
        Trait(
          "dwarf",
          statDeltas = mapOf("str" to 2),
          maxVitalDeltas = mapOf("hp" to 5),
          resistDeltas = mapOf("fire" to 50),
        ),
      "mage" to Trait("mage"),
    )

  private val startingStats =
    """
    def starting_stats(ctx):
        a = ctx.actor
        hp = 30 if a.choices.get("race") == "dwarf" else 20
        return struct(
            stats = stats(str = 10, dex = 10, con = 10, int = 10, wis = 10, armor = 0),
            max = vitals(hp = hp, mana = 20, move = 100),
        )
    """

  private fun files(rules: String = startingStats, rooms: String = "room(\"start\")\n") =
    mapOf(
      "rules/package.star" to TestWorlds.RULES_PACKAGE,
      "rules/combat.star" to TestWorlds.rules(rules),
      "zones/z/package.star" to zonePkg(),
      "zones/z/rooms.star" to rooms,
    )

  private fun load(
    root: java.nio.file.Path,
    wiring: WorldPluginWiring,
  ): Pair<ScriptLoader, LoadResult> {
    val loader = ScriptLoader(WorldDir(root), ScriptLimits.HARNESS, plugins = wiring)
    return loader to loader.load(listOf("zones/z", "rules"))
  }

  private fun world(
    wiring: WorldPluginWiring = Wiring(traits, Heritage()),
    root: java.nio.file.Path = tempWorld(files()),
  ): World {
    val (loader, result) = load(root, wiring)
    check(result.ok) { result.report() }
    val host = StarlarkHost(result, ScriptLimits.HARNESS, loader)
    return World(
        result.prototypes(),
        loader.schema,
        host.rules(),
        host,
        loader.module.config(WorldConfig(seed = 1, checkInvariants = true, strict = true)),
      )
      .also {
        wiring.install(it)
        host.install(it)
        it.start()
      }
  }

  private val start = Label("//zones/z:start")

  @Test
  fun `starting_stats reads the answers, and its results are base values with trait deltas on top`() {
    val w = world()
    val elf = w.spawnPlayer("Ann", start, choices = mapOf("race" to "elf", "class" to "mage"))
    val dwarf =
      w.spawnPlayer("Bob", start, choices = mapOf("race" to "dwarf", "class" to "warrior"))
    assertEquals(20, elf.baseMax("hp"))
    assertEquals(30, dwarf.baseMax("hp"))
    assertEquals(35, dwarf.max("hp"))
    assertEquals(35, dwarf.current("hp"), "a vital starts at its effective maximum")
    assertEquals(10, dwarf.baseStat("str"))
    assertEquals(12, dwarf.stat("str"))
  }

  @Test
  fun `actor views show effective values, base values, answers and traits`() {
    val w =
      world(
        root =
          tempWorld(
            files(
              startingStats.trimIndent() +
                "\n" +
                """
                def cast_allowed(ctx):
                    a = ctx.actor
                    return ctx.deny("%d %d %d %d %d %d %s %s %s" % (
                        a.str, a.base.str, a.max_hp, a.base.max_hp, a.resists.fire,
                        a.base.resists.fire, a.has_trait("dwarf"), a.traits, a.choices.get("race")))
                """
                  .trimIndent()
            )
          )
      )
    val bob = w.spawnPlayer("Bob", start, choices = mapOf("race" to "dwarf", "class" to "warrior"))
    assertEquals(
      "12 10 35 30 50 0 True [\"dwarf\"] dwarf",
      w.rules.castAllowed(RulesContext(w, bob)),
    )
  }

  @Test
  fun `mitigate uses the effective resist`() {
    val w = world()
    val ann = w.spawnPlayer("Ann", start, choices = mapOf("race" to "elf", "class" to "mage"))
    val bob = w.spawnPlayer("Bob", start, choices = mapOf("race" to "dwarf", "class" to "warrior"))
    assertEquals(
      mapOf("hp" to 10),
      w.rules.mitigate(RulesContext(w, bob, ann), 10, DamageType("fire")),
    )
    assertEquals(
      mapOf("hp" to 5),
      w.rules.mitigate(RulesContext(w, ann, bob), 10, DamageType("fire")),
    )
  }

  @Test
  fun `the dry run runs starting_stats for each option and catches an error only one option reaches`() {
    val failing =
      """
      def starting_stats(ctx):
          if ctx.actor.choices.get("class") == "mage":
              fail("mages are broken")
          return struct(
              stats = stats(str = 10, dex = 10, con = 10, int = 10, wis = 10, armor = 0),
              max = vitals(hp = 20, mana = 20, move = 100),
          )
      """
    val root = tempWorld(files(failing))
    val (_, without) = load(root, Wiring(traits))
    assertTrue(without.ok, "no player is a mage without the provider")
    val (_, with) = load(root, Wiring(traits, Heritage()))
    assertFalse(with.ok)
    assertContains(with.report(), "dry run:")
    assertContains(with.report(), "mages are broken")
  }

  @Test
  fun `the dry run sees every option once`() {
    val seen =
      """
      def starting_stats(ctx):
          if ctx.actor.choices:
              fail("saw %s" % ",".join(sorted([k + "=" + v for k, v in ctx.actor.choices.items()])))
          return struct(
              stats = stats(str = 10, dex = 10, con = 10, int = 10, wis = 10, armor = 0),
              max = vitals(hp = 20, mana = 20, move = 100),
          )
      """
    val (_, result) = load(tempWorld(files(seen)), Wiring(traits, Heritage()))
    val runs = Regex("saw ([a-z=,]+)").findAll(result.report()).map { it.groupValues[1] }.toSet()
    assertEquals(
      setOf("class=warrior,race=elf", "class=warrior,race=dwarf", "class=mage,race=elf"),
      runs,
      "each option is reached, with the first available answer for the other choices",
    )
  }

  @Test
  fun `mob prototype traits apply to the mob`() {
    val w =
      world(
        root =
          tempWorld(
            files(
              rooms =
                "room(\"start\")\nmob(\"miner\", vitals = vitals(hp = 10), traits = [\"dwarf\"])\n"
            )
          )
      )
    val miner = w.spawnMob(Label("//zones/z:miner"), w.room(start))
    assertEquals(listOf("dwarf"), miner.traits.map { it.name })
    assertEquals(miner.baseMax("hp") + 5, miner.max("hp"))
    assertEquals(miner.baseStat("str") + 2, miner.stat("str"))
    assertEquals(50, miner.resist("fire"))
  }

  @Test
  fun `a misspelled mob trait fails the package's load with a suggestion, and lint reports it`() {
    val root = tempWorld(files(rooms = "room(\"start\")\nmob(\"miner\", traits = [\"dwraf\"])\n"))
    val (_, result) = load(root, Wiring(traits))
    assertFalse(result.ok)
    assertContains(result.report(), "unknown trait 'dwraf'")
    assertContains(result.report(), "dwarf")
    val lint = Linter(WorldDir(root), plugins = Wiring(traits)).lint()
    val text = lint.toString()
    assertContains(text, "unknown trait 'dwraf'")
  }

  @Test
  fun `prototype traits follow a reload while granted ones stay`() {
    val root =
      tempWorld(
        files(
          rooms =
            "room(\"start\")\nmob(\"miner\", vitals = vitals(hp = 10), traits = [\"dwarf\"])\n"
        )
      )
    val w = world(root = root)
    val miner = w.spawnMob(Label("//zones/z:miner"), w.room(start))
    w.grantTrait(miner, "mage")
    root
      .resolve("zones/z/rooms.star")
      .toFile()
      .writeText("room(\"start\")\nmob(\"miner\", vitals = vitals(hp = 10))\n")
    w.handle(WorldControlEvent.Reload(listOf("zones/z")))
    assertEquals(listOf("mage"), miner.traits.map { it.name })
    assertEquals(miner.baseMax("hp"), miner.max("hp"))
  }

  @Test
  fun `tags load sorted and deduplicated`() {
    val w =
      world(
        root =
          tempWorld(
            files(
              rooms =
                """
                room("start", tags = ["shrine", "holy", "shrine"])
                item("robe", tags = ["light", "cloth", "light"])
                """
            )
          )
      )
    assertEquals(listOf("holy", "shrine"), w.room(start).tags.toList())
    assertEquals(
      listOf("cloth", "light"),
      w.protos.items.getValue(Label("//zones/z:robe")).tags.toList(),
    )
  }

  @Test
  fun `a tag that isn't a lowercase word fails the load`() {
    for (bad in listOf("Holy", "two words", "1st", "")) {
      val (_, result) =
        load(tempWorld(files(rooms = "room(\"start\", tags = [\"$bad\"])\n")), Wiring(traits))
      assertFalse(result.ok, bad)
      assertContains(result.report(), "'$bad' is not a valid tag")
    }
  }

  @Test
  fun `tags follow a reload`() {
    val root = tempWorld(files(rooms = "room(\"start\", tags = [\"holy\"])\n"))
    val w = world(root = root)
    root.resolve("zones/z/rooms.star").toFile().writeText("room(\"start\", tags = [\"cursed\"])\n")
    w.handle(WorldControlEvent.Reload(listOf("zones/z")))
    assertEquals(setOf("cursed"), w.room(start).tags)
  }
}
