package gups.pluginapi

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.World
import gups.core.world.spawnItem
import gups.core.world.submit
import gups.core.world.teleport
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreationRegistrationTest {
  private val schema = world().schema
  private val stat = schema.statNames.first()
  private val vital = schema.vitalNames.first()
  private val damage = schema.damageTypes.first()

  private fun plugin(id: String, register: (PluginRegistry) -> Unit) =
    object : GupsPlugin {
      override val id = id
      override val pluginApiVersion = PluginApi.VERSION

      override fun register(r: PluginRegistry) = register(r)
    }

  private fun creation(vararg choices: PluginChoice) =
    object : PluginCreation {
      override val choices = choices.toList()
    }

  private fun option(id: String, vararg traits: String) =
    PluginOption(id, id.replaceFirstChar { it.uppercase() }, "", traits = traits.toList())

  /** Registers [bad] next to a plugin that registers fine, and returns the refusal. */
  private fun refusal(features: Features = Features.ALL, bad: (PluginRegistry) -> Unit): String {
    val host =
      PluginHost.of(
        plugin("bad", bad),
        plugin("good") { it.addTrait(PluginTrait("fine")) },
        schema = schema,
        features = features,
      )
    assertEquals(listOf("good"), host.ids, "only the refused plugin is skipped")
    val warning = host.warnings.single()
    assertTrue(warning.startsWith("plugin 'bad': register failed: "), warning)
    return warning.removePrefix("plugin 'bad': register failed: ")
  }

  @Test
  fun `trait names must be member names and unique within a plugin`() {
    assertEquals(
      "trait name 'Elf' must match [a-z][a-z0-9_]*",
      refusal { it.addTrait(PluginTrait("Elf")) },
    )
    assertEquals(
      "trait 'elf' is already registered",
      refusal {
        it.addTrait(PluginTrait("elf"))
        it.addTrait(PluginTrait("elf"))
      },
    )
  }

  @Test
  fun `a trait name another plugin registered first is refused`() {
    val host =
      PluginHost.of(
        plugin("beta") { it.addTrait(PluginTrait("elf")) },
        plugin("alpha") { it.addTrait(PluginTrait("elf")) },
        schema = schema,
      )
    assertEquals(listOf("alpha"), host.ids)
    assertEquals(
      listOf("plugin 'beta': register failed: trait 'elf' is already registered"),
      host.warnings,
    )
  }

  @Test
  fun `trait deltas must name declared stats, vitals and damage types, with a suggestion`() {
    assertContains(
      refusal { it.addTrait(PluginTrait("x", statDeltas = mapOf("${stat}x" to 1))) },
      "unknown stat '${stat}x'",
    )
    assertContains(
      refusal { it.addTrait(PluginTrait("x", statDeltas = mapOf("${stat}x" to 1))) },
      "'$stat'",
    )
    assertContains(
      refusal { it.addTrait(PluginTrait("x", maxVitalDeltas = mapOf("${vital}x" to 1))) },
      "unknown vital '${vital}x'",
    )
    assertContains(
      refusal { it.addTrait(PluginTrait("x", resistDeltas = mapOf("${damage}x" to 1))) },
      "unknown damage type '${damage}x'",
    )
  }

  @Test
  fun `trait sense deltas must name a built-in or declared channel`() {
    assertContains(
      refusal { it.addTrait(PluginTrait("x", senseDeltas = mapOf("sigth" to 1))) },
      "unknown channel 'sigth'",
    )
    assertContains(
      refusal { it.addTrait(PluginTrait("x", senseDeltas = mapOf("sigth" to 1))) },
      "'sight'",
    )
    val host =
      PluginHost.of(
        plugin("keen") { it.addTrait(PluginTrait("keen", senseDeltas = mapOf("sound" to 2))) },
        schema = schema,
      )
    assertEquals(emptyList(), host.warnings)
  }

  @Test
  fun `a trait can't carry the stunned flag`() {
    assertEquals(
      "trait 'dazed': flags cannot contain 'stunned'",
      refusal { it.addTrait(PluginTrait("dazed", flags = setOf("stunned"))) },
    )
  }

  @Test
  fun `a combat modifier point needs the combat feature`() {
    val noCombat = Features(Feature.entries.toSet() - Feature.COMBAT)
    for (point in
      listOf(
        PluginModifierPoint.TO_HIT,
        PluginModifierPoint.DAMAGE,
        PluginModifierPoint.INITIATIVE,
        PluginModifierPoint.FLEE_FAIL_LAG,
      )) {
      assertEquals(
        "trait 'keen': modifier point $point needs the combat feature",
        refusal(noCombat) {
          it.addTrait(PluginTrait("keen", modifiers = mapOf(point to PluginModifier { _, b -> b })))
        },
      )
    }
    val host =
      PluginHost.of(
        plugin("ok") {
          it.addTrait(
            PluginTrait(
              "brisk",
              modifiers = mapOf(PluginModifierPoint.REGEN to PluginModifier { _, b -> b }),
            )
          )
        },
        schema = schema,
        features = noCombat,
      )
    assertEquals(listOf("ok"), host.ids, "regen needs no feature")
  }

  @Test
  fun `a track lag modifier point needs the track feature`() {
    val noTrack = Features(Feature.entries.toSet() - Feature.TRACK)
    for (point in
      listOf(
        PluginModifierPoint.ADVANCE_LAG,
        PluginModifierPoint.RETREAT_LAG,
        PluginModifierPoint.RETREAT_FAIL_LAG,
      )) {
      assertEquals(
        "trait 'nimble': modifier point $point needs the track feature",
        refusal(noTrack) {
          it.addTrait(
            PluginTrait("nimble", modifiers = mapOf(point to PluginModifier { _, b -> b }))
          )
        },
      )
    }
    val host =
      PluginHost.of(
        plugin("ok") {
          it.addTrait(
            PluginTrait(
              "fleeing",
              modifiers = mapOf(PluginModifierPoint.FLEE_FAIL_LAG to PluginModifier { _, b -> b }),
            )
          )
        },
        schema = schema,
        features = noTrack,
      )
    assertEquals(listOf("ok"), host.ids, "flee_fail_lag needs only combat")
  }

  @Test
  fun `choice and option ids are checked`() {
    fun race(vararg opts: PluginOption) = PluginChoice("race", "Race", opts.toList())
    assertEquals(
      "creation provider must declare at least one choice",
      refusal { it.setCreation(creation()) },
    )
    assertEquals(
      "choice id 'Race' must match [a-z][a-z0-9_]*",
      refusal { it.setCreation(creation(PluginChoice("Race", "Race", listOf(option("elf"))))) },
    )
    assertEquals(
      "duplicate choice id 'race'",
      refusal { it.setCreation(creation(race(option("elf")), race(option("dwarf")))) },
    )
    assertEquals(
      "choice 'race' must declare at least one option",
      refusal { it.setCreation(creation(race())) },
    )
    assertEquals(
      "option id 'High-elf' in choice 'race' must match [a-z][a-z0-9_]*",
      refusal { it.setCreation(creation(race(option("High-elf")))) },
    )
    assertEquals(
      "duplicate option id 'elf' in choice 'race'",
      refusal { it.setCreation(creation(race(option("elf"), option("elf")))) },
    )
    for (reserved in listOf("back", "cancel", "help", "yes", "no")) {
      assertEquals(
        "option id '$reserved' in choice 'race' is reserved",
        refusal { it.setCreation(creation(race(option(reserved)))) },
      )
    }
  }

  @Test
  fun `an option may only name its own plugin's traits`() {
    val host =
      PluginHost.of(
        plugin("alpha") { it.addTrait(PluginTrait("elf")) },
        plugin("beta") {
          it.setCreation(creation(PluginChoice("race", "Race", listOf(option("elf", "elf")))))
        },
        schema = schema,
      )
    assertEquals(listOf("alpha"), host.ids)
    assertEquals(
      listOf(
        "plugin 'beta': register failed: option 'elf' in choice 'race' names trait 'elf', which is not registered by this plugin"
      ),
      host.warnings,
    )
  }

  @Test
  fun `only one creation provider may be registered`() {
    val race = PluginChoice("race", "Race", listOf(option("elf")))
    assertEquals(
      "setCreation can only be called once",
      refusal {
        it.setCreation(creation(race))
        it.setCreation(creation(race))
      },
    )
    val host =
      PluginHost.of(
        plugin("beta") { it.setCreation(creation(race)) },
        plugin("alpha") { it.setCreation(creation(race)) },
        schema = schema,
      )
    assertEquals(listOf("alpha"), host.ids)
    assertEquals(
      listOf("plugin 'beta': register failed: a creation provider is already registered"),
      host.warnings,
    )
  }

  @Test
  fun `cast and learn restrictions need the skills feature`() {
    val noSkills = Features(Feature.entries.toSet() - Feature.SKILLS)
    for (point in listOf(PluginRestrictionPoint.CAST, PluginRestrictionPoint.LEARN)) {
      assertEquals(
        "restriction point $point needs the skills feature",
        refusal(noSkills) { it.addRestriction(point, "Doc.") { null } },
      )
    }
    val host =
      PluginHost.of(
        plugin("ok") {
          it.addRestriction(PluginRestrictionPoint.WEAR, "Doc.") { null }
          it.addRestriction(PluginRestrictionPoint.ENTER, "Doc.") { null }
        },
        schema = schema,
        features = noSkills,
      )
    assertEquals(listOf("ok"), host.ids)
  }

  @Test
  fun `several plugins' restrictions run in id order and the first refusal wins`() {
    val calls = mutableListOf<String>()
    val host =
      PluginHost.of(
        plugin("zeta") {
          it.addRestriction(PluginRestrictionPoint.ENTER, "Doc.") {
            calls += "zeta"
            "Zeta says no."
          }
        },
        plugin("alpha") {
          it.addRestriction(PluginRestrictionPoint.ENTER, "Doc.") {
            calls += "alpha"
            null
          }
        },
        plugin("mid") {
          it.addRestriction(PluginRestrictionPoint.ENTER, "Doc.") {
            calls += "mid"
            "Mid says no."
          }
        },
        schema = schema,
      )
    val w = world(plugins = host)
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    w.teleport(ann, w.room(Label("//zones/z:b")))
    assertEquals(listOf("alpha", "mid"), calls)
    assertEquals("//zones/z:a", ann.room.label.v)
  }

  @Test
  fun `a throwing plugin restriction allows and records a diagnostic`() {
    val host =
      PluginHost.of(
        plugin("broken") {
          it.addRestriction(PluginRestrictionPoint.ENTER, "Doc.") { error("oops") }
        },
        schema = schema,
      )
    val w = world(plugins = host)
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    w.teleport(ann, w.room(Label("//zones/z:b")))
    w.runPulse()
    assertEquals("//zones/z:b", ann.room.label.v)
    assertTrue(
      w.diagnostics.any {
        it.kind == "plugin" && "plugin 'broken'" in it.message && "oops" in it.message
      },
      "${w.diagnostics}",
    )
  }

  @Test
  fun `a plugin restriction registered at PluginRestrictionPoint DROP is reached by drop`() {
    var reached = false
    val host =
      PluginHost.of(
        plugin("heavy") {
          it.addRestriction(PluginRestrictionPoint.DROP, "Doc.") {
            reached = true
            "Cannot drop."
          }
        },
        schema = schema,
      )
    val w = world(plugins = host)
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val item = w.spawnItem(Label("//zones/z:stick"), ItemLocation.Carried(ann.id))
    w.submit(ann, "drop stick")
    w.runPulse()
    assertTrue(reached)
    assertEquals(ItemLocation.Carried(ann.id), item.location)
  }
}

class CreationFacadeTest {
  private val heritage =
    object : GupsPlugin {
      override val id = "heritage"
      override val pluginApiVersion = PluginApi.VERSION

      override fun register(r: PluginRegistry) {
        r.addTrait(PluginTrait("elf"))
        r.addTrait(PluginTrait("mage"))
        r.addTrait(PluginTrait("blessed"))
        r.setCreation(
          object : PluginCreation {
            override val choices =
              listOf(
                PluginChoice(
                  "race",
                  "Race",
                  listOf(PluginOption("elf", "Elf", "", traits = listOf("elf"))),
                ),
                PluginChoice(
                  "class",
                  "Class",
                  listOf(PluginOption("mage", "Mage", "", traits = listOf("mage"))),
                ),
              )
          }
        )
      }
    }
  private val w: World = world(plugins = PluginHost.of(heritage, schema = world().schema))
  private val f = WorldFacade(w, "heritage") {}
  private val player: Player =
    w.spawnPlayer("Ann", Label("//zones/z:a"), choices = mapOf("race" to "elf", "gone" to "x"))
  private val ann = ActorFacade(player, w)
  private val wolf =
    ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)

  @Test
  fun `views show known choices, sorted traits and granted traits`() {
    assertEquals(mapOf("race" to "elf"), ann.choices)
    assertEquals(listOf("elf"), ann.traits)
    assertNull(f.grantTrait(ann, "blessed"))
    assertEquals(listOf("blessed", "elf"), ann.traits)
    assertEquals(setOf("blessed"), ann.grantedTraits)
    assertTrue(ann.hasTrait("blessed"))
    assertEquals(emptyMap(), wolf.choices)
  }

  @Test
  fun `setChoices, grantTrait and removeTrait change the actor or refuse`() {
    assertNull(f.setChoices(ann, mapOf("class" to "mage")))
    assertEquals(listOf("elf", "mage"), ann.traits)
    assertEquals("Trait 'mage' was not granted directly", f.removeTrait(ann, "mage"))
    assertNull(f.grantTrait(wolf, "blessed"))
    assertEquals("Trait 'blessed' already granted", f.grantTrait(wolf, "blessed"))
    assertNull(f.removeTrait(wolf, "blessed"))
    assertEquals(emptyList(), wolf.traits)
  }

  @Test
  fun `unknown ids and mobs throw IllegalArgumentException`() {
    assertFailsWith<IllegalArgumentException> { f.grantTrait(ann, "nope") }
    assertFailsWith<IllegalArgumentException> { f.removeTrait(ann, "nope") }
    assertFailsWith<IllegalArgumentException> { f.setChoices(ann, mapOf("deity" to "sun")) }
    assertFailsWith<IllegalArgumentException> { f.setChoices(ann, mapOf("race" to "orc")) }
    assertFailsWith<IllegalArgumentException> { f.setChoices(wolf, mapOf("race" to "elf")) }
  }

  @Test
  fun `setChoices without a creation provider throws`() {
    val bare = world()
    val bob = ActorFacade(bare.spawnPlayer("Bob", Label("//zones/z:a")), bare)
    assertFailsWith<IllegalArgumentException> {
      WorldFacade(bare, "x") {}.setChoices(bob, mapOf("race" to "elf"))
    }
  }
}
