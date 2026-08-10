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
  `textSelectorScript` now prefers a clickable match, takes the deepest, and steps to the
  enclosing anchor.
- A synthetic `KeyboardEvent` never performs a **default action**. Dispatching Enter into a
  search box ran every page handler and left the form unsubmitted - success, no search.
  `keyPressScript` submits the enclosing form itself for Enter, unless a handler called
  `preventDefault` (the page implements Enter) or there is no form.
- Element lookup was a single attempt. It now polls to `ELEMENT_TIMEOUT_MS`, and a tag-qualified
  CSS selector that misses is retried with the tag dropped (`input[name='q']` -> `[name='q']`),
  logged at WARNING. Generated plans guess the tag and Google's search box is a `textarea`.

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

`TextSelectorScriptTest` dumps the generated script to `build/tmp/text-selector-images.js`. String
assertions cannot show that a locator picks the right *node*, so paste that file into a real page
(`browser_run_js` against a results tab) and check what it resolves to. On Google's results page
the fixed script returns the `A` carrying `udm=2`; the old one returned a `DIV[role=listitem]`.
