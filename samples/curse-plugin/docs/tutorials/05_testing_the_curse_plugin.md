# 05. Testing the Curse Plugin

Plugin logic should be verified with the same rigor as the core engine. A robust plugin testing suite employs two
distinct layers of verification:

1. **Tier 1: Fast Unit Tests (`CursePluginTest.kt`)** — In-memory unit tests using hand-built mock facades to verify
   predicate decision tables, restriction checks, and effect execution in isolation without booting a game server.
2. **Tier 2: End-to-End Integration Tests (`CurseWorldTest.kt`)** — Integration tests compiling the plugin `.jar`,
   pinning it in a temporary fixture world, and executing Starlark `*_test.star` scripts through the engine's
   `TestRunner`.

This tutorial explains the structure and techniques used in both test suites.

---

## 1. Tier 1: Fast Unit Tests with Mock Facades

In [`CursePluginTest.kt`](../../src/test/kotlin/gups/sample/curse/CursePluginTest.kt), mock implementations of
`PluginRegistry`, `PluginWorld`, `PluginItem`, and `PluginActor` allow unit tests to run in milliseconds.

### Testing the `bearsCurse` Truth Table

Because `bearsCurse` handles recursive containment and wards, we test each row of its decision table directly:

```kotlin
@Test
fun `bearsCurse decision table - warded box holding one is false`() {
  val box = FakeItem(listOf("warded"), contents = listOf(FakeItem(listOf("cursed"))))
  assertFalse(bearsCurse(box))
}

@Test
fun `bearsCurse decision table - plain bag holding a warded box holding one is false`() {
  val box = FakeItem(listOf("warded"), contents = listOf(FakeItem(listOf("cursed"))))
  val bag = FakeItem(listOf(), contents = listOf(box))
  assertFalse(bearsCurse(bag))
}

@Test
fun `bearsCurse decision table - cursed warded box is true`() {
  val box = FakeItem(listOf("cursed", "warded"))
  assertTrue(bearsCurse(box))
}
```

### Testing Restrictions & Custody Rules

Unit tests verify that `PUT` allows transfers within the character's own custody while refusing transfers to external
containers:

```kotlin
@Test
fun `PUT within custody - cursed item into bag in actor's inventory is allowed`() {
  val cursedItem = FakeItem(listOf("cursed"))
  val bagInInv = FakeItem(listOf(), custodian = actor)
  val call = PluginRestrictionCall(world, PluginRestrictionPoint.PUT, actor, item = cursedItem, target = bagInInv)
  assertNull(putCheck(call))
}

@Test
fun `PUT out of custody - cursed item into container on the ground is refused`() {
  val cursedItem = FakeItem(listOf("cursed"))
  val groundChest = FakeItem(listOf(), custodian = null)
  val call = PluginRestrictionCall(world, PluginRestrictionPoint.PUT, actor, item = cursedItem, target = groundChest)
  assertEquals("It won't budge. It must be cursed!", putCheck(call))
}
```

---

## 2. Tier 2: End-to-End Integration Testing with `TestRunner`

In [`CurseWorldTest.kt`](../../src/test/kotlin/gups/sample/curse/CurseWorldTest.kt), the plugin is packaged as a real
`.jar` bundle and tested inside a complete course world:

```kotlin
@Test
fun `the built curse jar runs through TestRunner in fixture world`() {
  val tmp = Files.createTempDirectory("gups-curse")
  val jar = tmp.resolve("plugins/curse.jar").also { it.parent.createDirectories() }
  Path.of(System.getProperty("gups.sample.curseJar")).copyTo(jar)

  // Pin the jar in MODULE.gups with SHA-256
  val schema = Path.of(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText()
  write(
    tmp,
    "MODULE.gups",
    schema.replaceFirst("module(", "module(\n    plugins = {\"curse\": \"${PluginLoader.sha256(jar)}\"},"),
  )
  ...
```

The test writes:

* The world's `MODULE.gups` and template `rules/`.
* A `skills` package declaring `remove_curse` (`effect = curse.lift()`) and `bestow_curse` (`effect = curse.bestow()`).
* A zone `curse_zone` declaring rooms, containers, weapons, cursed jewelry, and a test script `curse_test.star`.

Then `TestRunner(dir, plugins = host).run(listOf("//zones/curse_zone"))` executes every Starlark test function.

---

## 3. Authoring Starlark Test Scripts: `curse_test.star`

In `curse_test.star`, test functions simulate live player command input using `p.do("...")` and assert character stats
via `p.dex`:

### Testing Command-Driven Custody Propagation

```python
def test_propagation_by_commands(t):
    w = t.world(packages = ["//skills", "//zones/curse_zone"])
    p = w.spawn_player("Alice", at = ":sanctuary", skills = {"//skills:remove_curse": 100})
    base_dex = p.dex
    t.eq(base_dex, 10)

    # Spawn amulet and containers
    w.spawn(":amulet_of_woe", at = p)
    w.spawn(":small_bag", at = p)
    w.spawn(":backpack", at = p)
    t.eq(p.dex, base_dex - 2)

    # Put amulet in bag, then bag in backpack
    out = p.do("put amulet bag")
    t.contains(out, "You put an amulet of woe in a small bag.")
    t.eq(p.dex, base_dex - 2)

    out = p.do("put bag backpack")
    t.contains(out, "You put a small bag in a backpack.")
    t.eq(p.dex, base_dex - 2)
```

### Testing the Clinging Curse & Warded Relief

```python
def test_warded_box_drop(t):
    w = t.world(packages = ["//skills", "//zones/curse_zone"])
    p = w.spawn_player("Charlie", at = ":sanctuary", skills = {"//skills:remove_curse": 100})
    base_dex = p.dex
    w.spawn(":amulet_of_woe", at = p)
    w.spawn(":lead_box", at = p)
    t.eq(p.dex, base_dex - 2)

    # Putting into warded box suppresses the affect
    out = p.do("put amulet box")
    t.contains(out, "You put an amulet of woe in a lead box.")
    t.eq(p.dex, base_dex)

    # Dropping the warded box succeeds
    out = p.do("drop box")
    t.contains(out, "You drop a lead box.")
    t.eq(p.dex, base_dex)

    # Player walks away free
    out = p.do("north")
    t.contains(out, "Hall")
    t.eq(p.dex, base_dex)
```

### Testing the Weapon Swap Loophole

```python
def test_no_swap_loophole(t):
    w = t.world(packages = ["//skills", "//zones/curse_zone"])
    p = w.spawn_player("Frank", at = ":sanctuary", skills = {"//skills:remove_curse": 100})
    base_dex = p.dex
    sword = w.spawn(":cursed_sword", at = p)
    dagger = w.spawn(":dagger", at = p)

    p.do("wield sword")
    t.eq(p.equipped("wield").id, sword.id)
    t.eq(p.dex, base_dex - 1)

    # Trying to wield the dagger fails at the REMOVE check on the sword, and nothing moves
    out = p.do("wield dagger")
    t.contains(out, "It won't budge. It must be cursed!")
    t.eq(p.equipped("wield").id, sword.id)
    t.eq([i.id for i in p.inventory()], [dagger.id])
    t.eq(p.dex, base_dex - 1)

    # Lifting the curse removes the tag
    p.do("cast remove_curse sword")

    # Now wielding the dagger succeeds, swapping the sword into the inventory
    out = p.do("wield dagger")
    t.contains(out, "You wield a dagger.")
    t.eq(p.equipped("wield").id, dagger.id)
    t.eq([i.id for i in p.inventory()], [sword.id])
    t.eq(p.dex, base_dex)
```

Running both unit tests and integration tests ensures that your plugin's restrictions, filters, and effect primitives
function harmoniously across all gameplay scenarios.
