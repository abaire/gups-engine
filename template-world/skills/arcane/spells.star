# The arcane school's spells. spark lashes a single enemy; mend mends the caster's wounds.

skill("spark",
      kind = "spell",
      cost = vitals(mana = 8),
      warmup = 0,
      lag = 4,
      range = "near",
      target = "enemy",
      channels = ["spell", "sound"],
      effect = damage(dice = "2d4", type = DAMAGE.fire),
)

skill("mend",
      kind = "spell",
      cost = vitals(mana = 10),
      lag = 4,
      range = "self",
      target = "self",
      effect = heal(dice = "2d6", vital = "hp"),
)
