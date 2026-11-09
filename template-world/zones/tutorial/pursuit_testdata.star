# Test-only chase: a line of rooms a hunter runs down, with a fast hunter that cuts its quarry
# off and a slow pursuer that only walks. pursuit_test.star drives them through the pace and the
# cut-off hook, and never in a live room.

load("//lib:pursuit.star", "cut_off")

# Eight rooms in a line; the last is a dead end.
room("chase_r0", name = "Chase 0", desc = "A stretch of road.", exits = [exit("east", ":chase_r1")])
room("chase_r1", name = "Chase 1", desc = "A stretch of road.", exits = [exit("west", ":chase_r0"), exit("east", ":chase_r2")])
room("chase_r2", name = "Chase 2", desc = "A stretch of road.", exits = [exit("west", ":chase_r1"), exit("east", ":chase_r3")])
room("chase_r3", name = "Chase 3", desc = "A stretch of road.", exits = [exit("west", ":chase_r2"), exit("east", ":chase_r4")])
room("chase_r4", name = "Chase 4", desc = "A stretch of road.", exits = [exit("west", ":chase_r3"), exit("east", ":chase_r5")])
room("chase_r5", name = "Chase 5", desc = "A stretch of road.", exits = [exit("west", ":chase_r4"), exit("east", ":chase_r6")])
room("chase_r6", name = "Chase 6", desc = "A stretch of road.", exits = [exit("west", ":chase_r5"), exit("east", ":chase_r7")])
room("chase_r7", name = "Chase 7", desc = "The end of the road.", flags = ["dead_end_ok"], exits = [exit("west", ":chase_r6")])

# A fast hunter: speed 300 walks several rooms a pulse, and it cuts a quarry off.
mob("hunting_cat",
    name = "a hunting cat",
    desc = "A hunting cat.",
    level = 1,
    vitals = vitals(hp = 30, move = 100),
    stats = stats(speed = 300),
    behaviors = [aggressive(toward = "players"), pursue("players")],
    before_exit = cut_off)

# A slow pursuer: speed 75 walks three rooms every four pulses. It has the same cut-off, which
# never fires because nobody it fights is slower than it.
mob("goblin_plodder",
    name = "a goblin plodder",
    desc = "A goblin plodder.",
    level = 1,
    vitals = vitals(hp = 30, move = 100),
    stats = stats(speed = 75),
    behaviors = [aggressive(toward = "players"), pursue("players")],
    before_exit = cut_off)
