// The side panel, drawn from GMCP (M6 design §5.2, §6.2): vitals bars, affects and bands.

/**
 * @typedef {{hp: number, maxhp: number, mana: number, maxmana: number, move: number, maxmove: number}} Vitals
 * @typedef {{name: string, remaining: number, flags: string[]}} Affect
 * @typedef {{id: number, name: string, kind: "actor" | "item"}} BandEntry
 * @typedef {{melee: BandEntry[], near: BandEntry[], far: BandEntry[]}} Bands
 * @typedef {{label: string, name: string, exits: Object<string, string|null>, regions: string[]}} RoomInfo
 */

const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
};

/**
 * @param {HTMLElement} box
 * @param {Vitals} v
 */
export function drawVitals(box, v) {
  box.replaceChildren();
  for (const [key, label] of [["hp", "HP"], ["mana", "Mana"], ["move", "Move"]]) {
    const now = v[key];
    const max = v["max" + key] || 1;
    const bar = el("div", `bar ${key}`);
    bar.dataset.value = `${now}/${max}`;
    const top = el("div", "label");
    top.append(el("span", "", label), el("span", "", `${now}/${max}`));
    const track = el("div", "track");
    const fill = el("div", "fill");
    fill.style.width = `${Math.max(0, Math.min(100, (100 * now) / max))}%`;
    track.append(fill);
    bar.append(top, track);
    box.append(bar);
  }
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
