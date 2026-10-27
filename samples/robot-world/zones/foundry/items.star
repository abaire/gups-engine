item("plasma_cutter",
    name = "a plasma cutter",
    keywords = ["cutter", "plasma"],
    kind = "melee",
    wield = "wield",
    dice = "1d8",
    damage_type = DAMAGE.energy,
    weight = 4,
)

item("scrap_plate",
    name = "a scrap plate",
    keywords = ["plate", "scrap"],
    kind = "armor",
    wear = "torso",
    stat_deltas = stats(plating = 2),
)

skill("mind_lash",
    kind = "spell",
    cost = vitals(power = 10),
    warmup = 2,
    lag = 2,
    target = "enemy",
    channels = ["psionic"],
    effect = damage(dice = "2d4", type = DAMAGE.psychic),
)

skill("overload",
    kind = "spell",
    cost = vitals(power = 15),
    lag = 4,
    effect = damage(dice = "3d4", type = DAMAGE.energy),
)
