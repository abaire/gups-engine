# 01. Zone Foundations & Architecture

This guide covers how the GUPS engine discovers, loads, and executes your zone code, how addressing labels work, and how
to run and test your package locally.

---

## 1. What is a Package?

In GUPS, all content lives in **packages**. A package is a directory in your world repository that contains a
`package.star` file:

```text
zones/goblin_caves/
├── package.star       # Required: declares the package metadata
├── caves.star         # Content declarations (rooms, mobs, items)
├── helpers.star       # Helper functions and constants
└── caves_test.star    # Automated tests (ignored during normal load)
```

### The `package.star` File

Every `package.star` must contain **exactly one** call to `package()` and nothing else:

```python
# zones/tutorial/package.star
package(
    # Script API version this package targets
    api_version=1,

    # Package kind: "zone", "lib", "rules", or "skills"
    kind="zone",
    title="The Goblin Caves",

    # Cadence for zone resets (in world ticks; 1 tick = 30 seconds)
    reset_world_ticks=10,
)
```

* **Package kinds:**
    * `zone`: Student packages holding rooms, mobs, and items.
    * `lib`: Shared utility code (e.g. `//lib:quest.star`).
    * `rules`: Global game mechanics (e.g. `//rules:combat.star`).
    * `skills`: Global spell and skill declarations.
* **Evaluation order:** The engine evaluates all non-test `.star` content files in alphabetical order. Files ending in
  `_test.star` are ignored during normal world loading and run only under `gups test`.

---

## 2. Labels, Addressing, and Imports

Understanding Starlark labels is crucial when linking rooms together, spawning mobs, or loading shared code.

### A. Loading Files (`load()`)

The `load()` statement imports functions or constants from another file:

```python
# Import from another file in the SAME package:
load(":helpers.star", "greeting", "READY_WORD")

# Import from a file in ANOTHER package:
load("//lib:quest.star", "quest")
```

> **Rule:** `package.star` cannot be loaded. Identifiers starting with an underscore (e.g. `_private_fn`) are private to
> that file and cannot be imported by other files.

### B. Referencing Content Declarations

When referring to a room, mob, or item (in an exit, spawn list, or hook), you use a **target label**:

```python
# Relative label: refers to a declaration within the CURRENT package
exit("north", to=":hall")

# Absolute label: refers to a declaration in ANY package
exit("south", to="//zones/forest:clearing")
```

> **Key Insight:** `//zones/forest:clearing` addresses the *declaration* named `clearing` in package `zones/forest`,
> regardless of which `.star` file defined it.

---

## 3. Starlark vs. Python: Important Differences

GUPS scripts are written in **Starlark** (a dialect of Python originally designed for Bazel). While syntax resembles
Python, Starlark enforces strict safety and determinism constraints:

1. **Top-Level Code is Frozen After Loading:**  
   Module-level variables are completely immutable once the file finishes evaluating.
   ```python
   # ALLOWED: Read-only constants
   MAX_GOLD = 100

   # FORBIDDEN: Mutating a global in a hook
   def _on_enter(ctx):
       # ERROR! Global variables cannot be reassigned or mutated.
       # Store dynamic state in attributes instead (see Tutorial 04).
   ```
2. **String Iteration:**  
   Starlark strings cannot be iterated over directly (`for char in text:` fails). Use `text.elems()` instead:
   ```python
   for mark in ",.!?;:'\"".elems():
       text = text.replace(mark, " ")
   ```
3. **No Non-Deterministic Behavior:**  
   There is no system clock or generic random module in Starlark. Randomness must come through seeded engine primitives
   or `ctx.random()`.

---

## 4. Local Development Workflow

When authoring a zone, you will use three primary CLI commands:

### Start the Local Dev Server

```bash
gups serve --dev --world .
```

This boots the engine with your world, starts the telnet listener on port `4000`, loads the tutorial zone, and enables
hot-reloading when `.star` files are saved.

In another terminal, connect with a telnet client:

```bash
telnet localhost 4000
```

### Run Zone Automated Tests

```bash
# Run tests for a specific zone
gups test //zones/tutorial/...

# Run all tests across the world
gups test //...
```

### Run Static Lint Checks

```bash
gups lint //...
```

`gups lint` catches syntax errors, broken label references, dead-end rooms, missing keys, and namespace collisions
without having to start the server.

---

## Reference Links

* **Reference Implementation:** [`template-world/zones/tutorial/package.star`](../../zones/tutorial/package.star)
* **Specification:** [SPEC.md §2.3: Course World Repository](../../../docs/SPEC.md#L213)
  and [§4.1: Package Declaration](../../../docs/SPEC.md#L923)
* **HTML Documentation:** `declarations.html#package` in generated `gups docs`.
