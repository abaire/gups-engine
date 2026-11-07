// The GUPS web client: log in over HTTPS, then play over a WebSocket.
// Every frame is one JSON message:
//   server: {"t":"text","d":"..."} {"t":"prompt","d":"..."} {"t":"hide","d":true}
//           {"t":"gmcp","p":"Char.Schema","d":{...}} {"t":"gmcp","p":"Char.Vitals","d":{...}} {"t":"error","d":"..."}
//   client: {"t":"auth","d":token} {"t":"input","d":"..."} {"t":"gmcp","p":"Core.Supports.Set","d":[...]}

import { render } from "./markup.js";
import { drawAffects, drawBands, drawCreation, drawGroup, drawRoom, drawSkills, drawTraits, drawVitals } from "./panels.js";

const $ = (id) => document.getElementById(id);
const output = $("output");
const input = $("input");
const HISTORY_MAX = 100;
const OUTPUT_MAX = 2000;

/** @type {WebSocket | null} */
let socket = null;
let token = null;
let style = {};
const history = [];
let historyAt = 0;

function showLogin(message) {
  $("game").hidden = true;
  $("signup").hidden = true;
  $("login").hidden = false;
  $("login-error").textContent = message || "";
  $("login-name").focus();
}

function showGame() {
  $("login").hidden = true;
  $("game").hidden = false;
  input.focus();
}

/**
 * Appends game text, keeping the last OUTPUT_MAX lines.
 * @param {string} text
 * @param {string} [cls]
 */
function print(text, cls) {
  const atBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 40;
  const line = document.createElement("div");
  if (cls) line.className = cls;
  render(line, text.replace(/\n$/, ""), style);
  output.append(line);
  while (output.childElementCount > OUTPUT_MAX) output.firstElementChild.remove();
  if (atBottom) output.scrollTop = output.scrollHeight;
}

function sendFrame(frame) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(frame));
}

/** @type {import("./panels.js").Schema} */
let schema = { stats: [], vitals: [], damage_types: [], features: [] };
let lastVitals = null;
let lastBands = null;
let lastGroup = null;

const groupNames = () => new Set((lastGroup?.members ?? []).map((m) => m.name));

function onGmcp(pkg, data) {
  switch (pkg) {
    case "Char.Schema":
      schema = data;
      if (lastVitals) drawVitals($("vitals"), schema, lastVitals);
      if (lastGroup) drawGroup($("group-panel"), $("group-list"), schema, lastGroup);
      break;
    case "Char.Vitals":
      lastVitals = data;
      drawVitals($("vitals"), schema, data);
      break;
    case "Char.Group":
      lastGroup = data;
      drawGroup($("group-panel"), $("group-list"), schema, data);
      if (lastBands) drawBands($("bands"), lastBands, groupNames());
      break;
    case "Char.Affects": drawAffects($("affects"), data); break;
    case "Room.Bands":
      lastBands = data;
      drawBands($("bands"), data, groupNames());
      break;
    case "Room.Info": drawRoom($("room-name"), data); break;
    case "Comm.Channel.Text": break; // The text arrives as a text frame too.
    case "Gups.Trace": print(data.line, "trace"); break;
    case "Char.Creation":
      drawCreation($("creation"), data, (line) => {
        sendFrame({ t: "input", d: line });
        print(line, "notice");
      });
      break;
    case "Char.Traits":
      drawTraits($("traits-panel"), $("choices-list"), $("traits-list"), data);
      break;
    case "Char.Skills": drawSkills($("skills-panel"), $("skills-list"), data); break;
  }
}

function connect() {
  const url = new URL("/ws", location.href);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(url);
  socket = ws;
  let playing = false;
  ws.addEventListener("open", () => {
    if (token) sendFrame({ t: "auth", d: token });
    sendFrame({ t: "gmcp", p: "Core.Supports.Set", d: ["Char 1", "Room 1", "Comm.Channel 1", "Gups 1"] });
  });
  ws.addEventListener("message", (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    switch (m.t) {
      case "text":
        if (!playing) { playing = true; showGame(); }
        print(m.d);
        break;
      case "prompt": $("prompt").textContent = m.d.trim(); break;
      case "hide": input.type = m.d ? "password" : "text"; break;
      case "gmcp": onGmcp(m.p, m.d); break;
      case "error":
        if (!playing) showLogin(m.d === "Please log in first; your session may have expired." ? "" : m.d);
        else print(m.d, "notice");
        break;
    }
  });
  ws.addEventListener("close", () => {
    if (socket !== ws) return;
    socket = null;
    lastGroup = null;
    lastBands = null;
    drawCreation($("creation"), null, () => {});
    drawGroup($("group-panel"), $("group-list"), schema, null);
    if (playing) print("Disconnected. Reload the page to connect again.", "notice");
    else showLogin($("login-error").textContent);
  });
}

$("login").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  $("login-error").textContent = "";
  const body = JSON.stringify({ login: $("login-name").value, password: $("login-password").value });
  let res;
  try {
    res = await fetch("/api/login", { method: "POST", headers: { "Content-Type": "application/json" }, body, credentials: "same-origin" });
  } catch {
    $("login-error").textContent = "The server can't be reached.";
    return;
  }
  const reply = await res.json().catch(() => ({}));
  if (!res.ok) {
    $("login-error").textContent = reply.error || `Login failed (${res.status}).`;
    return;
  }
  token = reply.token;
  $("login-password").value = "";
  connect();
});

/**
 * POSTs JSON and returns [ok, reply], or [false, {error}] when the server can't be reached.
 * @param {string} path
 * @param {object} body
 */
async function post(path, body) {
  try {
    const res = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), credentials: "same-origin" });
    const reply = await res.json().catch(() => ({}));
    return [res.ok, res.ok ? reply : { error: reply.error || `That failed (${res.status}).` }];
  } catch {
    return [false, { error: "The server can't be reached." }];
  }
}

// Sign-up: check the instructor's code first, which says whether it is bound
// to an account (a roster setup code) or creates one (an invite), then choose a password.
let signupChecked = false;
let signupBound = null;

function showSignup() {
  $("login").hidden = true;
  $("signup").hidden = false;
  signupChecked = false;
  for (const id of ["signup-bound", "signup-login-label", "signup-password-label", "signup-again-label"]) $(id).hidden = true;
  $("signup-code").readOnly = false;
  $("signup-submit").textContent = "Check code";
  $("signup-error").textContent = "";
  $("signup-code").focus();
}

$("to-signup").addEventListener("click", showSignup);
$("to-login").addEventListener("click", () => showLogin(""));

$("signup").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  $("signup-error").textContent = "";
  const code = $("signup-code").value;
  if (!signupChecked) {
    const [ok, reply] = await post("/api/code", { code });
    if (!ok) { $("signup-error").textContent = reply.error; return; }
    signupChecked = true;
    signupBound = reply.login;
    $("signup-code").readOnly = true;
    $("signup-bound").hidden = !signupBound;
    $("signup-bound").textContent = signupBound ? `This code sets up the account '${signupBound}'.` : "";
    $("signup-login-label").hidden = !!signupBound;
    $("signup-password-label").hidden = false;
    $("signup-again-label").hidden = false;
    $("signup-submit").textContent = "Sign up";
    (signupBound ? $("signup-password") : $("signup-login")).focus();
    return;
  }
  const password = $("signup-password").value;
  if (password !== $("signup-again").value) { $("signup-error").textContent = "The passwords don't match."; return; }
  const [ok, reply] = await post("/api/signup", { code, login: signupBound ? null : $("signup-login").value, password });
  if (!ok) { $("signup-error").textContent = reply.error; return; }
  token = reply.token;
  $("signup-password").value = "";
  $("signup-again").value = "";
  connect();
});

$("command").addEventListener("submit", (ev) => {
  ev.preventDefault();
  const line = input.value;
  sendFrame({ t: "input", d: line });
  if (input.type === "password") {
    input.type = "text";
  } else {
    print(line, "notice");
    if (line && history[history.length - 1] !== line) history.push(line);
    if (history.length > HISTORY_MAX) history.shift();
  }
  historyAt = history.length;
  input.value = "";
});

input.addEventListener("keydown", (ev) => {
  if (ev.key === "ArrowUp" && historyAt > 0) {
    historyAt--;
    input.value = history[historyAt];
    ev.preventDefault();
  } else if (ev.key === "ArrowDown" && historyAt < history.length) {
    historyAt++;
    input.value = history[historyAt] ?? "";
    ev.preventDefault();
  }
});

$("logout").addEventListener("click", async () => {
  await fetch("/api/logout", { method: "POST", credentials: "same-origin", headers: token ? { Authorization: `Bearer ${token}` } : {} }).catch(() => {});
  token = null;
  const ws = socket;
  socket = null;
  ws?.close();
  lastGroup = null;
  lastBands = null;
  drawCreation($("creation"), null, () => {});
  drawGroup($("group-panel"), $("group-list"), schema, null);
  output.replaceChildren();
  showLogin("You have logged out.");
});

// A valid session cookie goes straight to the game; otherwise the server says to log in.
connect();
