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
