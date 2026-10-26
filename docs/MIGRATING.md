# Migrating Between api_versions

Breaking script-facing changes per `api_version`, with before/after snippets.

## api_version 2 (unreleased)

### `item(equip_affects)` entries can be timed, and a stun must be
- **kind:** `signature`
- **symbol:** `item.equip_affects`

An `equip_affects` entry is now a flag name, held while the item is worn, or
`{"flag": ..., "pulses": n}`, applied for `n` pulses when the item is equipped. Worn items'
affects are real affects on the wearer (SPEC §4.6). A bare `"stunned"` no longer loads.

```python before
item("dazing_helm", wear = "head", equip_affects = ["stunned"])
```

```python after
item("dazing_helm", wear = "head", equip_affects = [{"flag": "stunned", "pulses": 4}])
```

### `exit(blocks)` is now `exit(senses_blocked)`
- **kind:** `renamed`
- **symbol:** `exit.blocks`
- **replacement:** `exit.senses_blocked`

The name says the channels are senses an exit stops, not whether it can be walked through.

```python before
exit("east", to = ":long_hall", blocks = ALL)
```

```python after
exit("east", to = ":long_hall", senses_blocked = ALL)
```

### `door(open_blocks)` is now `door(open_senses_blocked)`
- **kind:** `renamed`
- **symbol:** `door.open_blocks`
- **replacement:** `door.open_senses_blocked`

Renamed to match `exit(senses_blocked)`.

### `door(closed_blocks)` is now `door(closed_senses_blocked)`
- **kind:** `renamed`
- **symbol:** `door.closed_blocks`
- **replacement:** `door.closed_senses_blocked`

Renamed to match `exit(senses_blocked)`.

### `ctx.set_exit_blocks` is now `ctx.set_exit_senses_blocked`
- **kind:** `renamed`
- **symbol:** `ctx.set_exit_blocks`
- **replacement:** `ctx.set_exit_senses_blocked`

Renamed to match `exit(senses_blocked)`; the arguments are unchanged.

### The exit view's `blocks` is now `senses_blocked`
- **kind:** `renamed`
- **symbol:** `view:exit.blocks`
- **replacement:** `view:exit.senses_blocked`

Renamed to match `exit(senses_blocked)`.
