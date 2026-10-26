package gups.script

// Short examples for the HTML reference (M7.2, SPEC §4.16). They are run as harness
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
   * `MODULE.gups`, and the `package` example is `package.star` (D-172).
   */
  val FILE_FOR_SYMBOL: Map<String, String> =
    mapOf("module" to "MODULE.gups", "package" to "package.star")

  /**
   * Symbols whose example package is a `lib` package rather than a `zone`, because the declaration
   * is only allowed there (D-172).
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
                    exits = [exit("west", to = ":start", one_way = True,
                                  door = door(state = "locked", key = ":iron_key"))],
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
                    exits = [exit("south", to = ":start", one_way = True)],
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

                mob("rat", hp = 4, loot = [loot(":rat_tail", chance = 1.0, count = 1)])
            """,
          test =
            """
                w = t.world(seed = 1)
                rat = w.spawn(":rat", at = ":start")
                w.damage(rat, pct = 100)
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
                    hp = 40,
                    stats = {"str": 12, "dex": 10, "con": 12, "int": 10, "wis": 10},
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
                    reset_pulses = 1200,
                )
            """
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
                    size = LARGE,
                    flags = ["dead_end_ok"],
                    exits = [exit("south", to = ":start", one_way = True)],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":library")
                t.contains(p.do("look"), "The Library")
            """,
        ),
      "skill" to
        ex(
          pkg =
            """
                skill("firebolt",
                    kind = "spell",
                    cost = {"mana": 5},
                    warmup = 4,
                    lag = 4,
                    range = "near",
                    target = "enemy",
                    effect = damage(dice = "2d6", type = "fire"),
                )
            """
        ),
      "spawn" to
        ex(
          pkg =
            """
                mob("wolf", name = "a grey wolf", hp = 12)

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
                    checkpoint_minutes = 30,
                    start_room = "//zones/ex:start",
                )
            """
        ),

      // ---- Constants ----------------------------------------------------------------------
      "ALL" to
        ex(
          pkg =
            """
                room("closet",
                    desc = "A closet behind a thick curtain.",
                    flags = ["dead_end_ok"],
                    # Nothing gets through: no sight, sound, noise or spells.
                    exits = [exit("out", to = ":start", one_way = True, senses_blocked = ALL)],
                )
            """
        ),
      "CRAMPED" to
        ex(
          pkg =
            """
                room("crawlway", desc = "You can barely turn around.", size = CRAMPED, flags = ["dead_end_ok"])
            """
        ),
      "LARGE" to
        ex(
          pkg =
            """
                room("courtyard", desc = "A wide, open courtyard.", size = LARGE, flags = ["dead_end_ok"])
            """
        ),
      "NORMAL" to
        ex(
          pkg =
            """
                room("corridor", desc = "An ordinary corridor.", size = NORMAL, flags = ["dead_end_ok"])
            """
        ),

      // ---- Effect primitives --------------------------------------------------------------
      "apply_affect" to
        ex(
          pkg =
            """
                skill("slow",
                    kind = "spell",
                    cost = {"mana": 4},
                    range = "near",
                    effect = apply_affect(
                        name = "slowed",
                        duration = 40,
                        stat_deltas = {"dex": -2},
                        expire_msg = "You feel quick again.",
                    ),
                )
            """
        ),
      "damage" to
        ex(
          pkg =
            """
                skill("frost_shard", kind = "spell", cost = {"mana": 3}, range = "near",
                      effect = damage(dice = "1d8+1", type = "cold"))
            """
        ),
      "dispel" to
        ex(
          pkg =
            """
                # Removes "poison", "poison_strong" and every other affect named poison...
                skill("cure", kind = "spell", cost = {"mana": 6}, target = "ally",
                      effect = dispel(filter = "poison*"))
            """
        ),
      "heal" to
        ex(
          pkg =
            """
                skill("mend", kind = "spell", cost = {"mana": 5}, target = "ally",
                      effect = heal(dice = "2d4"))
            """
        ),
      "knockback" to
        ex(
          pkg =
            """
                skill("shove", cost = {"move": 10}, effect = knockback(steps = 2))
            """
        ),
      "pull" to
        ex(
          pkg =
            """
                skill("hook", cost = {"move": 10}, range = "near", effect = pull(steps = 1))
            """
        ),
      "sequence" to
        ex(
          pkg =
            """
                skill("thunderclap", kind = "spell", cost = {"mana": 8},
                      effect = sequence(damage(dice = "1d6", type = "sound"), stun(pulses = 4)))
            """
        ),
      "stun" to
        ex(
          pkg =
            """
                skill("bash", cost = {"move": 15}, lag = 8, effect = stun(pulses = 8))
            """
        ),
      "summon" to
        ex(
          pkg =
            """
                mob("wisp", name = "a glowing wisp", hp = 5)

                skill("call_wisps", kind = "spell", cost = {"mana": 10}, target = "none",
                      effect = summon(label = ":wisp", count = 2, duration = 80))
            """
        ),

      // ---- Behaviors ----------------------------------------------------------------------
      "aggressive" to
        ex(
          pkg =
            """
                mob("bandit", hp = 20, behaviors = [aggressive(toward = "players", unless_level_above = 5)])
            """
        ),
      "assist" to
        ex(
          pkg =
            """
                mob("goblin_scout", hp = 10)
                mob("goblin_chief", hp = 30, behaviors = [assist(allies = ["goblin_*"])])
            """
        ),
      "flee" to
        ex(
          pkg =
            """
                mob("hare", hp = 6, behaviors = [flee(at_hp_pct = 50)])
            """
        ),
      "follow" to
        ex(
          pkg =
            """
                mob("stray_dog", name = "a stray dog", hp = 8, behaviors = [follow(target = "players")])
            """
        ),
      "guard" to
        ex(
          pkg =
            """
                # Players can't go north while the sentry is here; other mobs can.
                mob("sentry", hp = 30, behaviors = [sentinel(), guard(exit = "north", allies = "mobs")])
            """
        ),
      "pursue" to
        ex(
          pkg =
            """
                mob("hound", hp = 15, behaviors = [pursue(target = "players")])
            """
        ),
      "scavenge" to
        ex(
          pkg =
            """
                mob("magpie", hp = 4, behaviors = [scavenge()])
            """
        ),
      "sentinel" to
        ex(
          pkg =
            """
                mob("statue", name = "a watchful statue", hp = 100, behaviors = [sentinel()])
            """
        ),
      "wander" to
        ex(
          pkg =
            """
                mob("deer", hp = 10, behaviors = [wander(radius = 2, chance = 0.2)])
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
                p = w.spawn_player("Tess", at = ":start", hp = 30)
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
                p = w.spawn_player("Tess", at = ":start", hp = 20)
                w.damage(p, amount = 5)
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
