# 05. Testing Character Creation & Traits

Testing a character creation and traits plugin requires verifying two distinct levels:
1. **Fast Unit Tests:** In-memory tests using mock facades to verify trait definitions, availability filtering, validation rules, and restriction handlers in milliseconds.
2. **Integration Tests:** End-to-end tests that package the plugin jar, pin it in a temporary course world, evaluate Starlark `*_test.star` test scripts with the harness `TestRunner`, and simulate interactive login sessions.

This tutorial demonstrates how [`HeritagePluginTest.kt`](../../src/test/kotlin/gups/sample/heritage/HeritagePluginTest.kt) and [`HeritageWorldTest.kt`](../../src/test/kotlin/gups/sample/heritage/HeritageWorldTest.kt) implement these testing patterns.

---

## 1. Fast Unit Testing with Fake Facades

Because `:plugin-api` consists entirely of interfaces, you can test all plugin logic without booting the full engine by writing small fake implementations.

In [`HeritagePluginTest.kt`](../../src/test/kotlin/gups/sample/heritage/HeritagePluginTest.kt), we define:

* **`FakeRegistry`:** Implements `PluginRegistry` to capture registered traits, the creation provider, restrictions, builtins, and commands.
* **`FakeWorld`:** Implements `PluginWorld` to record verb calls such as `send()`, `setChoices()`, and `setSkill()`.
* **`FakeActor`, `FakeItem`, `FakeRoom`, `FakeSkill`:** Lightweight test doubles implementing entity views.

### Example: Testing the Creation Provider in Isolation

We can register the plugin against `FakeRegistry` and directly query the `PluginCreation` provider:

```kotlin
@Test
fun `available options exclude mage for dwarf`() {
  val plugin = HeritagePlugin()
  val registry = FakeRegistry()
  plugin.register(registry)

  val creation = registry.creation ?: error("No creation provider registered")
  val world = FakeWorld()

  // Race choices
  val races = creation.available(world, "race", emptyMap())
  assertEquals(listOf("human", "elf", "dwarf"), races)

  // Class choices for Dwarf: Mage should be excluded
  val dwarfClasses = creation.available(world, "class", mapOf("race" to "dwarf"))
  assertEquals(listOf("warrior", "cleric"), dwarfClasses)

  // Class choices for Elf: All classes available
  val elfClasses = creation.available(world, "class", mapOf("race" to "elf"))
  assertEquals(listOf("warrior", "mage", "cleric"), elfClasses)
}
```

### Example: Testing Whole-Set Validation

```kotlin
@Test
fun `validate rejects dwarf mage`() {
  val plugin = HeritagePlugin()
  val registry = FakeRegistry()
  plugin.register(registry)
  val creation = registry.creation!!
  val world = FakeWorld()

  assertEquals(
    "Dwarves cannot be mages.",
    creation.validate(world, mapOf("race" to "dwarf", "class" to "mage")),
  )
  assertNull(creation.validate(world, mapOf("race" to "dwarf", "class" to "warrior")))
  assertNull(creation.validate(world, mapOf("race" to "elf", "class" to "mage")))
}
```

### Example: Testing the `created()` Hook

```kotlin
@Test
fun `created grants magic missile to new mage`() {
  val plugin = HeritagePlugin()
  val registry = FakeRegistry()
  plugin.register(registry)
  val creation = registry.creation!!
  val world = FakeWorld()
  val mage = FakeActor(choices = mapOf("class" to "mage"))

  creation.created(world, mage, newCharacter = true)

  assertEquals(listOf(Triple(mage, "//skills:magic_missile", 25)), world.skills)
}

@Test
fun `created does nothing for existing character`() {
  val plugin = HeritagePlugin()
  val registry = FakeRegistry()
  plugin.register(registry)
  val creation = registry.creation!!
  val world = FakeWorld()
  val mage = FakeActor(choices = mapOf("class" to "mage"))

  creation.created(world, mage, newCharacter = false)

  assertTrue(world.skills.isEmpty(), "Existing characters should not receive starter skills")
}
```

### Example: Testing Restrictions Directly

Restrictions can be tested by invoking the registered lambda with a mock `PluginRestrictionCall`:

```kotlin
@Test
fun `wear restriction blocks mages from heavy armor`() {
  val plugin = HeritagePlugin()
  val registry = FakeRegistry()
  plugin.register(registry)
  val wearCheck = registry.restrictions[PluginRestrictionPoint.WEAR]!!.first().second

  val mage = FakeActor(traits = listOf("mage"))
  val warrior = FakeActor(traits = listOf("warrior"))
  val heavyArmor = FakeItem(tags = setOf("heavy_armor"))
  val robes = FakeItem(tags = setOf("cloth"))

  val blocked = wearCheck(PluginRestrictionCall(mage, item = heavyArmor, world = FakeWorld()))
  assertEquals("Your spellcraft can't work through that much metal.", blocked)

  val allowedMage = wearCheck(PluginRestrictionCall(mage, item = robes, world = FakeWorld()))
  assertNull(allowedMage)

  val allowedWarrior = wearCheck(PluginRestrictionCall(warrior, item = heavyArmor, world = FakeWorld()))
  assertNull(allowedWarrior)
}
```

---

## 2. Integration Testing with Fixture Worlds

While unit tests prove logic in isolation, integration tests verify that:
1. The packaged `.jar` compiles and passes ServiceLoader discovery.
2. The plugin checksum pins correctly in `MODULE.gups`.
3. Starlark scripts interact with the plugin seamlessly.
4. Mob prototypes carrying traits obey the same restrictions as players.

In [`HeritageWorldTest.kt`](../../src/test/kotlin/gups/sample/heritage/HeritageWorldTest.kt), a temporary copy of `template-world` is constructed on disk with `heritage.jar` pinned in `MODULE.gups`:

```kotlin
val jar = root.resolve("plugins/heritage.jar")
Path.of(System.getProperty("gups.sample.heritageJar")).copyTo(jar)

val module = root.resolve("MODULE.gups")
module.writeText(
  module.readText().replaceFirst(
    "module(",
    "module(\n    plugins = {\"heritage\": \"${PluginLoader.sha256(jar)}\"},",
  )
)
```

### Authoring Harness Test Scripts (`*_test.star`)

We can write Starlark tests executed by `TestRunner` to verify live world interactions:

```python
# In zones/heritage_test.star
load("//harness:testing.star", "test", "assert_eq", "assert_true")

def test_guild_entry(t):
    # Spawn a player mage and a player warrior
    mage = t.spawn_player("Gandalf", choices = {"race": "elf", "class": "mage"})
    warrior = t.spawn_player("Conan", choices = {"race": "human", "class": "warrior"})
    
    # Attempt entering the mage guild room (tagged "guild_mage")
    res_mage = t.walk(mage, "north")
    assert_true(res_mage.ok, "Mage should be permitted into the mage guild")
    
    res_warrior = t.walk(warrior, "north")
    assert_eq(res_warrior.error, "Only Mages may enter.")

def test_mob_prototype_traits(t):
    # Spawn a wandering NPC mob declared with traits = ["mage"]
    trainer = t.spawn_mob("//zones:archmage")
    assert_true(trainer.has_trait("mage"))
    
    # Verify the NPC mob can step through guild doors
    assert_true(t.walk(trainer, "north").ok)
```

### Key Assertions Demonstrated:
* **Harness Helper `t.spawn_player(choices = ...)`:** Creates a player with pre-selected choices.
* **Mob Trait Consistency:** Mobs declared with `traits = ["mage"]` successfully walk into `guild_mage` rooms while warrior mobs are rejected.
* **Effective Stats:** Asserting that `actor.stat("int")` reflects the `+2 int` bonus from the `mage` trait and `actor.max_vital("mana")` reflects `+20 mana`.

---

## 3. Simulating Interactive Character Creation Sessions

To test the Telnet creation sequence without manual typing, tests drive the session state machine programmatically:

```kotlin
@Test
fun `telnet creation flow sets choices and grants starter skills`() {
  val worldDir = worldDir(...)
  // Boot engine with DevLogin / AccountLogin
  val session = TestSession()
  
  // Provide character name
  session.sendLine("Gandalf")
  session.sendLine("yes") // Confirm new character
  
  // Race prompt
  assertTrue(session.lastOutput.contains("Choose your race:"))
  session.sendLine("2") // 2. Elf
  
  // Class prompt: Dwarf restrictions do not apply to Elf
  assertTrue(session.lastOutput.contains("Choose your class:"))
  session.sendLine("mage") // Answering by ID
  
  // Confirmation
  assertTrue(session.lastOutput.contains("Confirm choices?"))
  session.sendLine("confirm")
  
  // Verify character spawned with traits and starter skill
  val player = world.playerByName("Gandalf")!!
  assertEquals("elf", player.choices["race"])
  assertEquals("mage", player.choices["class"])
  assertTrue(player.hasTrait("mage"))
  assertEquals(25, player.skillProficiency("//skills:magic_missile"))
}
```

---

## Summary

* **Unit tests** using `FakeRegistry`, `FakeWorld`, and mock entity views provide lightning-fast verification of availability, validation, and restriction logic.
* **Integration tests** verify that the built `.jar` loads into the engine, interacts cleanly with Starlark scripts, and enforces uniform rules across both players and mob prototypes.
* **Harness helpers** like `spawn_player(choices = ...)` allow easy authoring of `*_test.star` scripts.
* **Session simulation** ensures that the terminal creation flow works seamlessly from the user's perspective.
