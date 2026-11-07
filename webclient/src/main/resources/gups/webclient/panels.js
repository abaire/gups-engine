// The side panel, drawn from GMCP: vitals bars, group, affects and bands.

/**
 * @typedef {{name: string, label: string}} Named
 * @typedef {{stats: Named[], vitals: Named[], damage_types: string[], features: string[]}} Schema
 * @typedef {Object<string, number>} Vitals The current and maximum of each vital: `hp` and `maxhp`.
 * @typedef {{name: string, leader: boolean, in_room: boolean, level?: number, vitals: Vitals}} GroupMember
 * @typedef {{id: number, leader: string, members: GroupMember[]}} GroupData
 * @typedef {{name: string, remaining: number, flags: string[]}} Affect
 * @typedef {{id: number, name: string, kind: "actor" | "item"}} BandEntry
 * @typedef {{melee: BandEntry[], near: BandEntry[], far: BandEntry[]}} Bands
 * @typedef {{label: string, name: string, exits: Object<string, string|null>, regions: string[]}} RoomInfo
 */

/** How many bar colors the stylesheet defines; bars cycle through them. */
const TONES = 4;

const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
};

/** One vital bar; `mini` bars (the group panel's) carry no data attributes and no number label. */
function vitalBar(name, label, index, now, max, mini) {
  const bar = el("div", mini ? `bar mini tone${index % TONES}` : `bar tone${index % TONES}`);
  if (!mini) {
    bar.dataset.vital = name;
    bar.dataset.value = `${now}/${max}`;
  }
  const top = el("div", "label");
  if (mini) {
    top.append(el("span", "", label));
    top.title = `${now}/${max}`;
  } else {
    top.append(el("span", "", label), el("span", "", `${now}/${max}`));
  }
  const track = el("div", "track");
  const fill = el("div", "fill");
  fill.style.width = `${Math.max(0, Math.min(100, (100 * now) / max))}%`;
  track.append(fill);
  bar.append(top, track);
  return bar;
}

/**
 * One bar per vital in schema order, labeled with the schema's label.
 * @param {HTMLElement} box
 * @param {Schema} schema
 * @param {Vitals} v
 */
export function drawVitals(box, schema, v) {
  box.replaceChildren();
  schema.vitals.forEach(({ name, label }, i) => {
    const now = v[name] ?? 0;
    const max = v["max" + name] || 1;
    box.append(vitalBar(name, label, i, now, max, false));
  });
}

/**
 * The group panel: one row per member, leader first, hidden with no group.
 * @param {HTMLElement} panel
 * @param {HTMLElement} list
 * @param {Schema} schema
 * @param {GroupData|null} data
 */
export function drawGroup(panel, list, schema, data) {
  if (!data || !data.members || data.members.length === 0) {
    panel.hidden = true;
    list.replaceChildren();
    return;
  }
  panel.hidden = false;
  list.replaceChildren(
    ...data.members.map((m) => {
      const li = el("li", m.in_room ? "member" : "member away");
      let nameText = m.name;
      if (m.leader) nameText += " ★";
      if (typeof m.level === "number") nameText += ` L${m.level}`;
      li.append(el("div", "member-name", nameText));
      schema.vitals.forEach(({ name, label }, i) => {
        const now = m.vitals?.[name] ?? 0;
        const max = m.vitals?.["max" + name] || 1;
        li.append(vitalBar(name, label, i, now, max, true));
      });
      return li;
    }),
  );
}

/**
 * @param {HTMLElement} list
 * @param {Affect[]} affects
 */
export function drawAffects(list, affects) {
  list.replaceChildren(...(affects.length === 0 ? [el("li", "", "none")] : affects.map(
    (a) => el("li", "", `${a.name} (${a.remaining}s${a.flags.length ? "; " + a.flags.join(", ") : ""})`),
  )));
}

/**
 * Who and what is in melee, near and far, from the player's own place in the room.
 * @param {HTMLElement} box
 * @param {Bands} bands
 * @param {Set<string>} [groupNames] names of group members, marked with the `group` class
 */
export function drawBands(box, bands, groupNames = new Set()) {
  box.replaceChildren();
  for (const band of ["melee", "near", "far"]) {
    const entries = bands[band] || [];
    const row = el("div", "band");
    row.append(el("div", "name", band));
    if (entries.length === 0) row.append(el("div", "who", "-"));
    for (const e of entries) {
      const isGroup = e.kind === "actor" && groupNames.has(e.name);
      row.append(el("div", isGroup ? "who actor group" : `who ${e.kind}`, e.name));
    }
    box.append(row);
  }
}

/**
 * @param {HTMLElement} heading
 * @param {RoomInfo} room
 */
export function drawRoom(heading, room) {
  heading.textContent = room.name;
  heading.title = room.label;
}

/**
 * @typedef {{id: string, label: string, summary: string, help?: string}} Option
 * @typedef {{id: string, title: string, prompt: string, options: Option[]}} Choice
 * @typedef {{choice: string, title: string, option: string, label: string}} Answer
 * @typedef {{name: string, step: "choice"|"confirm", choice: Choice|null, answers: Answer[]}} CreationData
 * @typedef {{name: string, label: string, doc: string}} Trait
 * @typedef {{choices: Answer[], traits: Trait[]}} TraitsData
 * @typedef {{label: string, name: string, kind: string, proficiency: number}} Skill
 * @typedef {{skills: Skill[]}} SkillsData
 */

/**
 * Draws the character creation panel over the play area.
 * @param {HTMLElement} panel
 * @param {CreationData | null} data
 * @param {(line: string) => void} sendLine
 */
export function drawCreation(panel, data, sendLine) {
  if (!data) {
    panel.hidden = true;
    panel.replaceChildren();
    return;
  }
  panel.hidden = false;
  panel.replaceChildren();

  if (data.step === "choice" && data.choice) {
    const title = el("h3", "", data.choice.prompt);
    const optionsBox = el("div", "creation-options");
    for (const opt of data.choice.options) {
      const btn = el("button", "creation-option");
      btn.type = "button";
      btn.dataset.id = opt.id;
      btn.append(el("span", "opt-label", opt.label));
      if (opt.summary) btn.append(el("span", "opt-summary", opt.summary));
      if (opt.help) {
        const details = el("details", "creation-help");
        details.append(el("summary", "", "Help"), el("p", "", opt.help));
        details.addEventListener("click", (ev) => ev.stopPropagation());
        btn.append(details);
      }
      btn.addEventListener("click", () => sendLine(opt.id));
      optionsBox.append(btn);
    }
    const actions = el("div", "creation-actions");
    const backBtn = el("button", "btn-back", "Back");
    backBtn.type = "button";
    backBtn.addEventListener("click", () => sendLine("back"));
    const cancelBtn = el("button", "btn-cancel", "Cancel");
    cancelBtn.type = "button";
    cancelBtn.addEventListener("click", () => sendLine("cancel"));
    actions.append(backBtn, cancelBtn);

    panel.append(title, optionsBox, actions);
  } else if (data.step === "confirm") {
    const title = el("h3", "", "Confirm your character:");
    const answersBox = el("div", "choices-list");
    for (const ans of data.answers || []) {
      const row = el("div", "choice");
      row.append(el("span", "title", `${ans.title}: `), el("span", "label", ans.label));
      answersBox.append(row);
    }
    const actions = el("div", "creation-actions");
    const createBtn = el("button", "btn-create", "Create");
    createBtn.type = "button";
    createBtn.addEventListener("click", () => sendLine("yes"));
    const backBtn = el("button", "btn-back", "Back");
    backBtn.type = "button";
    backBtn.addEventListener("click", () => sendLine("back"));
    const cancelBtn = el("button", "btn-cancel", "Cancel");
    cancelBtn.type = "button";
    cancelBtn.addEventListener("click", () => sendLine("cancel"));
    actions.append(createBtn, backBtn, cancelBtn);

    panel.append(title, answersBox, actions);
  }
}

/**
 * Draws the character choices and traits panel.
 * @param {HTMLElement} panel
 * @param {HTMLElement} choicesBox
 * @param {HTMLElement} traitsList
 * @param {TraitsData} data
 */
export function drawTraits(panel, choicesBox, traitsList, data) {
  const hasChoices = data && data.choices && data.choices.length > 0;
  const hasTraits = data && data.traits && data.traits.length > 0;
  if (!hasChoices && !hasTraits) {
    panel.hidden = true;
    return;
  }
  panel.hidden = false;
  choicesBox.replaceChildren();
  if (hasChoices) {
    for (const c of data.choices) {
      const row = el("div", "choice");
      row.append(el("span", "title", `${c.title}: `), el("span", "label", c.label));
      choicesBox.append(row);
    }
  }
  traitsList.replaceChildren();
  if (hasTraits) {
    for (const t of data.traits) {
      const li = el("li", "trait", t.label);
      if (t.doc) li.title = t.doc;
      traitsList.append(li);
    }
  }
}

/**
 * Draws the skills the character knows, one line each, hidden when it knows none.
 * @param {HTMLElement} panel
 * @param {HTMLElement} list
 * @param {SkillsData} data
 */
export function drawSkills(panel, list, data) {
  const skills = data.skills || [];
  list.replaceChildren();
  for (const s of skills) {
    const li = el("li", "skill", `${s.name} ${s.proficiency}%`);
    li.title = s.kind;
    list.append(li);
  }
  panel.hidden = skills.length === 0;
}
