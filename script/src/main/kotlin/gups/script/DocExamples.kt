package gups.script

// Short examples for the HTML reference. They are run as harness
// tests (DocExamplesTest), so they can't rot, and they are never written to `api.json`.

/**
 * One example for an API entry. [pkg] is Starlark for a zone package's content file, [test] the
 * body of a harness test function. At least one is non-blank.
 */
data class DocExample(val pkg: String = "", val test: String = "")

object DocExamples {
  /**
   * Content every example's package also holds, so examples can place players and items in
   * `:start`. An example must not redeclare it.
   */
  val FIXTURE: String =
    """
    room("start", name = "Start", desc = "A bare stone room.", flags = ["dead_end_ok"])
    """
      .trimIndent() + "\n"

  /**
   * Examples whose `pkg` replaces a whole file instead of `example.star`: the `module` example is
   * `MODULE.gups`, and the `package` example is `package.star`.
   */
  val FILE_FOR_SYMBOL: Map<String, String> =
    mapOf(
      "module" to "MODULE.gups",
      "stat" to "MODULE.gups",
      "vital" to "MODULE.gups",
      "package" to "package.star",
    )

  /**
   * Symbols whose example package is a `lib` package rather than a `zone`, because the declaration
   * is only allowed there.
   */
  val LIB_SYMBOLS: Set<String> = setOf("global_command")

  private fun ex(pkg: String = "", test: String = ""): DocExample =
    DocExample(
      pkg = pkg.trimIndent().let { if (it.isEmpty()) it else it + "\n" },
      test = test.trimIndent().let { if (it.isEmpty()) it else it + "\n" },
    )

  /** Examples keyed by `api.json` symbol. */
  val ALL: Map<String, DocExample> =
    sortedMapOf(
      // ---- Declarations -----------------------------------------------------------------
      "container" to
        ex(
          pkg =
            """
                item("chest",
                    name = "a wooden chest",
                    kind = "container",
                    container = container(capacity = 5, max_weight = 50, state = "closed"),
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                chest = w.spawn(":chest", at = ":start")
                t.eq(chest.label, "//zones/ex:chest")
            """,
        ),
      "door" to
        ex(
          pkg =
            """
                item("iron_key", name = "an iron key")

                room("vault",
                    desc = "A cold vault. A heavy door leads west.",
                    flags = ["dead_end_ok"],
                    exits = [exit(DIR.west, to = ":start", one_way = True,
                                  door = door(state = DOOR.locked, key = ":iron_key"))],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":vault")
                t.contains(p.do("west"), "closed")
                t.eq(p.room, ":vault")
            """,
        ),
      "exit" to
        ex(
          pkg =
            """
                room("porch",
                    desc = "A creaky porch. The start room is south.",
                    flags = ["dead_end_ok"],
                    exits = [exit(DIR.south, to = ":start", one_way = True)],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":porch")
                p.do("south")
                t.eq(p.room, ":start")
            """,
        ),
      "global_command" to
        ex(
          pkg =
            """
                def _pulse(ctx):
                    ctx.send(ctx.actor, "It is pulse %d." % ctx.pulse)

                # Only lib and rules packages may register global commands.
                global_command("pulse", _pulse, lane = "fast")
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                t.contains(p.do("pulse"), "It is pulse")
            """,
        ),
      "item" to
        ex(
          pkg =
            """
                item("short_sword",
                    name = "a short sword",
                    desc = "A plain iron blade.",
                    kind = "melee",
                    wield = "wield",
                    dice = "1d6",
                    damage_type = "slash",
                    weight = 3,
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                w.spawn(":short_sword", at = p)
                t.contains(p.do("wield sword"), "You wield a short sword.")
            """,
        ),
      "loot" to
        ex(
          pkg =
            """
                item("rat_tail", name = "a rat tail")

                mob("rat", vitals = vitals(hp = 4), loot = [loot(":rat_tail", chance = 1.0, count = 1)])
            """,
          test =
            """
                w = t.world(seed = 1)
                rat = w.spawn(":rat", at = ":start")
                w.damage(rat, "hp", pct = 100)
                # Loot drops into the corpse.
                corpse = [i for i in w.room(":start").items() if i.kind == "corpse"][0]
                t.eq([i.label for i in corpse.contents()], ["//zones/ex:rat_tail"])
            """,
        ),
      "mob" to
        ex(
          pkg =
            """
                mob("gatekeeper",
                    name = "the gatekeeper",
                    desc = "A stern woman with a ring of keys.",
                    level = 5,
                    vitals = vitals(hp = 40),
                    stats = stats(str = 12, dex = 10, con = 12, int = 10, wis = 10),
                    behaviors = [sentinel()],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                keeper = w.spawn(":gatekeeper", at = ":start")
                t.eq(keeper.max_hp, 40)
                t.false(keeper.is_player)
            """,
        ),
      "package" to
        ex(
          pkg =
            """
                package(
                    api_version = 1,
                    kind = "zone",
                    title = "Examples",
                    reset_world_ticks = 10,
                )
            """
        ),
      "resists" to
        ex(
          pkg =
            """
                mob("salamander", vitals = vitals(hp = 20), resists = resists(fire = 100, pierce = -25))
            """,
          test =
            """
                w = t.world(seed = 1)
                s = w.spawn(":salamander", at = ":start")
                t.eq(s.resists.fire, 100)
                t.eq(s.resists.slash, 0)
            """,
        ),
      "region" to
        ex(
          pkg =
            """
                def _wading(ctx, base):
                    # Moving through water costs twice the usual movement points.
                    return base * 2

                region("shallows", modifiers = {"move_cost": _wading})

                room("ford",
                    desc = "Knee-deep water runs over smooth stones.",
                    flags = ["dead_end_ok"],
                    regions = ["shallows"],
                )
            """
        ),
      "room" to
        ex(
          pkg =
            """
                room("library",
                    name = "The Library",
                    desc = "Shelves of dusty books reach the ceiling.",
                    size = SIZE.large,
                    flags = ["dead_end_ok"],
                    exits = [exit(DIR.south, to = ":start", one_way = True)],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":library")
                t.contains(p.do("look"), "The Library")
            """,
        ),
      "stats" to
        ex(
          pkg =
            """
                item("iron_cap", kind = "armor", wear = "head", stat_deltas = stats(armor = 2, con = 1))
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start", stats = stats(con = 12))
                w.spawn(":iron_cap", at = p)
                p.do("wear cap")
                t.eq(p.con, 13)
                t.eq(p.base.con, 12)
            """,
        ),
      "skill" to
        ex(
          pkg =
            """
                skill("firebolt",
                    kind = "spell",
                    cost = vitals(mana = 5),
                    warmup = 4,
                    lag = 4,
                    range = "near",
                    target = "enemy",
                    effect = damage(dice = "2d6", type = DAMAGE.fire),
                )
            """
        ),
      "vitals" to
        ex(
          pkg =
            """
                skill("ember", kind = "spell", cost = vitals(mana = 5, move = 2), range = "near",
                      effect = damage(dice = "1d4", type = DAMAGE.fire))
                mob("scarecrow", vitals = vitals(hp = 15))
            """,
          test =
            """
                w = t.world(seed = 1)
                s = w.spawn(":scarecrow", at = ":start")
                t.eq(s.max_hp, 15)
                t.eq(s.hp, 15)
            """,
        ),
      "spawn" to
        ex(
          pkg =
            """
                mob("wolf", name = "a grey wolf", vitals = vitals(hp = 12))

                room("den",
                    desc = "A den that smells of wet fur.",
                    flags = ["dead_end_ok"],
                    spawns = [spawn(":wolf", count = 2, respawn_pulses = 600, at_index = 1)],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                t.eq(len(w.room(":den").actors()), 2)
            """,
        ),
      "struct" to
        ex(
          pkg =
            """
                def _shout(ctx, text):
                    ctx.echo(ctx.actor.room, text.upper())

                # Other files load(":example.star", "loud") and call loud.shout(ctx, "hi").
                loud = struct(shout = _shout)
            """
        ),

      // ---- Settings -----------------------------------------------------------------------
      "module" to
        ex(
          pkg =
            """
                module(
                    api_version = 1,
                    # Each stat and vital is a name, or stat(...) / vital(...) with a label.
                    stats = [
                        stat("str", label = "Strength"), stat("dex", label = "Dexterity"),
                        stat("con", label = "Constitution"), stat("int", label = "Intelligence"),
                        stat("wis", label = "Wisdom"), "armor",
                    ],
                    vitals = [vital("hp", label = "Hit points"), "mana", vital("move", label = "Movement")],
                    damage_types = ["bludgeon", "slash", "pierce", "fire"],
                    unarmed_damage_type = "bludgeon",
                    channels = ["spell"],
                    prompt = "<{hp}hp {mana}m {move}mv> ",
                    checkpoint_minutes = 30,
                    start_room = "//zones/ex:start",
                )
            """
        ),
      "stat" to
        ex(
          pkg =
            """
                module(
                    api_version = 1,
                    # Each stat and vital is a name, or stat(...) / vital(...) with a label.
                    stats = [
                        stat("str", label = "Strength"), stat("dex", label = "Dexterity"),
                        stat("con", label = "Constitution"), stat("int", label = "Intelligence"),
                        stat("wis", label = "Wisdom"), "armor",
                    ],
                    vitals = [vital("hp", label = "Hit points"), "mana", vital("move", label = "Movement")],
                    damage_types = ["bludgeon", "slash", "pierce", "fire"],
                    unarmed_damage_type = "bludgeon",
                    channels = ["spell"],
                    prompt = "<{hp}hp {mana}m {move}mv> ",
                    checkpoint_minutes = 30,
                    start_room = "//zones/ex:start",
                )
            """
        ),
      "vital" to
        ex(
          pkg =
            """
                module(
                    api_version = 1,
                    # Each stat and vital is a name, or stat(...) / vital(...) with a label.
                    stats = [
                        stat("str", label = "Strength"), stat("dex", label = "Dexterity"),
                        stat("con", label = "Constitution"), stat("int", label = "Intelligence"),
                        stat("wis", label = "Wisdom"), "armor",
                    ],
                    vitals = [vital("hp", label = "Hit points"), "mana", vital("move", label = "Movement")],
                    damage_types = ["bludgeon", "slash", "pierce", "fire"],
                    unarmed_damage_type = "bludgeon",
                    channels = ["spell"],
                    prompt = "<{hp}hp {mana}m {move}mv> ",
                    checkpoint_minutes = 30,
                    start_room = "//zones/ex:start",
                )
            """
        ),

      // ---- Constants ----------------------------------------------------------------------
      "DIR" to
        ex(
          pkg =
            """
                room("crossroads",
                    name = "The Crossroads",
                    desc = "A fork in the path.",
                    flags = ["dead_end_ok"],
                    exits = [
                        exit(DIR.north, to = ":start", one_way = True),
                    ],
                )
            """
        ),
      "MODIFIER" to
        ex(
          pkg =
            """
                def _dampen(ctx, base):
                    return max(1, base // 2)

                room("padded_room",
                    flags = ["dead_end_ok"],
                    modifiers = {MODIFIER.damage: _dampen},
                )
            """
        ),
      "SIZE" to
        ex(
          pkg =
            """
                room("crawlway", desc = "You can barely turn around.", size = SIZE.cramped, flags = ["dead_end_ok"])
            """
        ),
      "SENSE" to
        ex(
          pkg =
            """
                room("closet",
                    desc = "A closet behind a thick curtain.",
                    flags = ["dead_end_ok"],
                    # Nothing gets through, if the rules honour the tag: no sight or sound.
                    exits = [exit(DIR.north, to = ":start", one_way = True, tags = ["sealed"])],
                )
            """
        ),
      "DOOR" to
        ex(
          pkg =
            """
                room("hall",
                    flags = ["dead_end_ok"],
                    exits = [exit(DIR.north, to = ":start", one_way = True, door = door(state = DOOR.closed))],
                )
            """
        ),
      "DAMAGE" to
        ex(
          pkg =
            """
                # A misspelling such as DAMAGE.fier fails at that line, with a suggestion.
                item("flame_tongue", kind = "melee", wield = "wield", dice = "1d6",
                     damage_type = DAMAGE.fire)
            """,
          test =
            """
                w = t.world(seed = 1)
                sword = w.spawn(":flame_tongue", at = ":start")
                t.eq(sword.damage_type, "fire")
            """,
        ),

      // ---- Effect primitives --------------------------------------------------------------
      "apply_affect" to
        ex(
          pkg =
            """
                skill("slow",
                    kind = "spell",
                    cost = vitals(mana = 4),
                    range = "near",
                    effect = apply_affect(
                        name = "slowed",
                        duration = 40,
                        stat_deltas = stats(dex = -2),
                        expire_msg = "You feel quick again.",
                    ),
                )
            """
        ),
      "damage" to
        ex(
          pkg =
            """
                skill("frost_shard", kind = "spell", cost = vitals(mana = 3), range = "near",
                      effect = damage(dice = "1d8+1", type = DAMAGE.fire))
            """
        ),
      "dispel" to
        ex(
          pkg =
            """
                # Removes "poison", "poison_strong" and every other affect named poison...
                skill("cure", kind = "spell", cost = vitals(mana = 6), target = "ally",
                      effect = dispel(name = "poison*"))
                # Lifts at most one curse, whatever it is called.
                skill("uncurse", kind = "spell", cost = vitals(mana = 8), target = "ally",
                      effect = dispel(flag = "curse", count = 1))
            """
        ),
      "heal" to
        ex(
          pkg =
            """
                skill("mend", kind = "spell", cost = vitals(mana = 5), target = "ally",
                      effect = heal(dice = "2d4", vital = "hp"))
            """
        ),
      "knockback" to
        ex(
          pkg =
            """
                skill("shove", cost = vitals(move = 10), effect = knockback(steps = 2))
            """
        ),
      "pull" to
        ex(
          pkg =
            """
                skill("hook", cost = vitals(move = 10), range = "near", effect = pull(steps = 1))
            """
        ),
      "sequence" to
        ex(
          pkg =
            """
                skill("thunderclap", kind = "spell", cost = vitals(mana = 8),
                      effect = sequence(damage(dice = "1d6", type = DAMAGE.pierce), stun(pulses = 4)))
            """
        ),
      "stun" to
        ex(
          pkg =
            """
                skill("bash", cost = vitals(move = 15), lag = 8, effect = stun(pulses = 8))
            """
        ),
      "summon" to
        ex(
          pkg =
            """
                mob("wisp", name = "a glowing wisp", vitals = vitals(hp = 5))

                skill("call_wisps", kind = "spell", cost = vitals(mana = 10), target = "none",
                      effect = summon(label = ":wisp", count = 2, duration = 80))
            """
        ),

      // ---- Behaviors ----------------------------------------------------------------------
      "aggressive" to
        ex(
          pkg =
            """
                mob("bandit", vitals = vitals(hp = 20), behaviors = [aggressive(toward = "players", unless_level_above = 5)])
            """
        ),
      "assist" to
        ex(
          pkg =
            """
                mob("goblin_scout", vitals = vitals(hp = 10))
                mob("goblin_chief", vitals = vitals(hp = 30), behaviors = [assist(allies = ["goblin_*"])])
            """
        ),
      "flee" to
        ex(
          pkg =
            """
                mob("hare", vitals = vitals(hp = 6), behaviors = [flee(below = vitals(hp = 50))])
            """
        ),
      "follow" to
        ex(
          pkg =
            """
                mob("stray_dog", name = "a stray dog", vitals = vitals(hp = 8), behaviors = [follow(target = "players")])
            """
        ),
      "guard" to
        ex(
          pkg =
            """
                # Players can't go north while the sentry is here; other mobs can.
                mob("sentry", vitals = vitals(hp = 30), behaviors = [sentinel(), guard(exit = "north", allies = "mobs")])
            """
        ),
      "pursue" to
        ex(
          pkg =
            """
                mob("hound", vitals = vitals(hp = 15), behaviors = [pursue(target = "players")])
            """
        ),
      "scavenge" to
        ex(
          pkg =
            """
                mob("magpie", vitals = vitals(hp = 4), behaviors = [scavenge()])
            """
        ),
      "sentinel" to
        ex(
          pkg =
            """
                mob("statue", name = "a watchful statue", vitals = vitals(hp = 100), behaviors = [sentinel()])
            """
        ),
      "wander" to
        ex(
          pkg =
            """
                mob("deer", vitals = vitals(hp = 10), behaviors = [wander(radius = 2, chance = 0.2)])
            """
        ),

      // ---- Harness types ------------------------------------------------------------------
      "diagnostic" to
        ex(
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                p.do("look")
                # A script error in a hook is recorded, not raised: fail on any.
                for d in w.diagnostics():
                    t.fail("%s:%s %s (%s)" % (d.file, d.line, d.message, d.kind))
            """
        ),
      "p" to
        ex(
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start", vitals = vitals(hp = 30))
                t.true(p.is_player)
                t.contains(p.do("look"), "Start")
                t.eq(p.hp, 30)
            """
        ),
      "t" to
        ex(
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                t.eq(p.room, ":start")
                t.ne(p.room, ":elsewhere")
                t.not_contains(p.do("look"), "Elsewhere")
            """
        ),
      "w" to
        ex(
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start", vitals = vitals(hp = 20))
                w.damage(p, "hp", amount = 5)
                t.eq(p.hp, 15)
                w.set_attr(p, "visits", 1)
                t.eq(w.get_attr(p, "visits"), 1)
            """
        ),
    )

  /**
   * Checks [ALL] against [model]: every key is a symbol of the model, no example is blank, and
   * every declaration, constant, effect primitive, behavior and harness type has an example.
   */
  fun problems(model: ScriptApiModel, examples: Map<String, DocExample> = ALL): List<String> {
    val problems = mutableListOf<String>()
    val symbols = model.symbols().toSet()
    for ((symbol, e) in examples) {
      if (symbol !in symbols)
        problems += "DocExamples has an example for '$symbol', which is not an api.json symbol"
      if (e.pkg.isBlank() && e.test.isBlank())
        problems += "DocExamples example for '$symbol' has neither pkg nor test"
    }
    if ("server" in examples) problems += "DocExamples can't run a 'server' example"
    val required =
      model.declarations.map { it.name } +
        model.constants.map { it.name } +
        model.effects.map { it.name } +
        model.behaviors.map { it.name } +
        model.harness.map { it.name }
    for (symbol in required) if (symbol !in examples)
      problems += "'$symbol' needs an example in DocExamples"
    return problems
  }
}
