# Mobs, and the hooks that give them personality.

load(":helpers.star", "READY_WORD", "said")

def _guide_hears(ctx):
    # on_say on a mob fires when someone *else* in the room speaks. ctx.text is what they
    # said and ctx.actor is who said it.
    if said(ctx.text, READY_WORD):
        # Attributes are this package's own state on any entity. Keys are namespaced
        # automatically ("ready" is stored as "tutorial:ready"), so packages never clash.
        ctx.set_attr(ctx.actor, "ready", True)
        ctx.say(ctx.self, "Then off you go, %s. The arena is north." % ctx.actor.name)
    elif said(ctx.text, "key") and not ctx.actor.has_item(":brass_key"):
        # give() spawns a fresh item straight into the player's inventory.
        ctx.give(ctx.actor, ":brass_key")
        ctx.emote(ctx.self, "hands %s a brass key." % ctx.actor.name)

def _guide_blocks(ctx):
    # A before_* hook on a mob is an *observer*: it fires for other actors' actions in the
    # mob's room. Returning ctx.deny(msg) vetoes the action, shows msg to the actor, and
    # costs them no lag. Returning anything else (or nothing) allows it.
    if ctx.direction == "north" and ctx.actor.is_player and not ctx.get_attr(ctx.actor, "ready", False):
        return ctx.deny("The guide blocks your way: say '%s' first." % READY_WORD)

mob("guide",
    name = "the guide",
    desc = "A patient old adventurer with a lantern.",
    level = 10,
    hp = 200,
    on_say = _guide_hears,
    before_exit = _guide_blocks,
)

def _mend(ctx):
    # A timer callback: a top-level function called with a fresh ctx whose self is the
    # entity that set the timer. Keyword arguments given to ctx.after arrive as keywords.
    if ctx.self.in_world and ctx.self.hp < ctx.self.max_hp:
        ctx.heal(ctx.self, ctx.self.max_hp - ctx.self.hp)
        ctx.echo(ctx.self.room, "The training dummy creaks and mends itself.")
    ctx.del_attr(ctx.self, "mending")

def _dummy_hit(ctx):
    # on_damage is dispatched to the damaged entity first, then to observers, so check
    # that this hook is about the dummy itself.
    if ctx.target != ctx.self:
        return
    ctx.emote(ctx.self, "wobbles.")
    # Do checks first and verbs last: if a hook fails half way, the verbs it already ran
    # stay applied.
    if ctx.self.hp * 2 < ctx.self.max_hp and not ctx.get_attr(ctx.self, "mending", False):
        ctx.set_attr(ctx.self, "mending", True)
        ctx.after(8, _mend)    # 8 pulses = 2 seconds = one combat round

mob("training_dummy",
    name = "a training dummy",
    keywords = ["dummy", "training"],
    desc = "A straw dummy on a post. It has seen better days.",
    hp = 30,
    on_damage = _dummy_hit,
)
