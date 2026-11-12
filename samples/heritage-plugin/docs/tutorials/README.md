# Heritage Plugin Developer's Guide — Character Creation, Traits & Restrictions

Welcome to the GUPS Heritage Plugin Developer's Guide! This series of tutorials walks you through building, testing, and
integrating a plugin that hooks into **character creation**, introduces permanent **traits**, and enforces **gameplay
restrictions**.

The canonical reference implementation for these guides is the heavily tested sample in
[`samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt`](../../src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt).
While that source file contains the complete, production-grade implementation, this tutorial series serves as its
conceptual companion: explaining the design philosophy, engine seams, execution model, and best practices.

---

## Why Character Creation Lives in Plugins

In GUPS, the engine core is strictly genre-neutral. It powers fantasy MUDs, science-fiction colonies, modern
investigative mysteries, and educational simulations across different university terms.

Consequently, the core engine deliberately **names no races, classes, choices, or traits**. A course world operating
without creation plugins boots smoothly with standard characters, requiring no prompts at login.

When a course world requires structured character creation (e.g. fantasy race and class, sci-fi cybernetic chassis and
specialization, or faction backgrounds): * A **plugin** declares the choices, options, and dynamic availability rules. *
The **engine** drives the interactive login sequence across all supported clients (telnet, WebSocket GMCP, `--dev` CLI)
without the plugin managing raw terminal I/O. * Chosen options map to **traits** whose stat deltas, maximum vital
modifiers, elemental resistances, and modifier functions are applied automatically to the character. * Operational
**restrictions** enforce game rules (e.g. mages cannot wear heavy armor, guild rooms admit only guild members) across
both players and NPCs using content **tags**.

---

## Architectural Overview

The relationship between course world content, the engine lifecycle, and the heritage plugin is summarized below:

```text
┌────────────────────────────────────────────────────────┐
│               World Content (Starlark)                 │
│  - item("plate", tags = ["heavy_armor"])               │
│  - room("hall", tags = ["guild_mage"])                 │
│  - mob("trainer", traits = ["mage"])                   │
└───────────┬────────────────────────────────┬───────────┘
            │                                │
            ▼ (Tags & Prototype Traits)      ▼ (Script Context: ctx.actor.choices)
┌────────────────────────────────────────────────────────┐
│                     Engine Core                        │
│  - Drives telnet/GMCP creation flow                    │
│  - Computes effective stats = base + trait + affect    │
│  - Caches effective max vitals & resists               │
│  - Evaluates modifier chain (to_hit, damage, regen)    │
│  - Triggers restriction points: WEAR, CAST, ENTER...   │
└───────────▲────────────────────────────────▲───────────┘
            │                                │
            │ (Registration & Interception)  │ (Facade Verbs: setChoices, setSkill)
┌───────────┴────────────────────────────────┴───────────┐
│               Heritage Plugin (Kotlin)                 │
│  - PluginCreation: choices, available(), validate()    │
│  - PluginTrait: "human", "elf", "warrior", "mage"...   │
│  - Restrictions: WEAR, CAST, ENTER, LEARN              │
│  - Builtins: heritage.choose, respec_menu, respec ...  │
└────────────────────────────────────────────────────────┘
```

---

## Tutorial Roadmap

1. **[01. Traits & Effective Attributes](01_traits_and_effective_attributes.md)**
   * Defining traits with `PluginTrait`
   * Stat deltas, maximum vital deltas, elemental resistances, and status flags
   * Registering trait modifier functions in the combat and regen chains
   * How actors acquire traits: player option traits vs. granted traits vs. mob prototype traits
   * Base attributes vs. effective attributes
2. **[02. Character Creation Lifecycle & Providers](02_character_creation_provider.md)**
   * Registering the world's `PluginCreation` provider
   * Declaring choices (`PluginChoice`) and options (`PluginOption`)
   * Progressive dynamic availability (`available`) and combination filtering (e.g. no dwarf mages)
   * Holistic whole-set validation (`validate`)
   * Dynamic starting rooms (`startRoom`) and the post-creation hook (`created`)
   * Telnet and GMCP `Char.Creation` protocol flow; handling missing choices on existing characters
3. **[03. Operational Restrictions & Content Tags](03_operational_restrictions_and_tags.md)**
   * The four restriction points: `WEAR`, `CAST`, `ENTER`, and `LEARN`
   * Why restrictions check traits instead of choices
   * Tagging items, rooms, and skills in Starlark (`tags = [...]`)
   * Crafting descriptive player refusals without action lag
   * Bypassing restrictions for staff roles (`PluginRole.BUILDER`, `INSTRUCTOR`, `ADMIN`)
   * Automatic de-equipping when traits change
4. **[04. Runtime Choice Changes, Builtins & Commands](04_runtime_choice_changes_and_builtins.md)**
   * In-game choice modification mechanics via `world.setChoices()`
   * Immediate state captures and vitals capping
   * Clearing a choice for a re-pick at the next login
   * A respec shrine: the `respec_menu`, `respec_check`, `respec` and `starter_skills` builtins and the `respec.star`
     library (prices, cooldowns, `keep` and `reset` skill policies, the `respec_offer` and `respec_done` hooks, the
     `"respec"` standing event)
   * Implementing player-facing commands: the fast-lane `heritage` command
   * Inspecting choices and traits via `score`, `@stat`, and GMCP `Char.Traits`
5. **[05. Testing Character Creation & Traits](05_testing_creation_and_traits.md)**
   * Fast unit testing with mock facades (`FakeRegistry`, `FakeWorld`, `FakeActor`)
   * Verifying trait calculations, availability logic, and restriction vetoes in isolation
   * Setting up fixture worlds and running `*_test.star` harness scripts
   * Testing mob NPC trait adherence
   * Simulating telnet character creation sessions

---

## Essential Reference Pointers

As you read these tutorials, refer to these authoritative documents:

* **Engine Specification:** [`docs/SPEC.md`](../../../docs/SPEC.md) §4.19 (*Character Creation, Traits and
  Restrictions*) and §4.15 (*Plugins*).
* **Plugin Developer Guide:** [`docs/PLUGINS.md`](../../../docs/PLUGINS.md) for the raw API facade definitions and
  instructor review requirements.
* **The Reference Implementation:**
  [`samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt`](../../src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt).
* **The Heritage Test Suite:**
  [`samples/heritage-plugin/src/test/kotlin/gups/sample/heritage/HeritagePluginTest.kt`](../../src/test/kotlin/gups/sample/heritage/HeritagePluginTest.kt).
