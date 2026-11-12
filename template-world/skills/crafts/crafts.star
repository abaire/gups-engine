# The crafting skills. A craft skill is never cast: //lib:craft.star rolls it with
# ctx.skill_roll when a recipe names it, and rules/combat.star's cast_allowed refuses `cast`.

skill("smithing",
      kind = "craft",
      target = "none",
      range = "self",
      tags = ["craft"],
)
