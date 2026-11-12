# A mage's first spell lashes one enemy; a cleric's mends the caster.

skill("magic_missile",
      kind = "spell",
      cost = vitals(mana = 8),
      lag = 4,
      range = "near",
      target = "enemy",
      tags = ["arcane"],
      effect = damage(dice = "2d4", type = DAMAGE.fire),
)

skill("minor_heal",
      kind = "spell",
      cost = vitals(mana = 10),
      lag = 4,
      range = "self",
      target = "self",
      tags = ["divine"],
      effect = heal(dice = "2d6", vital = "hp"),
)
