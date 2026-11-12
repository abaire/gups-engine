# World Builder's Guide — Starlark Tutorial Series

Welcome to world building for GUPS! This series of step-by-step tutorials teaches you how to design, build, script, and
test content for a GUPS course world using Starlark.

The canonical reference implementation for these guides is the heavily commented tutorial zone located in
[`template-world/zones/tutorial/`](../../zones/tutorial). While those `.star` files demonstrate working code, these
guides serve as their higher-level companion: explaining the underlying engine architecture, execution model, state
persistence, and linking to official specifications and generated API references.

---

## Tutorial Roadmap

1. **[01. Zone Foundations & Architecture](01_zone_foundations.md)**
   * Packages, directory structure, and `package.star`
   * Label addressing (`//zones/tutorial:gate` vs `:gate`) and file loading
   * Developer workflow: running the dev server, connecting via telnet, and linting
2. **[02. Rooms, Exits, and Navigation](02_rooms_and_navigation.md)**
   * Declaring rooms with `room()`
   * Exits, direction matching, and one-way passages
   * Doors, locks, and paired door mechanics
   * Room spatial tracks (`SIZE.cramped`, `SIZE.normal`, `SIZE.large`)
   * Custom room commands and zone verbs
   * Room and region rules modifiers (`modifiers = {...}`)
3. **[03. Items, Equipment, and Containers](03_items_and_equipment.md)**
   * Declaring items with `item()`
   * Keywords and player item matching
   * Weapons, armor slots, stat deltas, and item modifiers (including lag modifiers)
   * Interactive items and consumables (`on_use`)
   * Containers, locks, and capacity
   * Item affects, tags, and prototype transforms
4. **[04. State, Attributes, and Persistence](04_state_and_attributes.md)**
   * Entity attributes (`ctx.get_attr`, `ctx.set_attr`)
   * Scope: Entity-attached vs room-attached state
   * Automatic package namespacing and write isolation
   * Database persistence across disconnects and restarts
   * Shared library state and quest progress tracking
5. **[05. Mobs, Spawns, and Behaviors](05_mobs_and_behaviors.md)**
   * Declaring mobs with `mob()`, levels, vitals, and stats
   * Spawns, respawn timers, and zone resets
   * Built-in behaviors: `sentinel`, `wander`, `aggressive`, `assist`, `flee`
   * Custom AI with the `think` hook
6. **[06. Hooks, Perception, and Interactivity](06_hooks_and_interactivity.md)**
   * The lifecycle hook catalog (`on_enter`, `on_say`, `on_death`, `on_reset`)
   * Vetoing player actions with `before_*` hooks and `ctx.deny()`
   * Sensory perception (`senses`, `sense_deltas`, `on_perceive`, `ctx.emit`)
   * The rules that decide who notices what (`sense_cost`, `can_perceive`, `perceive_message`, `door_intensity`)
   * Reacting to level changes with `on_level_change`
   * Asynchronous timers with `ctx.after()`
7. **[07. Testing and Verifying Your Zone](07_testing_your_zone.md)**
   * The Starlark test harness API (`*_test.star`)
   * Spawning virtual players and executing simulated commands
   * Fast-forwarding world time and asserting outcomes
   * Test-only fixture files (`*_testdata.star`)
   * Running tests (`gups test`) and static checks (`gups lint`)
8. **[08. Shops: The `//lib:shop.star` Library](08_shops.md)**
   * Defining a shop with `shop.define`: stock lines, the till, and prices from `item(value=)`
   * The stock container: locked chests, burglary, and time-based restocking
   * Peddlers: carried packs that drop on death
   * The `shop_price` custom hook: charisma discounts and stolen-goods refusals
   * The `Shop.Stock` GMCP event and plugin handlers joining the hook
9. **[09. Trainers: The `//lib:trainer.star` Library](09_trainers.md)**
   * Skills, proficiency, and the `skills` command
   * Defining a trainer with `trainer.define`, `teach`, and `open`
   * Practice sessions earned by changing level and spent with `practice`
   * The `practice_offer` custom hook and class caps via `learn` restrictions
10. **[10. Groups and Shared Experience: The `//lib:group.star` Library](10_groups_and_shared_experience.md)**
    * The contribution ledger and the `xp_share` rules export
    * Tuning the template formula
    * Player groups: `invite`, `accept`, `leave`, `kick`, `leader`, `disband`, `group` and `gtell`
    * Clearing contributions from `on_disengage`
11. **[11. Standing and Factions: Opinions, Grudges, and Disposition Thresholds](11_standing_and_factions.md)**
    * Declaring factions with `faction()` and mob memberships with `mob(factions = [...])`
    * Standing records, and disposition: `declared`, `faction_to_actor`, `faction_to_faction`, and `personal`
    * The `standing_change` rules export, a gratitude ceiling, and history rate capping
    * Behavior disposition thresholds (`aggressive`, `guard`, `assist`, `follow`, `pursue`)
12. **[12. Crafting: The `//lib:craft.star` Library](12_crafting.md)**
    * Stations: rooms, ground items and carried kits with a `craft_recipes` hook
    * Writing recipes with `craft.recipe`, `need`, `make` and `improve`; failure losses and tool charges
    * Skill rolls, difficulty and quality tiers
    * The `craft_check` and `craft_done` hooks, learned recipes, and the `Craft.*` GMCP events
    * Under the hood: timed tasks, `ctx.skill_roll` and `ctx.set_item_name`

---

## Essential Reference Pointers

As you work through these tutorials, you will frequently consult these sources:

* **Offline HTML Documentation:** Run `gups docs --out build/docs` and open `build/docs/index.html` in your browser. It
  includes:
  * `declarations.html`: Comprehensive parameter lists, types, defaults, and verified examples for declarations (`room`,
    `mob`, `item`, `exit`, etc.).
  * `ctx.html`: Every method and property available on the `ctx` object during hooks and commands.
  * `hooks.html`: Complete list of hooks, when they trigger, and what arguments/context they receive.
  * `rules.html` and `world.html`: The world schema (stats, vitals, damage types, channels).
* **The Engine Specification:** [`docs/SPEC.md`](../../../docs/SPEC.md) is the authoritative architecture document.
* **The Reference Zone:** [`template-world/zones/tutorial/`](../../zones/tutorial) is tested on every engine commit to
  guarantee it remains 100% functional and up to date.
