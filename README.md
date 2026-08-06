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

These act on the most recently opened panel instance and return an error if the panel is
closed. None of them is permission-gated, including the two that start automation.

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
