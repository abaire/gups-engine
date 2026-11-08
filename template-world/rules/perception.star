# Sensory propagation, perception checks, and distant perception messages. An event starts at
# its room with an intensity and spreads outward through the exits, one hop at a time; each hop
# adds the cost sense_cost gives, and a hop with a negative cost stops it. Every actor who can
# be reached is asked can_perceive; a player in another room who passes is shown whatever
# perceive_message returns.

def sense_cost(ctx):
    # ctx.room is the room the signal enters; ctx.exit leads from it back toward the source.
    # A negative price blocks the hop; 0 is a free one.
    if "sealed" in ctx.exit.tags:
        return -1
    if "soundproof" in ctx.exit.tags and ctx.channel == "sound":
        return -1
    cost = 1
    # Room sizes need the track feature; a world without it drops this check.
    if ctx.room.size == "large" and ctx.channel == "sound":
        cost += 1
    door = ctx.exit.door
    if door and door.state != "open":
        # A closed door is opaque unless it is glass, and muffles sound.
        if ctx.channel == "sight" and "transparent" not in door.tags:
            return -1
        cost += 2 if "heavy" in door.tags else 1
    return cost

# How many pulses the noise a hidden actor makes gives it away.
NOISE_PULSES = 8

def can_perceive(ctx):
    # A pacified mob notices nobody, so it starts no fight and retargets no one.
    if ctx.event == "notice" and ctx.actor.has_flag("pacified"):
        return False
    # Stealth is decided when the actor hides and stored as a flag on an affect; this only reads
    # it, and never rolls. A hidden actor can't be seen at all, and it moves silently. It can be
    # heard, but a mob only notices it by sound while the noise it made is still fresh in the mob's
    # memory, so a hidden player who speaks, opens a door or casts is caught on the mob's next think.
    if ctx.source and ctx.source.has_flag("hidden"):
        if ctx.channel == "sight" or ctx.event in ("enter", "exit"):
            return False
        if ctx.event == "notice" and ctx.channel == "sound":
            heard = [m for m in ctx.memories(subject = ctx.source, since = ctx.pulse - NOISE_PULSES) if m.channel == "sound"]
            if not heard:
                return False
    sense = ctx.actor.senses[ctx.channel]
    if sense <= 0:
        return False
    if ctx.source_room == ctx.room:
        return True
    return ctx.intensity + sense > ctx.cost

def perceive_message(ctx):
    # A player in the event's own room is asked only about a world channel, as it happens; the
    # engine words the built-in ones itself. Shouts are the exception, told here in every room.
    if not ctx.via_exit and ctx.event != "shout":
        return None
    if ctx.event == "shout":
        if not ctx.via_exit:
            return "{} shouts, '{}'".format(ctx.source.name if ctx.source else "Someone", ctx.text or "")
        return "Someone shouts from the {}, '{}'".format(ctx.via_exit, ctx.text or "")
    if ctx.event in ("combat_start", "combat"):
        return "You hear the sounds of combat from the {}.".format(ctx.via_exit)
    if ctx.event == "death":
        return "You hear a death cry from the {}.".format(ctx.via_exit)
    if ctx.event in ("open", "close", "lock", "unlock"):
        return "You hear something {} to the {}.".format(ctx.event, ctx.via_exit)
    if ctx.text:
        return ctx.text
    return None
