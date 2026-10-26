// Game text markup rendered as HTML: {red} ... {white}, {bright_red} ...,
// {bold}, {underline}, {/} to reset, {x0} ... {x255} for xterm colors, and {{ for a brace.
// Unknown tags stay as typed.

const NAMES = ["black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"];
const BASE = [
  "#000000", "#800000", "#008000", "#808000", "#000080", "#800080", "#008080", "#c0c0c0",
  "#808080", "#ff0000", "#00ff00", "#ffff00", "#0000ff", "#ff00ff", "#00ffff", "#ffffff",
];
const TAG = /\{\{|\{(\/|bold|underline|(?:bright_)?(?:black|red|green|yellow|blue|magenta|cyan|white)|x(\d{1,3}))\}/g;

/**
 * The CSS color of xterm-256 color `n`.
 * @param {number} n
 * @returns {string}
 */
export function xterm(n) {
  if (n < 16) return BASE[n];
  if (n < 232) {
    const levels = [0, 95, 135, 175, 215, 255];
    const c = n - 16;
    return `rgb(${levels[Math.floor(c / 36)]}, ${levels[Math.floor(c / 6) % 6]}, ${levels[c % 6]})`;
  }
  const v = 8 + (n - 232) * 10;
  return `rgb(${v}, ${v}, ${v})`;
}

/**
 * Renders `text` with markup into DOM nodes appended to `parent`. Style carries over from
 * one call to the next through `state`, as the telnet renderer's does within one output.
 * @param {HTMLElement} parent
 * @param {string} text
 * @param {{color?: string, bold?: boolean, underline?: boolean}} [state]
 */
export function render(parent, text, state = {}) {
  let last = 0;
  const emit = (s) => {
    if (!s) return;
    if (!state.color && !state.bold && !state.underline) {
      parent.append(document.createTextNode(s));
      return;
    }
    const span = document.createElement("span");
    if (state.color) span.style.color = state.color;
    if (state.bold) span.classList.add("b");
    if (state.underline) span.classList.add("u");
    span.textContent = s;
    parent.append(span);
  };
  for (const m of text.matchAll(TAG)) {
    emit(text.slice(last, m.index));
    last = m.index + m[0].length;
    if (m[0] === "{{") { emit("{"); continue; }
    const tag = m[1];
    const n = m[2] === undefined ? null : Number(m[2]);
    if (n !== null && n > 255) { emit(m[0]); continue; }
    if (tag === "/") { state.color = undefined; state.bold = false; state.underline = false; }
    else if (tag === "bold") state.bold = true;
    else if (tag === "underline") state.underline = true;
    else if (n !== null) state.color = xterm(n);
    else {
      const bright = tag.startsWith("bright_");
      state.color = BASE[NAMES.indexOf(tag.replace("bright_", "")) + (bright ? 8 : 0)];
    }
  }
  emit(text.slice(last));
}

/**
 * Text with the markup removed.
 * @param {string} text
 * @returns {string}
 */
export function strip(text) {
  return text.replace(TAG, (m, tag, n) => (m === "{{" ? "{" : n !== undefined && Number(n) > 255 ? m : ""));
}
