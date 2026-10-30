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
    exits = [
        exit(DIR.west, to = ":scrapyard"),
        exit(DIR.east, to = ":assembly"),
        exit(DIR.south, to = ":furnace"),
    ],
)

room("scrapyard",
    name = "The Scrapyard",
    desc = "Heaps of twisted chassis. Something metallic rattles inside.",
    exits = [exit(DIR.east, to = ":gate")],
    spawns = [spawn(":rust_hound", count = 1)],
    flags = ["dead_end_ok"],
)

room("furnace",
    name = "The Dead Furnace",
    desc = "A cold furnace. Warning glyphs flicker on the wall.",
    exits = [exit(DIR.north, to = ":gate")],
    flags = ["dead_end_ok"],
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
    flags = ["dead_end_ok"],
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
    flags = ["dead_end_ok"],
)
