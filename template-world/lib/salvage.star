# Generic item salvage command. Salvage never looks inside containers: the contents' own
# on_destroy hooks decide what happens to them.

def _kw_match(keywords, phrase):
    """True if every word of the phrase starts one of the keywords."""
    for w in [w for w in phrase.lower().split(" ") if w != ""]:
        found = False
        for kw in keywords:
            if kw.lower().startswith(w):
                found = True
                break
        if not found:
            return False
    return True

def _salvage_target(actor, phrase):
    for item in actor.inventory():
        if _kw_match(item.keywords, phrase):
            return item
    return None

def _salvage_command(ctx):
    phrase = ctx.args.strip()
    if not phrase:
        ctx.send(ctx.actor, "Salvage what?")
        return
    item = _salvage_target(ctx.actor, phrase)
    if item == None:
        ctx.send(ctx.actor, "You aren't carrying that.")
        return
    name = item.name
    if item.has_tag("no_salvage"):
        ctx.send(ctx.actor, "You can't salvage %s." % name)
        return
    ctx.send(ctx.actor, "You break down %s." % name)
    ctx.echo(ctx.actor.room, "%s breaks down %s." % (ctx.actor.name, name), exclude = [ctx.actor])
    ctx.destroy(item)

global_command("salvage", _salvage_command, lane = "tick")
