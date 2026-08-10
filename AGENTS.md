# AGENTS.md

## Project Overview

**RPA Engine (Dynamic)** (`ai.rever.boss.plugin.dynamic.rpaengine`) is a dynamic plugin for the BOSS desktop application.

Execute recorded RPA workflows

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.rpaengine`
- **Main Class**: `ai.rever.boss.plugin.dynamic.rpaengine.RpaengineDynamicPlugin`
- **API Version**: 1.0.20

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.

## Executing a plan: what actually goes wrong

Every defect fixed while getting an LLM-generated plan to run end to end reported **success**
while doing nothing. That is the failure mode to design against here, not exceptions.

- `startExecution()` opened with `_selectedConfig.value ?: return`, and `rpa_run` reported
  `"Started RPA execution"` regardless. Nothing was loaded, nothing ran, status stayed IDLE.
  A run with no configuration is now an error in the log and a refusal from the tool.
- `findAvailableConfigurations()` scanned the RPA Recorder's directory and `~/Downloads`, but
  **not the engine's own `~/.boss/config/rpaengine`** - so a plan written where this plugin
  keeps its files was invisible. `settings.json` lives in that directory and is excluded by
  name, not by relying on a decode failure.
- The `text` selector scanned `a,button,input,span,div` and took the **first** match. An
  ancestor shares its descendant's `innerText` and comes first in document order, so a wrapper
  `div` won over the `a` inside it; `.click()` on that div did nothing and the action passed.
  `textSelectorScript` now drops every match that *contains* another match (that is what a
  wrapper is), prefers a clickable one, and steps to the enclosing anchor. Note it excludes
  ancestors rather than taking the *last* match: a label appearing twice on the page (nav and
  card) makes "last" a different element, not a deeper one.
- A synthetic `KeyboardEvent` never performs a **default action**. Dispatching Enter into a
  search box ran every page handler and left the form unsubmitted - success, no search.
  `keyPressScript` submits the enclosing form itself for Enter, unless a handler called
  `preventDefault` (the page implements Enter) or there is no form.
- Element lookup was a single attempt. It now polls to `ELEMENT_TIMEOUT_MS`.
- A tag-qualified CSS selector carries its tag-stripped form (`input[name='q']` -> `[name='q']`)
  as an **alternative in the same probe**, logged at WARNING when the fallback is what matched.
  Generated plans guess the tag and Google's search box is a `textarea`. It is one probe, not a
  retry: two sequential deadlines made every miss cost 10s and let the fallback win only after
  the primary had exhausted its own.

**The probe must never carry the action's body.** `awaitElement` polls `!!(locate)` and nothing
else; the body runs once, after. When they were one script, any eval whose completion value did
not come back - a body that threw, a click that navigated and tore down the frame - re-ran the
mutation on every poll, turning one submit into fifty.

**A run cannot be started or reloaded over a live one.** `rpa_run` and `rpa_load` are reachable
from an agent in a loop; without the guard a second start cleared the results mid-run and
replaced `executionJob` *without cancelling it*, so the old job appended a stale result into the
new run and orphaned its tab. `browserIntegration`/`currentTabId` are also cleared per run: stale
handles meant a failed `createBrowserTab` logged "simulation mode" while still driving the old tab.

**`rpa_load` only loads from directories BOSS manages.** The scan includes `~/Downloads` for the
human picking in the panel, but `run_script` executes arbitrary JavaScript in a tab holding the
user's session, so a downloaded file must not be reachable by an agent resolving a substring.
`isManagedPath` is the gate; a person clicking a downloaded plan is choosing it, an agent is not.

`elementScript` returns `Boolean?` where **null means the selector cannot be resolved at all**
and **false means resolved but no match**. Callers report those differently; a change that
collapses them turns "no element matched `X`" back into a useless "cannot resolve a css selector".

MCP tools are `rpa_status`, `rpa_load`, `rpa_run`, `rpa_stop`, `rpa_results`. `rpa_load` exists
because loading a configuration was UI-only, which made the whole plugin undriveable by an agent.

### Testing

`./gradlew test` - pure functions only (`asJsString`, `matchByName`, `stripTagQualifier`,
`keyPressScript`, `textSelectorScript`). There is no detekt/ktlint in this repo.

Every test here was mutation-checked: inverting the selector precedence, dropping the descendant
guard, removing the Enter submit clause, and interpolating instead of escaping each make a
specific named test fail. Add tests the same way - a JS-emitting helper is trivially "tested" by
a string assertion that also passes on the broken version.

A fixture can silently fail to discriminate. `ConfigMatchTest`'s exact-wins case first used
entries where nothing else contained the query, so a substring-first implementation found nothing
on its first pass and returned the exact match anyway: the test passed on the very mutation it
named. The earlier entry has to actually contain the query.

Nothing runs `./gradlew test` in CI - `build.yml` fires only on push to `main` and delegates to
the shared release workflow. These tests protect local development only; a PR-triggered job
running `./gradlew test` would make the policy above enforceable, and is worth adding.

`TextSelectorScriptTest` dumps the generated script to `build/tmp/text-selector-images.js`. String
assertions cannot show that a locator picks the right *node*, so paste that file into a real page
(`browser_run_js` against a results tab) and check what it resolves to. On Google's results page
the fixed script returns the `A` carrying `udm=2`; the old one returned a `DIV[role=listitem]`.

### Second review round

- **Resume must cancel the paused job.** `pauseExecution` only flips the status flag, so the
  previous job is alive and can be inside `awaitElement` for up to `ELEMENT_TIMEOUT_MS`. Resuming
  without cancelling let it see `EXECUTING` again and continue from *i+1* while the new job started
  from *i* - the double-start bug, reached through resume. Loading also refuses while `PAUSED` for
  the same reason.
- **The text scan prefilters on `textContent`.** `innerText` is layout-dependent, so reading it per
  candidate forces layout across the whole set, and the expression is re-evaluated every poll for
  five seconds: a `text` selector that misses would be ~50 full-page layout passes. `textContent`
  is free and can only over-select; the `innerText` pass then removes the rest. The candidate tags
  include headings and list items because a plan says "click the result titled X" and that title is
  an `h3`.
- **The tag-stripped fallback requires a rendered element** (`visibleQuerySelector`). Dropping the
  tag widens the match, and `[name='q']` also matches `<input type=hidden>` or an off-screen
  duplicate; writing to one of those made the probe true and the action report ok while nothing
  visible happened. The widening would otherwise have reintroduced the very failure this file is
  about.
- **Injected bodies are wrapped in an IIFE.** `var` at eval top level lands on the page's global
  object, so `el`/`ev`/`f` clobbered any page globals with those names.
- **Simulation mode is not allowed to look like execution.** It used to inject a random 5% failure
  and pass *any* verb through its `else`, so with no browser an unimplemented verb was
  indistinguishable from a working one and `rpa_results` looked like a real run. Unsupported verbs
  fail there too, and every simulated outcome is tagged.
- **`rpa_load` distinguishes "not managed" from "no match".** A configuration the user can see in
  the panel (from `~/Downloads`) reported "No configuration matched", which reads as a bug rather
  than the deliberate policy it is. `LoadOutcome` carries the four cases, from a single scan.
- `isManagedPath` wraps `canonicalFile`, not just `startsWith` - the former is what throws - and
  fails closed.
- `navigate` takes http, https and about only. `run_script` is a declared verb so this is not a new
  capability, but a navigate step should navigate, and `location.href = 'javascript:...'` executes.
