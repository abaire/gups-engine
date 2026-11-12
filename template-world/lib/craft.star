# Crafting: stations offer recipes, and players turn ingredients into items or improve one they
# hold. Any zone can run a station:
#
#     load("//lib:craft.star", "craft")
#
#     DAGGER = craft.recipe("forge_dagger", name = "an iron dagger", verb = "forge",
#                           inputs = [craft.need("//zones/smithy:iron_bar", count = 2)],
#                           tools = [craft.need(tag = "hammer")],
#                           result = craft.make("//zones/smithy:iron_dagger"))
#
#     def _forge_recipes(ctx):
#         return [DAGGER]
#
#     room("smithy", ..., hooks = {"craft_recipes": _forge_recipes})
#
# A station is a room, an item on the ground, or an item the crafter carries (not worn and not in
# a container) whose custom hook `craft_recipes` returns a list of recipes. Players type `recipes`
# to see what they can make here and `craft <recipe> [on <item>]` to make it.
#
# Recipes pass through ctx.call_hook and are stored in a task's arguments, so they are plain data:
# ints, strings, bools, lists and dicts, never None. An absent value is "", 0, [] or {}.

_WORD_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789_"
_NAME_MAX = 80

def _absolute(label, what):
    """A label the library's verbs can spawn: an absolute //zones/zone:item."""
    if type(label) != "string":
        fail("%s: %r is not a label string" % (what, label))
    if label.startswith(":"):
        fail("%s: %s is relative; write the full label, for example //zones/tutorial:iron_bar" % (what, label))
    if not label.startswith("//"):
        fail("%s: %s is not an absolute label starting with //" % (what, label))
    return label

def _is_word(s):
    """[a-z][a-z0-9_]*, the shape of tags, kinds and verbs."""
    if type(s) != "string" or not s or s[0] not in "abcdefghijklmnopqrstuvwxyz":
        return False
    for ch in s.elems():
        if ch not in _WORD_CHARS:
            return False
    return True

def _check_word(s, what):
    if not _is_word(s):
        fail("%s: %r is not a lowercase word matching [a-z][a-z0-9_]*" % (what, s))
    return s

def _check_id(id, what):
    """A recipe id: [a-z0-9_]+."""
    if type(id) != "string" or not id:
        fail("%s: must be a non-empty string" % what)
    for ch in id.elems():
        if ch not in _WORD_CHARS:
            fail("%s: %r is not made of [a-z0-9_]" % (what, id))
    return id

def _check_line(s, what):
    """A display name: a non-empty single line of at most 80 characters."""
    if type(s) != "string" or not s.strip() or len(s) > _NAME_MAX or "\n" in s or "\r" in s:
        fail("%s: must be a non-empty single line of at most %d characters" % (what, _NAME_MAX))
    return s

def _is_digits(s):
    return s != "" and s.isdigit()

def _is_dice(s):
    """[0-9]*d[0-9]+([+-][0-9]+)?, checked by hand: Starlark has no regex."""
    if "d" not in s:
        return False
    count, rest = s.split("d", 1)
    if count != "" and not _is_digits(count):
        return False
    for sign in ("+", "-"):
        if sign in rest:
            sides, mod = rest.split(sign, 1)
            return _is_digits(sides) and _is_digits(mod)
    return _is_digits(rest)

def _is_lose(v):
    """An int of 0 or more, "all", "N%" with N in 0..100, or a dice string."""
    if type(v) == "int":
        return v >= 0
    if type(v) != "string":
        return False
    if v == "all":
        return True
    if v.endswith("%"):
        n = v[:-1]
        return _is_digits(n) and int(n) <= 100
    return _is_dice(v)

def _need_what(label, tag, kind):
    return label or tag or kind or "?"

def _need(label = None, tag = None, kind = None, count = 1, lose = None, charges = 0, name = ""):
    """One thing a recipe needs: an item by label, any item with a tag, or any item of a kind."""
    named = [x for x in (label, tag, kind) if x != None]
    if len(named) != 1:
        fail("need: name exactly one of label, tag or kind")
    what = _need_what(label, tag, kind)
    if label != None:
        _absolute(label, "need(label)")
    if tag != None:
        _check_word(tag, "need(tag)")
    if kind != None:
        _check_word(kind, "need(kind)")
    if type(count) != "int" or count < 1:
        fail("need(count): must be at least 1, got %r for %s" % (count, what))
    if type(charges) != "int" or charges < 0:
        fail("need(charges): must be at least 0, got %r for %s" % (charges, what))
    if lose != None and not _is_lose(lose):
        fail("need(lose): %r for %s is not an int, \"all\", \"N%%\" or dice like \"1d4-1\"" % (lose, what))
    if name != "":
        _check_line(name, "need(name)")
    return {"label": label or "", "tag": tag or "", "kind": kind or "", "count": count,
            "lose": lose if lose != None else "", "charges": charges, "name": name}

def _make(label, count = 1):
    """A recipe's product: count items of label."""
    _absolute(label, "make(label)")
    if type(count) != "int" or count < 1:
        fail("make(count): must be at least 1, got %r" % count)
    return {"kind": "make", "label": label, "count": count}

def _check_deltas(d, what):
    if type(d) != "dict":
        fail("%s: must be a dict of ints" % what)
    for k, v in d.items():
        if type(k) != "string" or type(v) != "int":
            fail("%s: %r: %r is not a name and an int" % (what, k, v))
    return d

def _check_words(xs, what):
    if type(xs) != "list":
        fail("%s: must be a list of words" % what)
    for x in xs:
        _check_word(x, what)
    return xs

def _improve(affect = "", applies = "worn", stat_deltas = {}, sense_deltas = {},
             max_vital_deltas = {}, resist_deltas = {}, flags = [], add_tags = [], remove_tags = [],
             variant = "", unless_tag = ""):
    """A recipe's result that improves the item the crafter names, rather than making one."""
    if affect != "":
        _check_word(affect, "improve(affect)")
    if applies not in ("worn", "carried"):
        fail("improve(applies): must be \"worn\" or \"carried\", got %r" % applies)
    for d, what in ((stat_deltas, "stat_deltas"), (sense_deltas, "sense_deltas"),
                    (max_vital_deltas, "max_vital_deltas"), (resist_deltas, "resist_deltas")):
        _check_deltas(d, "improve(%s)" % what)
    _check_words(flags, "improve(flags)")
    _check_words(add_tags, "improve(add_tags)")
    _check_words(remove_tags, "improve(remove_tags)")
    if variant != "":
        _check_word(variant, "improve(variant)")
    if unless_tag != "":
        _check_word(unless_tag, "improve(unless_tag)")
    if not (stat_deltas or sense_deltas or max_vital_deltas or resist_deltas or flags or add_tags or
            remove_tags or variant):
        fail("improve: give at least one effect: deltas, flags, tags or a variant")
    return {"kind": "improve", "affect": affect, "applies": applies, "stat_deltas": stat_deltas,
            "sense_deltas": sense_deltas, "max_vital_deltas": max_vital_deltas,
            "resist_deltas": resist_deltas, "flags": flags, "add_tags": add_tags,
            "remove_tags": remove_tags, "variant": variant, "unless_tag": unless_tag}

def _failure(lose = "50%", make = None, message = ""):
    """What a failed roll costs: each input's loss when it names none, and an optional flawed product."""
    if lose == None or not _is_lose(lose):
        fail("failure(lose): %r is not an int, \"all\", \"N%%\" or dice like \"1d4-1\"" % lose)
    if make != None and (type(make) != "dict" or make.get("kind") != "make"):
        fail("failure(make): must be a craft.make(...) result or None")
    if message != "":
        _check_line(message, "failure(message)")
    return {"lose": lose, "make": make if make != None else {}, "message": message}

def _quality(at, name = "", affect = "", stat_deltas = {}, sense_deltas = {}, max_vital_deltas = {},
             resist_deltas = {}, add_tags = []):
    """A quality tier: a roll whose power reaches at renames and enhances the result."""
    if type(at) != "int" or at < 1:
        fail("quality(at): must be at least 1, got %r" % at)
    if name != "":
        _check_line(name, "quality(name)")
    if affect != "":
        _check_word(affect, "quality(affect)")
    for d, what in ((stat_deltas, "stat_deltas"), (sense_deltas, "sense_deltas"),
                    (max_vital_deltas, "max_vital_deltas"), (resist_deltas, "resist_deltas")):
        _check_deltas(d, "quality(%s)" % what)
    _check_words(add_tags, "quality(add_tags)")
    return {"at": at, "name": name, "affect": affect, "stat_deltas": stat_deltas,
            "sense_deltas": sense_deltas, "max_vital_deltas": max_vital_deltas,
            "resist_deltas": resist_deltas, "add_tags": add_tags}

def _doing(verb):
    """forge -> forging, hone -> honing, smelt -> smelting."""
    stem = verb[:-1] if verb.endswith("e") else verb
    return stem + "ing"

def _task_name(doing, name):
    """What the crafter is busy with: "forging an iron dagger"."""
    return doing + " " + name

def _check_needs(needs, what):
    if type(needs) != "list":
        fail("recipe(%s): must be a list of craft.need(...) results" % what)
    for n in needs:
        if type(n) != "dict" or "count" not in n or "charges" not in n:
            fail("recipe(%s): each entry must be a craft.need(...) result" % what)
    return needs

def _minus_at(q):
    return -q["at"]

def _check_qualities(qualities):
    if type(qualities) != "list":
        fail("recipe(qualities): must be a list of craft.quality(...) results")
    seen = {}
    for q in qualities:
        if type(q) != "dict" or "at" not in q:
            fail("recipe(qualities): each entry must be a craft.quality(...) result")
        if q["at"] in seen:
            fail("recipe(qualities): two tiers at %d" % q["at"])
        seen[q["at"]] = True
    return sorted(qualities, key = _minus_at)

def _check_result(result, target):
    if type(result) != "dict" or result.get("kind") not in ("make", "improve"):
        fail("recipe(result): must be a craft.make(...) or craft.improve(...) result")
    if result["kind"] == "improve" and target == None:
        fail("recipe(target): an improve recipe needs a target, a craft.need(...)")
    if result["kind"] == "make" and target != None:
        fail("recipe(target): a make recipe takes no target")
    if target != None and (type(target) != "dict" or "count" not in target):
        fail("recipe(target): must be a craft.need(...) result")

def _check_numbers(time, lag, difficulty):
    if type(time) != "int" or time < 1:
        fail("recipe(time): must be at least 1, got %r" % time)
    if type(lag) != "int" or lag < 0:
        fail("recipe(lag): must be at least 0, got %r" % lag)
    if type(difficulty) != "int" or difficulty < 0:
        fail("recipe(difficulty): must be at least 0, got %r" % difficulty)

def _recipe(id, result, name = "", verb = "craft", doing = "", inputs = [], tools = [], target = None,
            skill = "", difficulty = 0, time = 10, lag = 0, learned = False, failure = None,
            qualities = []):
    """A recipe: what it needs, how long it takes, what it rolls and what it makes."""
    _check_id(id, "recipe(id)")
    _check_result(result, target)
    _check_word(verb, "recipe(verb)")
    name = _check_line(name, "recipe(name)") if name != "" else id.replace("_", " ")
    doing = _check_line(doing, "recipe(doing)") if doing != "" else _doing(verb)
    if len(_task_name(doing, name)) > _NAME_MAX:
        fail("recipe(name): \"%s\" names the craft's task and must be at most %d characters; shorten name or doing" %
             (_task_name(doing, name), _NAME_MAX))
    _check_numbers(time, lag, difficulty)
    if skill != "":
        _absolute(skill, "recipe(skill)")
    for n in _check_needs(inputs, "inputs"):
        if n["charges"] > 0:
            fail("recipe(inputs): %s asks for charges; only tools spend charges" %
                 _need_what(n["label"], n["tag"], n["kind"]))
    _check_needs(tools, "tools")
    if failure == None:
        failure = _failure()
    elif type(failure) != "dict" or "lose" not in failure:
        fail("recipe(failure): must be a craft.failure(...) result")
    return {"id": id, "name": name, "verb": verb, "doing": doing, "inputs": inputs,
            "tools": tools, "target": target if target != None else {}, "skill": skill,
            "difficulty": difficulty, "time": time, "lag": lag, "learned": learned == True,
            "failure": failure, "qualities": _check_qualities(qualities), "result": result}

# ---- Learned recipes ---------------------------------------------------------------------------

def _known(ctx, actor):
    return ctx.get_attr(actor, "craft_known", [])

def _teach(ctx, actor, recipe_id):
    """Lets actor use the learned recipe recipe_id; True if it didn't know it yet."""
    _check_id(recipe_id, "teach(recipe_id)")
    known = list(_known(ctx, actor))
    if recipe_id in known:
        return False
    known.append(recipe_id)
    ctx.set_attr(actor, "craft_known", sorted(known))
    return True

def _knows(ctx, actor, recipe_id):
    """Whether actor has been taught recipe_id."""
    return recipe_id in _known(ctx, actor)

def _usable(ctx, actor, recipe):
    """A learned recipe is listed and usable only for actors who know it."""
    return not recipe["learned"] or _knows(ctx, actor, recipe["id"])

# ---- Stations ----------------------------------------------------------------------------------

def _recipes_of(ctx, entity):
    """The recipes a station's craft_recipes hook offers, by id, or None when it offers none."""
    offered = ctx.call_hook(entity, "craft_recipes")
    if offered == None:
        return None
    if type(offered) != "list":
        print("craft_recipes of %s returned %r, not a list; skipped" % (entity.name, offered))
        return None
    by_id = {}
    for r in offered:
        if type(r) != "dict" or type(r.get("id")) != "string":
            print("craft_recipes of %s returned %r, not a recipe; skipped" % (entity.name, r))
            continue
        if r["id"] in by_id:
            print("craft_recipes of %s offers %s twice; the later one wins" % (entity.name, r["id"]))
        by_id[r["id"]] = r
    return by_id

def _stations(ctx, actor):
    """[(station, {id: recipe})] in reach: the room, items on the ground, then carried items."""
    room = actor.room
    out = []
    for entity in [room] + room.items() + actor.inventory():
        by_id = _recipes_of(ctx, entity)
        if by_id != None:
            out.append((entity, by_id))
    return out

def _offered(ctx, actor):
    """[(station, recipe)] the actor may use, each id once: the first station offering it wins."""
    seen = {}
    out = []
    for station, by_id in _stations(ctx, actor):
        for id, recipe in by_id.items():
            if id in seen or not _usable(ctx, actor, recipe):
                continue
            seen[id] = True
            out.append((station, recipe))
    return out

# ---- Matching ----------------------------------------------------------------------------------

def _stackable(ctx, item):
    info = ctx.item_info(item.label)
    return info != None and info.stackable

def _fits(ctx, need, item, group):
    """Whether item can serve need: the right label, tag or kind, and enough charges for a tool."""
    if need["label"] != "" and item.label != need["label"]:
        return False
    if need["tag"] != "" and not item.has_tag(need["tag"]):
        return False
    if need["kind"] != "" and item.kind != need["kind"]:
        return False
    n = need["charges"]
    if group == "tool" and n > 0:
        return not _stackable(ctx, item) and (item.charges == None or item.charges >= n)
    return True

def _match(ctx, actor, recipe, target = None):
    """struct(ready, reserved, missing): what the actor's carried items can do for recipe.
    Greedy, inputs first, then tools; an item serving one need can't serve another, and a stack
    gives up to its count. The target being improved serves no need: it must outlive the craft.
    reserved is [{group, need, id, take}]; missing is [(need, short)]."""
    used = {target.id: True} if target != None else {}
    reserved = []
    missing = []
    carried = actor.inventory()
    for group, needs in (("input", recipe["inputs"]), ("tool", recipe["tools"])):
        for index, need in enumerate(needs):
            left = need["count"]
            for item in carried:
                if left == 0:
                    break
                if item.id in used or not _fits(ctx, need, item, group):
                    continue
                take = min(item.count, left)
                reserved.append({"group": group, "need": index, "id": item.id, "take": take})
                used[item.id] = True
                left -= take
            if left > 0:
                missing.append((need, left))
    ready = not missing and _skills_ok(ctx, recipe)
    return struct(ready = ready, reserved = reserved, missing = missing)

def _skills_ok(ctx, recipe):
    """A recipe that names a skill works only in a world with the skills feature."""
    return recipe["skill"] == "" or ctx.has_feature("skills")

# ---- Names -------------------------------------------------------------------------------------

def _article(word):
    return "an" if word[0] in "aeiou" else "a"

def _need_name(ctx, need, n):
    """How n of need reads: "an iron bar", "2 iron bars", "a hammer", "2 hammer"."""
    if need["label"] != "" and need["name"] == "":
        info = ctx.item_info(need["label"])
        if info != None:
            if n == 1:
                return info.name
            return "%d %s" % (n, info.plural if info.plural != None else info.name)
        word = need["label"].split(":")[-1].replace("_", " ")
    else:
        word = need["name"] or need["tag"] or need["kind"]
    if n == 1:
        return "%s %s" % (_article(word), word)
    return "%d %s" % (n, word)

def _missing_text(ctx, missing):
    return ", ".join([_need_name(ctx, need, short) for need, short in missing])

def _lose_of(recipe, need):
    """A need's own lose, or the recipe's failure.lose when it names none."""
    return need["lose"] if need["lose"] != "" else recipe["failure"]["lose"]

# ---- recipes -----------------------------------------------------------------------------------

def _status(ctx, recipe, m):
    if not _skills_ok(ctx, recipe):
        return " — needs the skills feature"
    if m.ready:
        return " — ready"
    return " — still missing: %s" % _missing_text(ctx, m.missing)

def _have(m, group, index):
    total = 0
    for r in m.reserved:
        if r["group"] == group and r["need"] == index:
            total += r["take"]
    return total

def _recipe_payload(ctx, station, recipe, m):
    needs = []
    for i, need in enumerate(recipe["inputs"]):
        needs.append({"name": _need_name(ctx, need, 1), "count": need["count"],
                      "have": _have(m, "input", i), "lose": _lose_of(recipe, need)})
    tools = []
    for i, need in enumerate(recipe["tools"]):
        tools.append({"name": _need_name(ctx, need, 1), "have": _have(m, "tool", i) >= need["count"]})
    return {"id": recipe["id"], "name": recipe["name"], "verb": recipe["verb"],
            "station": station.name, "skill": recipe["skill"] if recipe["skill"] != "" else None,
            "time": recipe["time"], "learned": recipe["learned"],
            "target": recipe["result"]["kind"] == "improve", "ready": m.ready,
            "needs": needs, "tools": tools}

def _recipes_command(ctx):
    actor = ctx.self
    offered = _offered(ctx, actor)
    if not offered:
        ctx.send(actor, "There's nothing to craft with here.")
        ctx.gmcp(actor, "Craft.Recipes", {"recipes": []})
        return
    lines = []
    payload = []
    last = None
    for station, recipe in offered:
        if station.id != last:
            lines.append("%s:" % station.name)
            last = station.id
        m = _match(ctx, actor, recipe)
        lines.append(" %s: %s (%s)%s" % (recipe["id"], recipe["name"], recipe["verb"],
                                         _status(ctx, recipe, m)))
        payload.append(_recipe_payload(ctx, station, recipe, m))
    ctx.send(actor, "\n".join(lines))
    ctx.gmcp(actor, "Craft.Recipes", {"recipes": payload})

# ---- craft -------------------------------------------------------------------------------------

def _cap(s):
    return s[0].upper() + s[1:] if s else s

def _words(s):
    return [w for w in s.lower().split(" ") if w]

def _kw_match(keywords, phrase):
    """True if every word of the phrase starts one of the keywords."""
    words = _words(phrase)
    if not words:
        return False
    for w in words:
        found = False
        for kw in keywords:
            if kw.lower().startswith(w):
                found = True
                break
        if not found:
            return False
    return True

def _split_on(args):
    """"hone_edge on dagger" -> ("hone_edge", "dagger"); the last " on " splits."""
    i = args.rfind(" on ")
    if i < 0:
        return (args, None)
    return (args[:i].strip(), args[i + 4:].strip())

def _find_recipe(ctx, actor, what):
    """(station, recipe) by id, else by a prefix match on the id's or the name's words, or None."""
    offered = _offered(ctx, actor)
    for station, recipe in offered:
        if recipe["id"] == what:
            return (station, recipe)
    for station, recipe in offered:
        if _kw_match(recipe["id"].split("_"), what) or _kw_match(_words(recipe["name"]), what):
            return (station, recipe)
    return None

def _worn(actor):
    out = []
    for slot in actor.slots:
        item = actor.equipped(slot)
        if item != None and item not in out:
            out.append(item)
    return out

def _held(actor):
    """Carried items, then worn ones."""
    return actor.inventory() + _worn(actor)

def _find_held(actor, keyword):
    for item in _held(actor):
        if _kw_match(item.keywords, keyword):
            return item
    return None

def _by_id(items, id):
    for item in items:
        if item.id == id:
            return item
    return None

def _station_ref(station):
    if type(station) == "room":
        return {"kind": "room", "label": station.label}
    return {"kind": "item", "id": station.id}

def _station_of(actor, ref):
    """The station ref names, if it is still in the crafter's reach, else None."""
    room = actor.room
    if ref["kind"] == "room":
        return room if room.label == ref["label"] else None
    return _by_id(room.items() + actor.inventory(), ref["id"])

def _target_refusal(ctx, recipe, target):
    """Why target can't take this improve recipe, or None."""
    verb = recipe["verb"]
    result = recipe["result"]
    if not _fits(ctx, recipe["target"], target, "target"):
        return "You can't %s %s." % (verb, target.name)
    if result["unless_tag"] != "" and target.has_tag(result["unless_tag"]):
        return "%s is already %s." % (_cap(target.name), result["unless_tag"])
    if result["variant"] != "" and result["variant"] not in target.variants:
        return "You can't %s %s." % (verb, target.name)
    return None

def _pick_target(ctx, actor, recipe, on):
    """(target, refusal) for the item the crafter names after "on"."""
    improves = recipe["result"]["kind"] == "improve"
    if not improves:
        return (None, "That recipe doesn't work on an item." if on != None else None)
    if on == None or on == "":
        return (None, "%s what?" % _cap(recipe["verb"]))
    target = _find_held(actor, on)
    if target == None:
        return (None, "You aren't carrying that.")
    return (target, _target_refusal(ctx, recipe, target))

def _refusal(ctx, actor, recipe):
    """Why actor can't start recipe at all, before its target and inputs are looked at."""
    if ctx.has_feature("combat") and actor.fighting():
        return "You can't do that while fighting."
    if not _skills_ok(ctx, recipe):
        return "That recipe needs skills this world doesn't have."
    return None

def _checked_adjust(ctx, station, actor, recipe, target):
    """(adjust, refusal) after the station's craft_check hook."""
    adjust = -recipe["difficulty"]
    res = ctx.call_hook(station, "craft_check", crafter = actor, recipe = recipe["id"],
                        target = target, adjust = adjust)
    if type(res) == "string":
        return (adjust, res)
    if type(res) == "int":
        return (res, None)
    return (adjust, None)

def _craft_command(ctx):
    actor = ctx.self
    args = ctx.args.strip()
    if not args:
        ctx.send(actor, "Craft what? Type `recipes` to see what you can make here.")
        return
    what, on = _split_on(args)
    found = _find_recipe(ctx, actor, what)
    if found == None:
        ctx.send(actor, "You don't know how to make that here.")
        return
    station, recipe = found
    refusal = _refusal(ctx, actor, recipe)
    target = None
    if refusal == None:
        target, refusal = _pick_target(ctx, actor, recipe, on)
    if refusal != None:
        ctx.send(actor, refusal)
        return
    m = _match(ctx, actor, recipe, target)
    if not m.ready:
        ctx.send(actor, "You still need %s." % _missing_text(ctx, m.missing))
        return
    adjust, refusal = _checked_adjust(ctx, station, actor, recipe, target)
    if refusal != None:
        ctx.send(actor, refusal)
        return
    _start(ctx, actor, station, recipe, m, target, adjust)

def _start(ctx, actor, station, recipe, m, target, adjust):
    err = ctx.start_task(actor, recipe["time"], _on_done, name = _task_name(recipe["doing"], recipe["name"]),
                         lag = recipe["lag"], on_stop = _on_stop, recipe = recipe,
                         station = _station_ref(station), reserved = m.reserved,
                         target_id = target.id if target != None else 0, adjust = adjust)
    if err != None:
        ctx.send(actor, err)
        return
    ctx.send(actor, "You begin to %s %s." % (recipe["verb"], recipe["name"]))
    ctx.echo(actor.room, "%s begins to %s %s." % (_cap(actor.name), recipe["verb"], recipe["name"]),
             exclude = [actor])

# ---- Completion --------------------------------------------------------------------------------

def _need_of(recipe, r):
    return recipe["inputs" if r["group"] == "input" else "tools"][r["need"]]

def _still_held(ctx, actor, recipe, reserved):
    """{id: item} for the reserved items, or None when one is gone, short or out of charges."""
    carried = actor.inventory()
    held = {}
    for r in reserved:
        item = _by_id(carried, r["id"])
        if item == None or item.count < r["take"]:
            return None
        if r["group"] == "tool" and not _fits(ctx, _need_of(recipe, r), item, "tool"):
            return None
        held[r["id"]] = item
    return held

def _result(ctx, actor, recipe, outcome, quality = None, items = [], lost = []):
    ctx.gmcp(actor, "Craft.Result", {"recipe": recipe["id"], "outcome": outcome, "quality": quality,
                                     "items": items, "lost": lost})

def _roll(ctx, actor, recipe, target, adjust):
    """(success, power): a recipe with no skill always succeeds at power 100."""
    if recipe["skill"] == "":
        return (True, 100)
    r = ctx.skill_roll(actor, recipe["skill"], item = target, event = "craft", adjust = adjust)
    return (r.success, r.power)

def _spend_charges(ctx, recipe, reserved, held):
    for r in reserved:
        if r["group"] != "tool":
            continue
        n = _need_of(recipe, r)["charges"]
        item = held[r["id"]]
        if n > 0 and item.charges != None:
            ctx.set_charges(item, max(0, item.charges - n))

def _loss(ctx, lose, count):
    """How many of count a failure loses: an int, "all", "N%" (rounded up) or dice."""
    if type(lose) == "int":
        n = lose
    elif lose == "all":
        n = count
    elif lose.endswith("%"):
        n = (count * int(lose[:-1]) + 99) // 100
    else:
        n = ctx.roll(lose)
    return max(0, min(count, n))

def _consume(ctx, recipe, reserved, held, losses):
    """Destroys losses[need] of each input from its reserved items, in order; returns what went."""
    lost = []
    for i in range(len(recipe["inputs"])):
        left = losses[i]
        for r in reserved:
            if left <= 0:
                break
            if r["group"] != "input" or r["need"] != i:
                continue
            item = held[r["id"]]
            n = min(left, r["take"])
            lost.append({"label": item.label, "name": item.name, "count": n})
            ctx.destroy(item, count = n)
            left -= n
    return lost

def _tier(recipe, power):
    """The highest quality tier the power reaches, or None."""
    for q in recipe["qualities"]:
        if q["at"] <= power:
            return q
    return None

def _has_deltas(e):
    return e["stat_deltas"] or e["sense_deltas"] or e["max_vital_deltas"] or e["resist_deltas"]

def _affect(ctx, item, name, e, flags = [], applies = "worn"):
    ctx.apply_affect(item, name, None, stat_deltas = stats(**e["stat_deltas"]),
                     sense_deltas = e["sense_deltas"],
                     max_vital_deltas = vitals(**e["max_vital_deltas"]),
                     resist_deltas = resists(**e["resist_deltas"]), flags = flags,
                     applies = applies)

def _apply_tier(ctx, q, item):
    if q["name"] != "":
        ctx.set_item_name(item, q["name"])
    if _has_deltas(q):
        _affect(ctx, item, q["affect"] or "quality_%d" % q["at"], q)
    for tag in q["add_tags"]:
        ctx.add_tag(item, tag)

def _views(made):
    return made if type(made) == "list" else [made]

def _make_items(ctx, actor, make, tier):
    items = _views(ctx.give(actor, make["label"], make["count"]))
    if tier != None:
        for item in items:
            _apply_tier(ctx, tier, item)
        return items
    return [ctx.merge_stack(item) for item in items]

def _improve_item(ctx, recipe, target, tier):
    e = recipe["result"]
    if _has_deltas(e) or e["flags"]:
        _affect(ctx, target, e["affect"] or "craft_" + recipe["id"], e, e["flags"], e["applies"])
    for tag in e["add_tags"]:
        ctx.add_tag(target, tag)
    for tag in e["remove_tags"]:
        ctx.remove_tag(target, tag)
    if e["variant"] != "":
        ctx.transform(target, target.variants[e["variant"]])
    if tier != None:
        _apply_tier(ctx, tier, target)
    return [target]

def _count_text(ctx, label, name, n):
    """"a test bar" for one, "2 test bars" for more."""
    if n == 1:
        return name
    info = ctx.item_info(label)
    plural = info.plural if info != None and info.plural != None else name
    return "%d %s" % (n, plural)

def _made_text(ctx, items, count):
    """How the made or improved items read: a name, or "3 test nails" for a stack."""
    item = items[0]
    if len(items) == 1 and item.count == 1:
        return item.name
    return _count_text(ctx, item.label, item.name, count)

def _succeed(ctx, actor, recipe, reserved, held, target, power):
    _consume(ctx, recipe, reserved, held, [n["count"] for n in recipe["inputs"]])
    tier = _tier(recipe, power)
    result = recipe["result"]
    if result["kind"] == "make":
        items = _make_items(ctx, actor, result, tier)
        count = result["count"]
    else:
        items = _improve_item(ctx, recipe, target, tier)
        count = 1
    ctx.send(actor, "You %s %s." % (recipe["verb"], _made_text(ctx, items, count)))
    return (items, tier, [])

def _fail(ctx, actor, recipe, reserved, held):
    failure = recipe["failure"]
    losses = [_loss(ctx, _lose_of(recipe, n), n["count"]) for n in recipe["inputs"]]
    lost = _consume(ctx, recipe, reserved, held, losses)
    items = []
    if failure["make"]:
        items = _make_items(ctx, actor, failure["make"], None)
    text = failure["message"] or "You fail to %s %s." % (recipe["verb"], recipe["name"])
    if lost:
        ruined = [_count_text(ctx, l["label"], l["name"], l["count"]) for l in lost]
        text += " You ruin %s." % ", ".join(ruined)
    ctx.send(actor, text)
    return (items, None, [{"name": l["name"], "count": l["count"]} for l in lost])

def _quality_name(tier):
    if tier == None:
        return None
    return tier["name"] if tier["name"] != "" else "q%d" % tier["at"]

def _on_done(ctx, recipe, station, reserved, target_id, adjust):
    actor = ctx.actor
    held = _still_held(ctx, actor, recipe, reserved)
    target = _by_id(_held(actor), target_id) if target_id else None
    if _station_of(actor, station) == None or held == None or (target_id and target == None):
        ctx.send(actor, "You no longer have what you need.")
        _result(ctx, actor, recipe, "stopped")
        return
    success, power = _roll(ctx, actor, recipe, target, adjust)
    _spend_charges(ctx, recipe, reserved, held)
    if success:
        items, tier, lost = _succeed(ctx, actor, recipe, reserved, held, target, power)
    else:
        items, tier, lost = _fail(ctx, actor, recipe, reserved, held)
    outcome = "success" if success else "fail"
    if recipe["skill"] != "":
        ctx.report_skill_use(actor, recipe["skill"], item = items[0] if items else target)
    where = _station_of(actor, station)
    if where != None:
        ctx.call_hook(where, "craft_done", crafter = actor, recipe = recipe["id"], outcome = outcome,
                      items = items)
    _result(ctx, actor, recipe, outcome, _quality_name(tier), [i.name for i in items], lost)

def _on_stop(ctx, reason, recipe, station, reserved, target_id, adjust):
    # The engine has already told the crafter; a crafter gone from the world hears nothing.
    if reason != "gone":
        _result(ctx, ctx.actor, recipe, "stopped")

craft = struct(recipe = _recipe, need = _need, make = _make, improve = _improve,
               failure = _failure, quality = _quality, teach = _teach, knows = _knows)

global_command("recipes", _recipes_command, lane = "fast")
global_command("craft", _craft_command, lane = "tick")

gmcp_package("Craft.Recipes", doc = "Recipes the crafter can reach, sent by recipes.")
gmcp_package("Craft.Result", doc = "How a craft ended, sent when it finishes or stops.")
