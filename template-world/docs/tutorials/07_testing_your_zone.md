# 07. Testing and Verifying Your Zone

In GUPS, automated testing is not an afterthought: **every zone is required to include at least one `*_test.star`
file**, and CI gates verify all zone tests on every pull request.

This tutorial explains how to write unit tests using the Starlark test harness to verify your rooms, mobs, items, and
quests.

---

## 1. Anatomy of a Test File

Test files live right inside your zone package and end in `_test.star`:

```text
zones/tutorial/
├── package.star
├── rooms.star
├── mobs.star
├── items.star
└── tutorial_test.star   # Automated test suite
```

In [`tutorial_test.star`](../../zones/tutorial/tutorial_test.star):

* Any top-level function whose name begins with `test_*` is discovered and run by the test runner.
* Every test function takes a single parameter: `t` (the test harness context).

```python
def test_the_sign_can_be_read(t):
    w = t.world(seed=1)
    p = w.spawn_player("Tess", at=":gate")

    t.contains(p.do("read sign"), "Go north to meet the guide.")
    t.contains(p.do("read wall"), "nothing like that")
```

---

## 2. Booting an In-Memory Test World

The `t.world()` call boots a lightweight, isolated virtual world in memory:

```python
w = t.world(seed=1, packages=["//zones/tutorial"])
```

* **`seed`:** Guarantees deterministic pseudo-random dice rolls and spawns across runs.
* **`packages`:** Which packages to load (defaults to the current package).
* **Speed:** The virtual world runs in-process with a simulated clock, executing in milliseconds.

---

## 3. Simulating Players and Actions

Once the world is running, spawn virtual players and send commands as if connected via telnet:

```python
# Spawn player "Tess" at the tutorial gate
p = w.spawn_player("Tess", at=":gate")

# Execute a command and capture the text output:
output = p.do("north")
t.contains(output, "Welcome to the tutorial, Tess!")

# Verify room location:
t.eq(p.room, ":hall")
```

### Checking Player State:

* `p.room`: Evaluates to the current room view (compares equal to a label string like `":hall"`).
* `p.track_index`: The player's slot on the intra-room spatial track (`0` through `4`).
* `p.has_item(":brass_key")`: Checks if an item is in inventory.
* `p.hp`, `p.mana`: Inspect current vital pools.

---

## 4. Advancing Time (`w.run()`)

To test timers (`ctx.after()`), healing over time, or respawn delays, advance world time explicitly:

```python
def test_the_dummy_mends_itself(t):
    w = t.world(seed=1)

    # Locate the training dummy in the arena
    dummy = w.room(":arena").actors()[0]

    # Deal damage directly through the test harness
    w.damage(dummy, "hp", pct=60)
    t.eq(dummy.hp, 12)

    # Fast-forward virtual time by 8 pulses (2 seconds)
    w.run(pulses=8)

    # Verify the dummy repaired itself via its scheduled timer
    t.eq(dummy.hp, dummy.max_hp)
```

---

## 5. Test Harness Assertions

The `t` object provides standard assertions:

| Assertion                          | Description                                       |
|------------------------------------|---------------------------------------------------|
| `t.eq(a, b)`                       | Asserts `a == b`                                  |
| `t.ne(a, b)`                       | Asserts `a != b`                                  |
| `t.true(cond)`                     | Asserts `cond` is truthy                          |
| `t.false(cond)`                    | Asserts `cond` is falsy                           |
| `t.contains(haystack, needle)`     | Asserts substring or element is inside `haystack` |
| `t.not_contains(haystack, needle)` | Asserts substring or element is absent            |
| `t.fail("Message")`                | Unconditionally fails the test with a message     |

---

## 6. Running Tests & CI Checks

Before submitting a pull request, run your tests locally using the CLI:

```bash
# Run tests for your zone:
gups test //zones/tutorial/...

# Run all tests in the world:
gups test //...

# Run static lint checks:
gups lint //...
```

You can also run automated fuzz-testing to have autonomous bots navigate and test your zone for crashes:

```bash
gups fuzz --pulses 1000 //zones/tutorial/...
```

---

## Reference Links

* **Reference Implementation:** [
  `template-world/zones/tutorial/tutorial_test.star`](../../zones/tutorial/tutorial_test.star)
* **Specification:** [SPEC.md §4.12: Test Harness API](../../../docs/SPEC.md#L1932)
  and [§7.2: Script Tests](../../../docs/SPEC.md#L3516)
* **HTML Documentation:** `harness.html` in `gups docs`.
