# World Builder's Guide — Starlark Tutorial Series

Welcome to world building for GUPS! This series of step-by-step tutorials teaches you how to design, build, script, and
test content for a GUPS course world using Starlark.

The canonical reference implementation for these guides is the heavily commented tutorial zone located in [
`template-world/zones/tutorial/`](../../zones/tutorial). While those `.star` files demonstrate working code, these
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
    * Room spatial tracks (`CRAMPED`, `NORMAL`, `LARGE`)
    * Custom room commands and zone verbs

3. **[03. Items, Equipment, and Containers](03_items_and_equipment.md)**
    * Declaring items with `item()`
    * Keywords and player item matching
    * Weapons and armor equipment slots
    * Interactive items and consumables (`on_use`)
    * Containers, locks, and capacity

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
    * Sensory perception (`senses`, `on_nearby`, sound propagation)
    * Asynchronous timers with `ctx.after()`

7. **[07. Testing and Verifying Your Zone](07_testing_your_zone.md)**
    * The Starlark test harness API (`*_test.star`)
    * Spawning virtual players and executing simulated commands
    * Fast-forwarding world time and asserting outcomes
    * Running tests (`gups test`) and static checks (`gups lint`)

---

## Essential Reference Pointers

As you work through these tutorials, you will frequently consult these sources:

* **Offline HTML Documentation:** Run `gups docs --out build/docs` and open `build/docs/index.html` in your browser. It
  includes:
    * `declarations.html`: Comprehensive parameter lists, types, defaults, and verified examples for declarations
      (`room`, `mob`, `item`, `exit`, etc.).
    * `ctx.html`: Every method and property available on the `ctx` object during hooks and commands.
    * `hooks.html`: Complete list of hooks, when they trigger, and what arguments/context they receive.
    * `rules.html` and `world.html`: The world schema (stats, vitals, damage types, channels).
* **The Engine Specification:** [`docs/SPEC.md`](../../../docs/SPEC.md) is the authoritative architecture document.
* **The Reference Zone:** [`template-world/zones/tutorial/`](../../zones/tutorial) is tested on every engine commit to
  guarantee it remains 100% functional and up to date.
