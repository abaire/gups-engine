# Declared factions and initial opinions of other factions.
#
# Faction labels are //lib:<name>. Opinions are one-way integers in -100..100:
# -100 means absolute hatred, 0 neutral, and 100 beloved ally.
# Mobs declare their faction membership with `mob(factions = [...])`.

faction("townsfolk",
    standing = {":goblins": -80},
    doc = "Ordinary town citizens, merchants, and guards.",
)

faction("goblins",
    standing = {":townsfolk": -80},
    doc = "Wild goblin tribes of the wilderness.",
)
