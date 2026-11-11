package gups.cli

/** The stylesheet of the generated reference, written as `style.css`. */
internal val DOCS_STYLE =
  """
  :root {
    color-scheme: dark;
    --bg: #111418;
    --panel: #1a1f25;
    --text: #d8dee4;
    --muted: #8b949e;
    --accent: #4ea1ff;
    --border: #2a3139;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
  }
  header {
    position: sticky;
    top: 0;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
    padding: 8px 16px;
  }
  .brand { font-weight: 600; }
  .engine { color: var(--muted); font-weight: 400; }
  nav { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 4px; }
  nav a.current { color: var(--text); font-weight: 600; }
  main { max-width: 1100px; margin: 0 auto; padding: 8px 16px 48px; }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
  pre {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 8px 12px;
    overflow-x: auto;
  }
  .entry {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 4px 16px 12px;
    margin: 16px 0;
  }
  .entry .entry { background: var(--bg); }
  .entry pre { background: var(--bg); }
  .badge {
    display: inline-block;
    border: 1px solid var(--accent);
    color: var(--accent);
    border-radius: 10px;
    padding: 0 8px;
    font-size: 12px;
    font-weight: 400;
  }
  .badge.plugin { border-color: var(--muted); color: var(--muted); }
  .badge.feature { border-color: #d29922; color: #d29922; }
  .since { color: var(--muted); font-size: 12px; font-weight: 400; }
  .intro, .caption { color: var(--muted); }
  .caption { margin-bottom: 0; font-size: 13px; }
  table { border-collapse: collapse; width: 100%; margin: 8px 0; display: block; overflow-x: auto; }
  th, td { border: 1px solid var(--border); padding: 4px 8px; text-align: left; vertical-align: top; }
  th { background: var(--bg); }
  tr:target, .entry:target { outline: 2px solid var(--accent); }
  """
    .trimIndent() + "\n"
