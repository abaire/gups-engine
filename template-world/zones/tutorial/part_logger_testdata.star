# Test-only fixtures for the tutorial's tests: `gups test` and `gups scenario` load this file,
# and the live game never does. Real content can't name what is declared here.

def _log_parts(ctx):
    # Reads ctx.parts: adds each damage part's hp loss to a running total on the wearer, by
    # type, so a test can see how a mixed hit was split.
    for part in ctx.parts:
        key = "hp_" + part.type
        ctx.set_attr(ctx.target, key, ctx.get_attr(ctx.target, key, 0) + part.losses.hp)

item("part_logger",
    name = "a chipped logging pendant",
    keywords = ["logger"],
    desc = "A battered pendant that tallies the damage it takes, by kind.",
    wear = "neck",
    on_damage = _log_parts,
)
