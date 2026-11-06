# The arcane school's spells. spark lashes a single enemy; mend mends the caster's wounds.
# soothe heals an ally, and ward hardens an ally's armor for a while.

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

skill("soothe",
      kind = "spell",
      cost = vitals(mana = 8),
      lag = 4,
      range = "near",
      target = "ally",
      effect = heal(dice = "2d6", vital = "hp"),
)

skill("ward",
      kind = "spell",
      cost = vitals(mana = 8),
      lag = 4,
      range = "near",
      target = "ally",
      effect = apply_affect(
          name = "warded",
          duration = 60,
          stat_deltas = stats(armor = 2),
          expire_msg = "Your ward fades.",
      ),
)
