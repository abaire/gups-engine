# Shared helpers for the tutorial's other files. Any .star file can load() another one:
#
#     load(":helpers.star", "greeting")         # a file in this package
#     load("//lib:quest.star", "quest")         # a file in another package
#
# Names starting with an underscore are private to their file and can't be loaded.

# The quest library is shared by every zone. Its helpers store progress on the player.
# Names a file load()s are its own: every file that uses quest loads it itself.
load("//lib:quest.star", "quest")

# Module-level values are frozen once the file has loaded: hooks can read them but never
# change them. Keep per-player or per-room state in attributes instead (ctx.set_attr).
READY_WORD = "ready"

# The tutorial's quest. Each step's text is what `quests` shows while the player is on it.
FIRST_STEPS = quest.define("tutorial_first_steps", "First Steps", [
    "Ask the guide in the hall about a key.",
    "Unlock the armory east of the hall and take the practice sword.",
    "Tell the guide you're ready, then enter the arena with the sword wielded.",
])

def greeting(name):
    """Returns the guide's welcome line for a player."""
    return "Welcome to the tutorial, %s! Say '%s' when you want to try the arena." % (name, READY_WORD)

def said(text, word):
    """True if word appears in text as a whole word, ignoring case and punctuation."""
    # Unlike Python, Starlark strings aren't iterable: .elems() gives their characters.
    for mark in ",.!?;:'\"".elems():
        text = text.replace(mark, " ")
    return word in text.lower().split(" ")
