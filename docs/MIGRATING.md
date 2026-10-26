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
