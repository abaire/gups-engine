// The side panel, drawn from GMCP: vitals bars, affects and bands.

/**
 * @typedef {{name: string, label: string}} Named
 * @typedef {{stats: Named[], vitals: Named[], damage_types: string[], features: string[]}} Schema
 * @typedef {Object<string, number>} Vitals The current and maximum of each vital: `hp` and `maxhp`.
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
    const bar = el("div", `bar tone${i % TONES}`);
    bar.dataset.vital = name;
    bar.dataset.value = `${now}/${max}`;
    const top = el("div", "label");
    top.append(el("span", "", label), el("span", "", `${now}/${max}`));
    const track = el("div", "track");
    const fill = el("div", "fill");
    fill.style.width = `${Math.max(0, Math.min(100, (100 * now) / max))}%`;
    track.append(fill);
    bar.append(top, track);
    box.append(bar);
  });
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
 */
export function drawBands(box, bands) {
  box.replaceChildren();
  for (const band of ["melee", "near", "far"]) {
    const entries = bands[band] || [];
    const row = el("div", "band");
    row.append(el("div", "name", band));
    if (entries.length === 0) row.append(el("div", "who", "-"));
    for (const e of entries) row.append(el("div", `who ${e.kind}`, e.name));
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
