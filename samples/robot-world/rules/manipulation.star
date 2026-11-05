# Door and container manipulation rules: how loud opening, closing, locking and unlocking are.

def door_intensity(ctx):
    if "silent" in ctx.tags:
        return 0
    if ctx.action in ("open", "close"):
        if "heavy" in ctx.tags or "creaky" in ctx.tags or "loud" in ctx.tags:
            return 2
        return 1
    if ctx.action in ("lock", "unlock"):
        if "clanking" in ctx.tags or "noisy_lock" in ctx.tags or "heavy" in ctx.tags:
            return 1
        return 0
    return 0

def item_power(ctx):
    # Percent strength of what ctx.item does for ctx.actor: its use_effect (ctx.event "use") or a
    # proc (ctx.event "hit", "struck" or "avoid"). 100 is the item as declared; a world can base it
    # on a skill of the user's, such as an artificer's.
    return 100
