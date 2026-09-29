# BOSS RPA Engine

Replay recorded browser workflows, from the right sidebar.

Loads the workflows that [RPA
Recorder](https://github.com/risa-labs-inc/boss-plugin-rparecorder) saves and drives them
against a real BOSS browser tab, generating JavaScript per action and selector type.

## What it does

- **Load a workflow** from `~/.boss/config/rpaengine` or straight from RPA Recorder's own
  `~/.boss/config/rparecorder/configurations`, with a recents list.
- **Execute against a live browser tab**, created through `activeTabsProvider`. Supported
  actions are click, input, select, navigate, wait, scroll, switch_frame, run_script,
  screenshot and assert, each resolved by css, xpath, text or id.
- **Run, pause, stop and reset**, with per-action results and durations and a live execution
  log at four levels.
- **Tune the run**: an execution speed slider (0.5x to 2.0x), a human-like mode that jitters
  delays, and a stop-on-error toggle. All three persist to `~/.boss/config/rpaengine`.
- **Execution summary**: total, completed, failed and skipped actions, plus total duration.

## Simulation mode, and why it matters

When no `BrowserService` or `ActiveTabsProvider` is available, the engine does **not** fail. It
falls back to simulation: it fakes plausible timing and a roughly 95% success rate, so the run
fills with green ticks and the summary reports success while **nothing touches a browser**.

The only signal is a single `WARNING` line in the execution log reading "running in simulation
mode". `rpa_run` over MCP reports success either way. Check the log before trusting a green
run.

## MCP tools

| Tool | Purpose |
|---|---|
| `rpa_status` | Execution state, current action, result count |
| `rpa_run` | Start or resume execution |
| `rpa_stop` | Stop execution |
| `rpa_observe` | List the interactive elements and large images rendered in a browser tab, with a selector for each |
| `rpa_step` | Perform one action in a browser tab, including downloading an element's image or linked file |

`rpa_status`, `rpa_run` and `rpa_stop` act on the most recently opened panel instance and return
an error if the panel is closed. None of the tools is permission-gated, including the ones that
start automation or act on a page.

`rpa_observe` and `rpa_step` take a `tab_id` (from `tabs_list`) and work on any browser tab with
no panel open, including the user's logged-in tabs. That is deliberate (LLM RPA drives the tab the
user chose); see "Any-tab reach" in AGENTS.md for the mitigations. Both are declared non-read-only,
so a host that asks before non-read-only tools asks for them.

- `rpa_observe {tab_id, max_elements?}` (`max_elements` 1-200, default 80). Returns
  `{tab_id, url, title, truncated, elements[]}`. Each element has `id` (`e1`..`eN`, in-viewport
  first, then document order), `role`, `tag`, `label` (accessible name, at most 80 chars),
  `type`, `placeholder`, `href`, `image`, `options` (a select's first 30 option labels), `checked`
  (checkbox/radio), `sensitive`, `in_viewport` and a `selector` (`id`, `css` or `xpath`) checked
  in-page to match only that element (an SVG/MathML element's xpath names it by `local-name()`).
  Disabled and unrendered elements are skipped; shadow DOM and iframe contents are not observed.
  Typed text, selected options and contenteditable text are never read; checkbox/radio `checked`
  state is. `sensitive` is true for password fields, `cc-*`, `one-time-code`, `current-password`
  and `new-password` autocomplete, a field whose aria-label, label, placeholder or title matches the
  same words, and names/ids containing `password|passwd|passcode|pwd|cvv|cvc`
  or a whole word `pass|pin|otp|ssn|card|csc|cc` (camelCase and `-`/`_` split words, so
  `userPass` is flagged and `passenger`, `compass`, `discard` are not).
  - `image` is `{src, alt, width, height}` when the element is an `<img>` or contains one rendered
    at 48x48 or larger, else null. `src` is absolute: the largest `srcset` candidate, else
    `currentSrc`, else `src`; a `data:` URI over 200 chars is reported as null. `width`/`height`
    are the rendered size.
  - When an element with an image has no accessible name, `label` falls back to the image's `alt`,
    then `title`, then the enclosing `<figure>`'s `figcaption`, then a file name from the link
    href or image src (`/wiki/File:Persian_cat.jpg` -> `Persian cat.jpg`).
  - After the interactive elements (capped by `max_elements`), up to 20 rendered `<img>` elements
    of 100x100 or larger that are not inside a reported element are appended with role and tag
    `img`. `truncated` is true when either list was cut.
- `rpa_step {tab_id, action: {type, selector?, value?}, highlight_ms?, allow_sensitive?}`. `type` is one of
  `click`, `input`, `select`, `keypress`, `submit`, `scroll`, `navigate`, `wait`, `download`;
  all but `download` run through the same code as a plan step. `selector` is
  `{type: id|css|xpath|text, value}`; it is required for `click`, `input`, `select`, `submit` and
  `download`, and a `keypress` without one targets the focused element. `select` requires a
  `value`; an `input` with an empty or absent `value` clears the field. `wait` is capped at 10000 ms. `run_script`, `screenshot`, `switch_frame`, `assert`
  and unknown types are refused with `UNSUPPORTED_ACTION`. The target is outlined for
  `highlight_ms` (0-2000, default 600) before the action runs, and the outline is removed first.
  Returns `{ok, error, url_before, url_after, navigated, duration_ms, download}`; an action that
  runs but fails is `ok: false`, not a tool error. `navigated` ignores a fragment-only change.
  `download` is null except after a successful download.
  - `input` into a field that `rpa_observe` would flag `sensitive` is refused with `INVALID_INPUT`
    unless `allow_sensitive: true` is passed. The check runs in the typing script, on the same
    element, before anything is typed.
  - A tab an RPA Engine panel run is driving is refused with `TAB_BUSY`, and steps on one tab run
    one at a time.
- `download` saves a file through the browser. It takes the target's link href when that path ends
  in `jpg|jpeg|png|gif|webp|svg|pdf|zip|csv|xlsx|docx|txt|mp4|mp3` (a segment containing `:`,
  such as `File:X.jpg`, is not a file), otherwise the target's own or first descendant `<img>`
  (best source, as in `image`). The page fetches it (`mode: cors`, no credentials) and clicks a
  temporary `<a download>` on a blob URL; progress is polled every 200 ms for up to 10 s. If the
  fetch fails, a same-origin URL is clicked directly (`method: "direct"`, `bytes: null`) and a
  cross-origin one fails with "The site does not allow downloading this file from script; open it
  instead". An HTTP error status fails without fallback. The page is never navigated. The file
  name is the URL's last path segment, decoded, without a `NNNpx-` thumbnail prefix, else
  `download`. Success adds `download: {url, file, bytes, method, save_verified, note}`: `method` is
  `blob-click` or `direct`, and `save_verified` is always false, because the page can trigger a
  download but cannot see whether the host saved it. Credentials are omitted, so a file behind a
  login fails with HTTP 401/403. `download` is not a plan verb: a plan step of that type fails in
  the panel with "only available through rpa_step". The `direct` fallback is a real link click, so
  it does send cookies. A file named `*.json` is never saved (the panel lists those from
  `~/Downloads` as configurations), and an image target must fetch an `image/*` blob. Verified in BOSS's embedded browser
  (JxBrowser), where the file lands in `~/Downloads`.
- Tool errors are `{"error": {"code", "message"}}` with `isError` set. Codes: `INVALID_INPUT`,
  `TAB_NOT_FOUND`, `NO_BROWSER`, `SCRIPT_FAILED` (observe), `UNSUPPORTED_ACTION` and `TAB_BUSY` (step).

## Requirements

- BOSS >= 9.2.20, boss-plugin-api >= 1.0.20
- `browserService` and `activeTabsProvider`, both optional. Absent, you get simulation mode
  rather than an error.
- Writes settings and workflows under `~/.boss/config/`.
- No external binaries.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-rpaengine-*.jar ~/.boss/plugins/
```

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
