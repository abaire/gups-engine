# Tests for pet ownership, orders, and owner-bound combat against the template world.

def _player(t, name, at = ":arena", **kw):
    w = t.world(seed = 1)
    p = w.spawn_player(name, at = at, **kw)
    w.set_skill(p, "//skills/arcane:familiar", 100)
    return w, p

def _pool(victim_level, killer_level):
    diff = victim_level - killer_level
    pct = max(0, min(150, 100 + 20 * diff))
    return 50 * victim_level * pct // 100

def test_familiar_follows_owner_and_does_not_wander(t):
    w, caster = _player(t, "Tess", ":arena")
    caster.do("cast familiar")
    familiars = [a for a in caster.room.actors() if a.label == "//zones/tutorial:familiar"]
    t.eq(len(familiars), 1)
    familiar = familiars[0]
    t.eq(familiar.owner, caster)

    # Move two rooms away: :arena -> south -> :hall -> south -> :gate
    caster.do("south")
    caster.do("south")
    t.eq(caster.room.label, "//zones/tutorial:gate")

    # heel follows the owner across any distance
    w.run(pulses = 10)
    t.eq(familiar.room.label, "//zones/tutorial:gate")

    # Although its prototype declared wander(), the owned behavior list runs instead
    # and does not wander away.
    w.run(pulses = 100)
    t.eq(familiar.room.label, "//zones/tutorial:gate")

def test_order_kill_engages_pet_and_owner(t):
    w, caster = _player(t, "Tess", ":arena")
    caster.do("cast familiar")
    familiars = [a for a in caster.room.actors() if a.label == "//zones/tutorial:familiar"]
    t.eq(len(familiars), 1)
    familiar = familiars[0]
    dummy = [a for a in caster.room.actors() if a.label == "//zones/tutorial:training_dummy"][0]

    out = caster.do("order familiar kill dummy")
    t.contains(out, "You order a spectral familiar to 'kill dummy'.")

    # When the pet's attack lands, both pet and owner become engaged with the dummy.
    w.run(pulses = 1)
    t.contains([a.id for a in familiar.fighting()], dummy.id)
    t.contains([a.id for a in caster.fighting()], dummy.id)
    t.contains([a.id for a in dummy.fighting()], familiar.id)
    t.contains([a.id for a in dummy.fighting()], caster.id)

def test_caster_flee_disengages_familiar(t):
    w, caster = _player(t, "Tess", ":arena")
    caster.do("cast familiar")
    familiars = [a for a in caster.room.actors() if a.label == "//zones/tutorial:familiar"]
    familiar = familiars[0]
    dummy = [a for a in caster.room.actors() if a.label == "//zones/tutorial:training_dummy"][0]

    caster.do("order familiar kill dummy")
    w.run(pulses = 1)
    t.contains([a.id for a in familiar.fighting()], dummy.id)
    t.contains([a.id for a in caster.fighting()], dummy.id)

    # Caster flees: pet disengages from the opponent as well.
    w.force_rng(ints = [1])
    caster.do("flee")
    t.eq(len(caster.fighting()), 0)
    t.eq(len(familiar.fighting()), 0)
    t.eq(len(dummy.fighting()), 0)

def test_sequence_exploit_dropped_when_owner_not_in_room(t):
    w, caster = _player(t, "Tess", ":arena")
    caster.do("cast familiar")
    familiars = [a for a in caster.room.actors() if a.label == "//zones/tutorial:familiar"]
    familiar = familiars[0]
    guide = [a for a in w.room("//zones/tutorial:hall").actors() if a.label == "//zones/tutorial:guide"][0]

    # Order familiar south, then order familiar kill guide.
    caster.do("order familiar south")
    caster.do("order familiar kill guide")

    # Pulse 1: familiar moves south into :hall (caster stays in :arena).
    w.run(pulses = 1)
    t.eq(familiar.room.label, "//zones/tutorial:hall")
    t.eq(caster.room.label, "//zones/tutorial:arena")

    # Pulse 2: familiar attempts to execute the ordered kill, but caster is not in :hall.
    # The action is dropped without engaging.
    w.run(pulses = 1)
    t.eq(len(familiar.fighting()), 0)
    t.eq(len(guide.fighting()), 0)
    t.contains(caster.output(), "A spectral familiar hesitates without you nearby.")

def test_kill_by_familiar_pays_caster_through_xp_share(t):
    w, caster = _player(t, "Tess", ":arena")
    caster.do("cast familiar")
    familiars = [a for a in caster.room.actors() if a.label == "//zones/tutorial:familiar"]
    familiar = familiars[0]

    dummy = [a for a in caster.room.actors() if a.label == "//zones/tutorial:training_dummy"][0]
    w.set_attr(dummy, "mending", True)
    w.damage(dummy, "hp", amount = dummy.max_hp - 4)
    dummy_level = dummy.level

    caster.do("order familiar kill dummy")
    # Run combat until the dummy dies.
    w.run(pulses = 100)
    t.eq(dummy.condition, "dead")
    t.eq(caster.xp, _pool(dummy_level, caster.level))
