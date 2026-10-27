# The foundry:
#
#   scrapyard ---- gate ---- assembly ---- control
#                    |           |
#                  furnace     vault ---- whisper_hall
#                                              |
#                                          reactor
#
# Arriving robots start at the gate.

room("gate",
    name = "The Foundry Gate",
    desc = "Rusted gates stand open on a yard of scrap.",
    size = LARGE,
    exits = [
        exit("west", to = ":scrapyard"),
        exit("east", to = ":assembly"),
        exit("south", to = ":furnace"),
    ],
)

room("scrapyard",
    name = "The Scrapyard",
    desc = "Heaps of twisted chassis. Something metallic rattles inside.",
    exits = [exit("east", to = ":gate")],
    spawns = [spawn(":rust_hound", count = 1)],
    flags = ["dead_end_ok"],
)

room("furnace",
    name = "The Dead Furnace",
    desc = "A cold furnace. Warning glyphs flicker on the wall.",
    exits = [exit("north", to = ":gate")],
    flags = ["dead_end_ok"],
)

room("assembly",
    name = "The Assembly Hall",
    desc = "Idle arms hang above empty cradles.",
    size = LARGE,
    exits = [
        exit("west", to = ":gate"),
        exit("east", to = ":control"),
        exit("south", to = ":vault"),
    ],
    spawns = [spawn(":welder_drone", count = 1)],
)

room("control",
    name = "The Control Room",
    desc = "Dark screens and a single working terminal.",
    exits = [exit("west", to = ":assembly")],
    flags = ["dead_end_ok"],
)

room("vault",
    name = "The Parts Vault",
    desc = "Shelves of spare plating and power cells.",
    exits = [
        exit("north", to = ":assembly"),
        exit("east", to = ":whisper_hall", senses_blocked = ["psionic"]),
    ],
)

room("whisper_hall",
    name = "The Whisper Hall",
    desc = "The air hums with something that is not quite a voice.",
    exits = [
        exit("west", to = ":vault"),
        exit("south", to = ":reactor"),
    ],
    spawns = [spawn(":whisperer", count = 1)],
)

room("reactor",
    name = "The Reactor Core",
    desc = "A cracked reactor ticks quietly. Your sensors scream.",
    exits = [exit("north", to = ":whisper_hall")],
    flags = ["dead_end_ok"],
)
