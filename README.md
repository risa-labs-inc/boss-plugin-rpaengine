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
| `rpa_observe` | List the interactive elements rendered in a browser tab, with a selector for each |
| `rpa_step` | Perform one action in a browser tab |

`rpa_status`, `rpa_run` and `rpa_stop` act on the most recently opened panel instance and return
an error if the panel is closed. None of the tools is permission-gated, including the ones that
start automation or act on a page.

`rpa_observe` and `rpa_step` take a `tab_id` (from `tabs_list`) and work on any browser tab with
no panel open.

- `rpa_observe {tab_id, max_elements?}` (read-only; `max_elements` 1-200, default 80). Returns
  `{tab_id, url, title, truncated, elements[]}`. Each element has `id` (`e1`..`eN`, in-viewport
  first, then document order), `role`, `tag`, `label` (accessible name, at most 80 chars),
  `type`, `placeholder`, `href`, `options` (a select's first 30 option labels), `checked`
  (checkbox/radio), `sensitive`, `in_viewport` and a `selector` (`id`, `css` or `xpath`) checked
  in-page to match only that element. Disabled and unrendered elements are skipped. Field values
  and contenteditable text are never read. `sensitive` is true for password fields,
  `cc-*`/`one-time-code` autocomplete, and names/ids matching `pass|pwd|card|cvv|ssn|otp`.
- `rpa_step {tab_id, action: {type, selector?, value?}, highlight_ms?}`. `type` is one of
  `click`, `input`, `select`, `keypress`, `submit`, `scroll`, `navigate`, `wait`, run through the
  same code as a plan step. `selector` is `{type: id|css|xpath|text, value}`; it is required for
  `click`, `input`, `select` and `submit`, and a `keypress` without one targets the focused
  element. `wait` is capped at 10000 ms. `run_script`, `screenshot`, `switch_frame`, `assert`
  and unknown types are refused with `UNSUPPORTED_ACTION`. The target is outlined for
  `highlight_ms` (0-2000, default 600) before the action runs, and the outline is removed first.
  Returns `{ok, error, url_before, url_after, navigated, duration_ms}`; an action that runs but
  fails is `ok: false`, not a tool error.
- Tool errors are `{"error": {"code", "message"}}` with `isError` set. Codes: `INVALID_INPUT`,
  `TAB_NOT_FOUND`, `NO_BROWSER`, `SCRIPT_FAILED` (observe), `UNSUPPORTED_ACTION` (step).

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
