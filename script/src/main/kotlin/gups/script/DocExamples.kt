package gups.script

// Short examples for the HTML reference. They are run as harness
// tests (DocExamplesTest), so they can't rot, and they are never written to `api.json`.

/**
 * One example for an API entry. [pkg] is Starlark for a zone package's content file, [test] the
 * body of a harness test function. At least one of them is non-blank. [module] holds extra
 * `module(...)` arguments, such as `level_mode = "deferred"`, that the example's world adds to the
 * template world's `MODULE.gups`.
 */
data class DocExample(val pkg: String = "", val test: String = "", val module: String = "")

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
  val LIB_SYMBOLS: Set<String> = setOf("global_command", "global_hook", "gmcp_package")

  private fun ex(pkg: String = "", test: String = "", module: String = ""): DocExample =
    DocExample(
      pkg = pkg.trimIndent().let { if (it.isEmpty()) it else it + "\n" },
      test = test.trimIndent().let { if (it.isEmpty()) it else it + "\n" },
      module = module.trim(),
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
      "damages" to
        ex(
          test =
            """
                w = t.world(seed = 1)
                s = damages(fire = 2) + damages(slash = 1)
                t.eq(s.fire, 2)
                t.eq(s.slash, 1)
                t.eq(s.bludgeon, 0)
            """
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
      "global_hook" to
        ex(
          pkg =
            """
                def _step(ctx):
                    ctx.send(ctx.actor, "Step %s->%s." % (ctx.old_level, ctx.new_level))

                # Only lib and rules packages may register global hooks.
                global_hook("on_level_change", _step)
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                w.award_xp(p, 1000)
                t.contains(p.output(), "Step 1->2.")
            """,
        ),
      "gmcp_package" to
        ex(
          pkg =
            """
                # Only lib and rules packages may declare GMCP packages.
                gmcp_package("Shop.Stock", doc = "Items for sale where the player stands.")
            """
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
      "item_affect" to
        ex(
          pkg =
            """
                item("charm",
                    name = "a lucky charm",
                    affects = [item_affect("luck", applies = "carried", stat_deltas = stats(dex = 1))],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                base = p.dex
                w.spawn(":charm", at = p)
                t.eq(p.dex, base + 1)
            """,
        ),
      "loot" to
        ex(
          pkg =
            """
                item("rat_tail", name = "a rat tail")

                mob("rat", vitals = vitals(hp = 4), loot = [loot(":rat_tail", chance = 100, count = 1)])
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
      "outcome" to
        ex(
          pkg =
            """
                item("chaos_wand",
                    name = "a chaos wand",
                    wear = "wield",
                    dice = "1d4",
                    procs = [
                        proc(
                            on = "hit",
                            outcomes = [
                                outcome(50, effect = damage(dice = "1d6", type = DAMAGE.fire)),
                                outcome(50, effect = heal(dice = "1d4", vital = "hp"), target = "self"),
                            ],
                        ),
                    ],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                wand = w.spawn(":chaos_wand", at = p)
                t.eq(wand.kind, "misc")
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
      "proc" to
        ex(
          pkg =
            """
                item("ember_dagger",
                    name = "an ember dagger",
                    wear = "wield",
                    dice = "1d4",
                    procs = [
                        proc(on = "hit", chance = 10, effect = damage(dice = "1d6", type = DAMAGE.fire)),
                    ],
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                dagger = w.spawn(":ember_dagger", at = p)
                t.eq(dagger.kind, "misc")
            """,
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
      "faction" to
        ex(
          pkg =
            """
                faction("goblins", standing = {":orcs": -80})
                faction("orcs")

                room("clearing",
                    name = "Forest Clearing",
                    desc = "A quiet clearing between the trees.",
                    flags = ["dead_end_ok"],
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
      "standing_delta" to
        ex(
          pkg =
            """
                # Helper for rules.standing_change:
                def _change(ctx):
                    return [standing_delta(ctx.actor, ctx.target, -25)]
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
                        stat("wis", label = "Wisdom"), stat("cha", label = "Charisma"),
                        stat("speed", label = "Speed"), "armor",
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
                        stat("wis", label = "Wisdom"), stat("cha", label = "Charisma"),
                        stat("speed", label = "Speed"), "armor",
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
                        stat("wis", label = "Wisdom"), stat("cha", label = "Charisma"),
                        stat("speed", label = "Speed"), "armor",
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
      "SEMI_PERMANENT" to
        ex(
          pkg =
            """
                # A burn that never ends on its own; dispel it to put it out.
                skill("immolate", kind = "spell", cost = vitals(mana = 5), range = "near",
                      effect = apply_affect(name = "burning", duration = SEMI_PERMANENT,
                                             flags = ["burning"]))
            """
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

      // ---- Ctx verbs ----------------------------------------------------------------------
      "ctx.level_down" to
        ex(
          pkg =
            """
                def _drain(ctx):
                    # Steal up to 250 XP from the victim and award it to the caster.
                    stolen = ctx.take_xp(ctx.target, 250)
                    if stolen > 0:
                        ctx.award_xp(ctx.actor, stolen)
                    # Drain one level from the victim, undoing exactly what that level gave them.
                    ctx.level_down(ctx.target)

                skill("vampiric_drain",
                    kind = "spell",
                    range = "near",
                    target = "enemy",
                    resolve = _drain,
                )
            """,
          test =
            """
                w = t.world(seed = 2)
                v = w.spawn_player("Vlad", at = ":start", skills = {":vampiric_drain": 100})
                p = w.spawn_player("Tess", at = ":start")
                hp0 = p.max_hp
                w.award_xp(p, 1000)
                t.eq(p.level, 2)
                t.eq(p.xp, 1000)
                t.true(p.max_hp > hp0)
                v.do("cast vampiric_drain Tess")
                t.eq(p.level, 1)
                t.eq(p.max_hp, hp0)
                t.eq(p.xp, 750)
                t.eq(v.xp, 250)
                t.contains(p.output(), "You have fallen to level 1.")
            """,
        ),
      "ctx.level_up" to
        ex(
          module = "level_mode = \"deferred\"",
          pkg =
            """
                def _train(ctx):
                    if ctx.actor.pending_levels > 0:
                        # level_up would also return False, changing nothing, with none pending.
                        ctx.level_up(ctx.actor)
                    else:
                        ctx.send(ctx.actor, "You have nothing to learn.")

                room("hall",
                    name = "Training Hall",
                    desc = "A quiet hall where adventurers train.",
                    flags = ["dead_end_ok"],
                    commands = {"train": _train},
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":hall")
                w.award_xp(p, 1000)
                t.contains(p.output(), "You feel ready to advance.")
                t.eq(p.level, 1)
                t.eq(p.pending_levels, 1)
                t.contains(p.do("score"), "You are ready to advance.")
                t.contains(p.do("train"), "You have reached level 2!")
                t.eq(p.level, 2)
                t.eq(p.pending_levels, 0)
                t.not_contains(p.do("score"), "You are ready to advance.")
            """,
        ),
      "ctx.merge_stack" to
        ex(
          pkg =
            """
                def _merge(ctx):
                    a = ctx.actor
                    ctx.give(a, ":coin", count = 5)
                    first = a.inventory()[0]
                    ctx.give(a, ":coin", count = 3)
                    second = a.inventory()[1]
                    merged = ctx.merge_stack(second)
                    ctx.send(a, "count=%d stacks=%d" % (merged.count, len(a.inventory())))

                room("vault",
                    desc = "A strongbox vault.",
                    flags = ["dead_end_ok"],
                    commands = {"merge": _merge},
                )
                item("coin", name = "a coin", plural = "coins", stackable = True)
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":vault")
                t.contains(p.do("merge"), "count=8 stacks=1")
            """,
        ),
      "ctx.plan" to
        ex(
          pkg =
            """
                def _plan(ctx):
                    actions = [
                        {"name": "fetch_key", "effects": {"has_key": True}},
                        {"name": "unlock_door", "pre": {"has_key": True}, "effects": {"door_unlocked": True}},
                        {"name": "open_door", "pre": {"door_unlocked": True}, "effects": {"door_open": True}},
                    ]
                    plan = ctx.plan({"has_key": False}, actions, {"door_open": True})
                    ctx.send(ctx.actor, "plan=" + "->".join(plan))

                room("workshop",
                    desc = "A cluttered workshop.",
                    flags = ["dead_end_ok"],
                    commands = {"solve": _plan},
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":workshop")
                t.contains(p.do("solve"), "plan=fetch_key->unlock_door->open_door")
            """,
        ),
      "ctx.clear_contributions" to
        ex(
          pkg =
            """
                def _reset(ctx):
                    # Empty the whole contribution record, then remove one entry specifically.
                    ctx.clear_contributions(ctx.actor)
                    ctx.clear_contributions(ctx.actor, ctx.actor)
                    ctx.send(ctx.actor, "record cleared")

                room("pit",
                    name = "The Pit",
                    desc = "A dank pit.",
                    flags = ["dead_end_ok"],
                    commands = {"reset": _reset},
                )
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":pit")
                t.contains(p.do("reset"), "record cleared")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.memories" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard")

                def _recall(ctx):
                    guard = ctx.here("guard")
                    ctx.remember(guard, ctx.actor, "taunt", amount = 5)
                    mems = ctx.memories(guard, subject = ctx.actor)
                    ctx.send(ctx.actor, "event=%s amount=%d" % (mems[0].event, mems[0].amount))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"recall": _recall})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("recall"), "event=taunt amount=5")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.remember" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard")

                def _note(ctx):
                    guard = ctx.here("guard")
                    ctx.remember(guard, ctx.actor, "taunt", amount = 10)
                    ctx.send(ctx.actor, "count=%d" % len(ctx.memories(guard, event = "taunt")))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"note": _note})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("note"), "count=1")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.forget" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard")

                def _amnesia(ctx):
                    guard = ctx.here("guard")
                    ctx.remember(guard, ctx.actor, "taunt")
                    removed = ctx.forget(guard, subject = ctx.actor, event = "taunt")
                    ctx.send(ctx.actor, "removed=%d left=%d" % (removed, len(ctx.memories(guard, event = "taunt"))))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"amnesia": _amnesia})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("amnesia"), "removed=1 left=0")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "rules_ctx.memories" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard", behaviors = [aggressive(prefer = "grudge")])

                def _taunt(ctx):
                    ctx.remember(ctx.here("guard"), ctx.actor, "taunt", amount = 10)
                    ctx.send(ctx.actor, "mems=%d" % len(ctx.memories(ctx.here("guard"), event = "taunt")))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"taunt": _taunt})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("taunt"), "mems=1")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.group_add" to
        ex(
          pkg =
            """
                def _join(ctx):
                    ctx.group_add(ctx.actor, ctx.here(ctx.args))
                    ctx.send(ctx.actor, "joined")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"join": _join})
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                w.spawn_player("Quinn", at = ":camp")
                t.contains(p.do("join Quinn"), "joined")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.group_remove" to
        ex(
          pkg =
            """
                def _join(ctx):
                    ctx.group_add(ctx.actor, ctx.here(ctx.args))
                def _leave(ctx):
                    ctx.group_remove(ctx.actor)
                    ctx.send(ctx.actor, "left")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"join": _join, "leave": _leave})
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                w.spawn_player("Quinn", at = ":camp")
                p.do("join Quinn")
                t.contains(p.do("leave"), "left")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.group_set_leader" to
        ex(
          pkg =
            """
                def _join(ctx):
                    ctx.group_add(ctx.actor, ctx.here(ctx.args))
                def _lead(ctx):
                    ctx.group_set_leader(ctx.actor)
                    ctx.send(ctx.actor, "leads")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"join": _join, "lead": _lead})
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                w.spawn_player("Quinn", at = ":camp")
                p.do("join Quinn")
                t.contains(p.do("lead"), "leads")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.group_disband" to
        ex(
          pkg =
            """
                def _join(ctx):
                    ctx.group_add(ctx.actor, ctx.here(ctx.args))
                def _disband(ctx):
                    ctx.group_disband(ctx.actor)
                    ctx.send(ctx.actor, "disbanded")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"join": _join, "disband": _disband})
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                w.spawn_player("Quinn", at = ":camp")
                p.do("join Quinn")
                t.contains(p.do("disband"), "disbanded")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.set_owner" to
        ex(
          pkg =
            """
                def _adopt(ctx):
                    ctx.set_owner(ctx.here("wolf"), ctx.actor)
                    ctx.send(ctx.actor, "adopted")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"adopt": _adopt})
                mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10))
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                wolf = w.spawn(":wolf", at = ":camp")
                t.contains(p.do("adopt"), "adopted")
                t.eq(wolf.owner.name, "Tess")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.clear_owner" to
        ex(
          pkg =
            """
                def _adopt(ctx):
                    ctx.set_owner(ctx.here("wolf"), ctx.actor)
                def _release(ctx):
                    ctx.clear_owner(ctx.here("wolf"))
                    ctx.send(ctx.actor, "released")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"adopt": _adopt, "release": _release})
                mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10))
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                wolf = w.spawn(":wolf", at = ":camp")
                p.do("adopt")
                t.contains(p.do("release"), "released")
                t.eq(wolf.owner, None)
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.set_leash" to
        ex(
          pkg =
            """
                def _unleash(ctx):
                    ctx.set_leash(ctx.here("wolf"), None)
                    ctx.send(ctx.actor, "unleashed")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"unleash": _unleash})
                mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10), leash = 4)
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                wolf = w.spawn(":wolf", at = ":camp")
                t.eq(wolf.leash, 4)
                t.contains(p.do("unleash"), "unleashed")
                t.eq(wolf.leash, None)
                t.eq(wolf.leash_overridden, True)
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.reset_leash" to
        ex(
          pkg =
            """
                def _set(ctx):
                    ctx.set_leash(ctx.here("wolf"), 1)
                def _reset(ctx):
                    ctx.reset_leash(ctx.here("wolf"))
                    ctx.send(ctx.actor, "reset")

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"set": _set, "reset": _reset})
                mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10), leash = 4)
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                wolf = w.spawn(":wolf", at = ":camp")
                p.do("set")
                t.eq(wolf.leash, 1)
                t.contains(p.do("reset"), "reset")
                t.eq(wolf.leash, 4)
                t.eq(wolf.leash_overridden, False)
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.group_of" to
        ex(
          pkg =
            """
                def _join(ctx):
                    ctx.group_add(ctx.actor, ctx.here(ctx.args))
                def _who(ctx):
                    g = ctx.group_of(ctx.actor)
                    ctx.send(ctx.actor, "group %s" % (g.id if g else "none"))

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"join": _join, "gwho": _who})
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                w.spawn_player("Quinn", at = ":camp")
                p.do("join Quinn")
                t.contains(p.do("gwho"), "group 1")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.group_members" to
        ex(
          pkg =
            """
                def _join(ctx):
                    ctx.group_add(ctx.actor, ctx.here(ctx.args))
                def _roster(ctx):
                    ctx.send(ctx.actor, "roster %d" % len(ctx.group_members(ctx.actor)))

                room("camp", name = "Camp", desc = "A camp.", flags = ["dead_end_ok"],
                     commands = {"join": _join, "roster": _roster})
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":camp")
                w.spawn_player("Quinn", at = ":camp")
                p.do("join Quinn")
                t.contains(p.do("roster"), "roster 2")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.standing" to
        ex(
          pkg =
            """
                faction("townsfolk", standing = {":guards": 50})
                faction("guards")

                def _check(ctx):
                    ctx.adjust_standing(":townsfolk", ":guards", -10)
                    val = ctx.standing(":townsfolk", ":guards")
                    ctx.send(ctx.actor, "record=%d" % val)

                room("town", name = "Town", desc = "Town.", flags = ["dead_end_ok"],
                     commands = {"check": _check})
            """,
          test =
            """
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":town")
                t.contains(p.do("check"), "record=-10")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.disposition" to
        ex(
          pkg =
            """
                faction("townsfolk")
                mob("guard", name = "Guard", factions = [":townsfolk"])

                def _check(ctx):
                    guard = ctx.here("guard")
                    ctx.adjust_standing(":townsfolk", ctx.actor, 30)
                    ctx.adjust_standing(guard, ctx.actor, 15)
                    ctx.send(ctx.actor, "disposition=%d" % ctx.disposition(guard, ctx.actor))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"check": _check})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("check"), "disposition=45")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.factions_of" to
        ex(
          pkg =
            """
                faction("townsfolk")
                mob("guard", name = "Guard", factions = [":townsfolk"])

                def _check(ctx):
                    guard = ctx.here("guard")
                    factions = ctx.factions_of(guard)
                    ctx.send(ctx.actor, "factions=" + ",".join(factions))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"check": _check})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("check"), "factions=//zones/ex:townsfolk")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.standing_history" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard")

                def _history(ctx):
                    guard = ctx.here("guard")
                    ctx.adjust_standing(guard, ctx.actor, 25)
                    h = ctx.standing_history(guard, ctx.actor)
                    ctx.send(ctx.actor, "gained=%d lost=%d" % (h.gained, h.lost))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"history": _history})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("history"), "gained=25 lost=0")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.standing_event" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard")

                def _event(ctx):
                    guard = ctx.here("guard")
                    ctx.standing_event(guard, ctx.actor, "custom_event")
                    ctx.send(ctx.actor, "event raised")

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"event": _event})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("event"), "event raised")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.adjust_standing" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard")

                def _adjust(ctx):
                    guard = ctx.here("guard")
                    ctx.adjust_standing(guard, ctx.actor, 15)
                    ctx.send(ctx.actor, "standing=%d" % ctx.standing(guard, ctx.actor))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"adjust": _adjust})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("adjust"), "standing=15")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      "ctx.clear_standing" to
        ex(
          pkg =
            """
                mob("guard", name = "Guard")

                def _wipe(ctx):
                    guard = ctx.here("guard")
                    ctx.adjust_standing(guard, ctx.actor, 20)
                    ctx.clear_standing(guard, ctx.actor)
                    ctx.send(ctx.actor, "standing=%d" % ctx.standing(guard, ctx.actor))

                room("post", name = "Post", desc = "Post.", flags = ["dead_end_ok"],
                     commands = {"wipe": _wipe})
            """,
          test =
            """
                w = t.world(seed = 1)
                w.spawn(":guard", at = ":post")
                p = w.spawn_player("Tess", at = ":post")
                t.contains(p.do("wipe"), "standing=0")
                for d in w.diagnostics():
                    t.fail("%s" % d.message)
            """,
        ),
      // ---- Behaviors ----------------------------------------------------------------------
      "aggressive" to
        ex(
          pkg =
            """
                mob("bandit", vitals = vitals(hp = 20), behaviors = [aggressive(toward = "players", unless_level_above = 5, chance = 50)])
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
      "retarget" to
        ex(
          pkg =
            """
                mob("ogre", vitals = vitals(hp = 50), behaviors = [aggressive(prefer = "weakest"), retarget(prefer = "weakest", chance = 50)])
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
      "heel" to
        ex(
          pkg =
            """
                # Idle until the mob has an owner; then it trails the owner anywhere.
                mob("tame_hound", vitals = vitals(hp = 15), behaviors = [heel(priority = 40)])
            """
        ),
      "defend" to
        ex(
          pkg =
            """
                # Joins whatever its owner, or the owner's group, is fighting.
                mob("guard_dog", vitals = vitals(hp = 15), behaviors = [heel(), defend(group = True)])
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
                mob("deer", vitals = vitals(hp = 10), behaviors = [wander(radius = 2, chance = 20)])
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
