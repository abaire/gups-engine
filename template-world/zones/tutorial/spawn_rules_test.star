# Tests for the tutorial zone's item spawn rules and spawner pool.

def test_brass_key_spawns_once_in_satchel(t):
    w = t.world(seed = 1)
    hall = w.room(":hall")
    satchel = [i for i in hall.items() if i.label == "//zones/tutorial:satchel"][0]
    t.ne(satchel.spawned_by, None)
    t.eq([i.label for i in satchel.contents()], ["//zones/tutorial:brass_key"])
    t.ne(satchel.contents()[0].spawned_by, None)

    p = w.spawn_player("Tess", at = ":hall")
    p.do("get key from satchel")
    t.true(p.has_item(":brass_key"))
    t.eq(satchel.contents(), [])

    w.reset()
    t.eq(satchel.contents(), [])

def test_armory_sword_restocks_after_being_carried_out_and_reset(t):
    w = t.world(seed = 1)
    armory = w.room(":armory")
    t.eq(len([i for i in armory.items() if i.label == "//zones/tutorial:practice_sword"]), 1)

    p = w.spawn_player("Tess", at = ":armory")
    p.do("get sword")
    # While the sword is still in the armory (carried by Tess), until = "taken" still counts it.
    w.run(pulses = 120)
    w.reset()
    t.eq([i for i in armory.items() if i.label == "//zones/tutorial:practice_sword"], [])

    # Carrying it out of the armory releases it at the next world tick, so a reset places another.
    p.do("east")
    w.run(pulses = 120)
    w.reset()
    t.eq(len([i for i in armory.items() if i.label == "//zones/tutorial:practice_sword"]), 1)

def test_stray_ring_pool_respawns_after_timer(t):
    w = t.world(seed = 1)
    c = w.ctx()
    t.eq(c.spawner_info(":stray_ring").live, 1)

    rings = [
        i for i in w.room(":gravel").items() + w.room(":tar_pit").items()
        if i.label == "//zones/tutorial:copper_ring"
    ]
    t.eq(len(rings), 1)
    t.eq(rings[0].spawned_by.id, "//zones/tutorial:stray_ring")

    c.destroy(rings[0])
    t.eq(c.spawner_info(":stray_ring").live, 0)

    # Weight 4 in {":gravel": 3, ":tar_pit": 1} picks :tar_pit.
    w.force_rng(ints = [4])
    w.run(pulses = 4800 + 120)
    t.eq(c.spawner_info(":stray_ring").live, 1)
    tar_rings = [i for i in w.room(":tar_pit").items() if i.label == "//zones/tutorial:copper_ring"]
    t.eq(len(tar_rings), 1)
    t.eq(tar_rings[0].spawned_by.id, "//zones/tutorial:stray_ring")
