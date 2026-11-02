# Sample Plugin: Curse System

This directory contains the reference curse plugin for the GUPS engine: **curses as content**.

While [`weather-plugin`](../weather-plugin) demonstrates environmental hooks and procedural Starlark builtins, and
[`heritage-plugin`](../heritage-plugin) demonstrates character creation and traits, `curse-plugin` demonstrates how a
plugin can implement clinging cursed items, warded containers, and curse lifecycle mechanics:

* **Operational Restrictions:** Refusing `REMOVE` when removing worn items tagged `cursed`, refusing `DROP` and `GIVE`
  for items bearing curses, and refusing `PUT` when placing cursed items into containers outside the character's own
  custody.
* **Containment Filters:** Intercepting carried affects propagating through the container tree, preventing cursed items
  from affecting holders when placed inside containers tagged `warded`.
* **Effect Primitives:** Providing `curse.lift()` and `curse.bestow()` for item-targeted skills, allowing curses to be
  bestowed or lifted via variant transformations or tag removal.

---

## Directory Layout

```text
samples/curse-plugin/
├── build.gradle.kts           # Gradle build configuration compiling against :plugin-api
├── README.md                  # This file
├── docs/
│   └── tutorials/             # Step-by-step walkthrough series
│       ├── README.md          # Series roadmap and architecture overview
│       ├── 01_item_affects_and_custody.md
│       ├── 02_item_restriction_points.md
│       ├── 03_item_effects_transform_and_variants.md
│       ├── 04_containment_filters.md
│       └── 05_testing_the_curse_plugin.md
└── src/
    ├── main/kotlin/gups/sample/curse/
    │   └── CursePlugin.kt     # Complete plugin implementation
    ├── plugin/META-INF/services/
    │   └── gups.pluginapi.GupsPlugin  # Java ServiceLoader SPI declaration
    └── test/kotlin/gups/sample/curse/
        ├── CursePluginTest.kt # Fast unit tests with mock facades
        └── CurseWorldTest.kt  # End-to-end integration tests with TestRunner
```

---

## Step-by-Step Walkthrough

If you are implementing item affects, custom containment rules, or gameplay restrictions, start with the
**[Curse Plugin Developer's Guide](docs/tutorials/README.md)**:

1. **[01. Item Affects & Custody Propagation](docs/tutorials/01_item_affects_and_custody.md)** — `worn` vs. `carried`
   scopes, how item affects traverse container hierarchies, and why containers are not escape hatches.
2. **[02. Item Restriction Points](docs/tutorials/02_item_restriction_points.md)** — The four kernel restriction points
   (`REMOVE`, `DROP`, `GIVE`, `PUT`), the command-only rule, trusted verbs, and preventing equipment swap loopholes.
3. **[03. Item Effects, Transform & Variants](docs/tutorials/03_item_effects_transform_and_variants.md)** —
   Item-targeted skills, prototype `variants`, `world.transform()`, separate clean prototypes vs. tag changes, and
   `curse.bestow()`.
4. **[04. Containment Filters](docs/tutorials/04_containment_filters.md)** — Registering `addContainmentFilter()`, the
   filter purity contract, and keeping the filter in agreement with the `bearsCurse` predicate the restrictions use.
5. **[05. Testing the Curse Plugin](docs/tutorials/05_testing_the_curse_plugin.md)** — Fast unit testing with mock
   facades and end-to-end integration testing via `TestRunner` in fixture worlds.

---

## Building and Testing

### Build the Plugin Jar

To compile the plugin and package the `.jar` bundle:

```bash
./gradlew :samples:curse-plugin:jar
```

The output jar is generated at:

```text
samples/curse-plugin/build/libs/curse.jar
```

### Pin the Plugin in a World

Copy the jar to `plugins/curse.jar` in the course world repository and pin its checksum in `MODULE.gups`:

```bash
shasum -a 256 plugins/curse.jar
```

```python
module(
    api_version = 1,
    plugins = {"curse": "sha256:<the 64 hex digits shasum printed>"},
)
```

The restrictions and the containment filter work in any world. `curse.lift()` and `curse.bestow()` are effect
primitives, so using them needs the `skills` feature and an item skill such as `skill("remove_curse", target = "item",
effect = curse.lift())`. See [Installing a plugin](../../docs/PLUGINS.md#installing-a-plugin) for how jars are loaded
and verified.

### Run the Test Suite

The sample includes fast unit tests verifying restrictions, containment filters, and effect primitives, as well as an
end-to-end integration test that boots a test world with the pinned jar:

```bash
./gradlew :samples:curse-plugin:test
```

---

## Essential Reference Pointers

* **Engine Architecture Specification:** See [`docs/SPEC.md`](../../docs/SPEC.md) §4.15 (*Plugins*) and §4.19
  (*Character Creation, Traits and Restrictions*).
* **Plugin Developer Reference:** See [`docs/PLUGINS.md`](../../docs/PLUGINS.md) for plugin interfaces, restrictions,
  containment filters, and item facade APIs.
