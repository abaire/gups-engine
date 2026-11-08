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

skill("self_repair",
    kind = "routine",
    cost = vitals(power = 5),
    lag = 4,
    target = "self",
    effect = heal(dice = "1d6", vital = "integrity"),
)

def _on_use_chip(ctx):
    if "//zones/foundry:self_repair" in ctx.actor.skills:
        ctx.send(ctx.actor, "You already run that routine.")
        return
    refusal = ctx.set_skill(ctx.actor, ":self_repair", 1)
    if refusal == None:
        ctx.send(ctx.actor, "The chip installs self_repair. (1%)")
    else:
        ctx.send(ctx.actor, refusal)

item("repair_chip",
    name = "a repair chip",
    keywords = ["chip", "repair"],
    kind = "consumable",
    charges = 1,
    on_use = _on_use_chip,
)
