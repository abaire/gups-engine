# The foundry:
#
#   scrapyard ---- gate ---- assembly ---- control
#                    |           |
#                  furnace     vault ---- whisper_hall
#                                              |
#                                          reactor
#
# Arriving robots start at the gate.

# A server loads every package, but a test world or scenario that loads only this zone gets
# //lib (and its on_skill_use hook) only through a load. Nothing here reads the constant.
load("//lib:skill_gain.star", "USE_WINDOW_PULSES")

def _stock_gate(ctx):
    # Keep a repair chip on the floor so a new chassis can learn to self-repair.
    on_floor = [i for i in ctx.self.items() if i.label == "//zones/foundry:repair_chip"]
    if not on_floor:
        ctx.spawn(":repair_chip", ctx.self)

room("gate",
    name = "The Foundry Gate",
    desc = "Rusted gates stand open on a yard of scrap.",
    exits = [
        exit(DIR.west, to = ":scrapyard"),
        exit(DIR.east, to = ":assembly"),
        exit(DIR.south, to = ":furnace"),
    ],
    on_load = _stock_gate,
    on_reset = _stock_gate,
)

room("scrapyard",
    name = "The Scrapyard",
    desc = "Heaps of twisted chassis. Something metallic rattles inside.",
    exits = [exit(DIR.east, to = ":gate")],
    spawns = [spawn(":rust_hound", count = 1)],
)

room("furnace",
    name = "The Dead Furnace",
    desc = "A cold furnace. Warning glyphs flicker on the wall.",
    exits = [exit(DIR.north, to = ":gate")],
)

room("assembly",
    name = "The Assembly Hall",
    desc = "Idle arms hang above empty cradles.",
    exits = [
        exit(DIR.west, to = ":gate"),
        exit(DIR.east, to = ":control"),
        exit(DIR.south, to = ":vault"),
    ],
    spawns = [spawn(":welder_drone", count = 1)],
)

room("control",
    name = "The Control Room",
    desc = "Dark screens and a single working terminal.",
    exits = [exit(DIR.west, to = ":assembly")],
)

room("vault",
    name = "The Parts Vault",
    desc = "Shelves of spare plating and power cells.",
    exits = [
        exit(DIR.north, to = ":assembly"),
        exit(DIR.east, to = ":whisper_hall", tags = ["psionic_proof"]),
    ],
)

room("whisper_hall",
    name = "The Whisper Hall",
    desc = "The air hums with something that is not quite a voice.",
    exits = [
        exit(DIR.west, to = ":vault"),
        exit(DIR.south, to = ":reactor"),
    ],
    spawns = [spawn(":whisperer", count = 1)],
)

room("reactor",
    name = "The Reactor Core",
    desc = "A cracked reactor ticks quietly. Your sensors scream.",
    exits = [exit(DIR.north, to = ":whisper_hall")],
)
