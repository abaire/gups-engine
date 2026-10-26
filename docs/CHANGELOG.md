# Changelog

Script-facing changes are listed per `api_version`, with before/after snippets for
breaking ones.

## api_version 1 (unreleased)

### Added `module.aliases`

`MODULE.gups` sets the alias words for engine commands, such as `l` for `look`. The engine no
longer has built-in aliases, so a world keeps `l`, `i`, `inv`, `eq`, `k`, `attack`, `c` and
`take` only by listing them, as the template world does.

### Added `module.prefixes`

`MODULE.gups` sets the single characters that stand for an engine command at the start of a
line, such as `'` for `say`. A world keeps `'` only by listing it, as the template world does.

### Added `server.shutdown_timeout`

`server.gups` can set how many seconds a shutdown waits for the final checkpoint.

### `dispel(filter)` is now `dispel(name, flag, all, count)`

`dispel` takes keyword criteria instead of a filter string: `name` (a trailing `*` matches a
prefix), `flag` (a string or a list), `all = True`, and an optional `count`. At least one of
`name`, `flag` or `all` is required. Affects flagged `intrinsic` are never dispelled, and a
dispelled affect now sends its `expire_msg`.

Before:

```python
skill("cure", kind = "spell", target = "ally", effect = dispel(filter = "poison*"))
```

After:

```python
skill("cure", kind = "spell", target = "ally", effect = dispel(name = "poison*"))
```

### `item(equip_affects)` entries can be timed, and a stun must be

An `equip_affects` entry is now a flag name, held while the item is worn, or
`{"flag": ..., "pulses": n}`, applied for `n` pulses when the item is equipped. Worn items'
affects are real affects on the wearer (SPEC §4.6). A bare `"stunned"` no longer loads.

Before:

```python
item("dazing_helm", wear = "head", equip_affects = ["stunned"])
```

After:

```python
item("dazing_helm", wear = "head", equip_affects = [{"flag": "stunned", "pulses": 4}])
```

### `exit(blocks)` is now `exit(senses_blocked)`

The name says the channels are senses an exit stops, not whether it can be walked through.

Before:

```python
exit("east", to = ":long_hall", blocks = ALL)
```

After:

```python
exit("east", to = ":long_hall", senses_blocked = ALL)
```

### `door(open_blocks)` is now `door(open_senses_blocked)`

Renamed to match `exit(senses_blocked)`.

### `door(closed_blocks)` is now `door(closed_senses_blocked)`

Renamed to match `exit(senses_blocked)`.

### `ctx.set_exit_blocks` is now `ctx.set_exit_senses_blocked`

Renamed to match `exit(senses_blocked)`; the arguments are unchanged.

### The exit view's `blocks` is now `senses_blocked`

Renamed to match `exit(senses_blocked)`.

## api_version 1

Initial (unstable) script API.

### Added `module.plugins`

`MODULE.gups` can pin plugin jars.
