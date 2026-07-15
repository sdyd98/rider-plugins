# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A monorepo of JetBrains/Rider plugins (Kotlin, IntelliJ Platform Gradle Plugin 2.16.0, target Rider 2026.1.3 / build 261):

- `:xlsx-editor` — read-only `.xlsx`/`.xls` grid viewer + relationship-graph explorer (`refs.json`) + MCP tools. Detailed behavior docs in `xlsx-editor/README.md`.
- `:log-viewer` — read-only local + remote (SSH/SFTP) log viewer with live tail. Detailed docs in `log-viewer/README.md` (Korean); releases in `log-viewer/CHANGELOG.md`.
- `:common` — shared library: POI helpers (`PoiClassLoaders.kt`, `CellFormatting.kt`; package `com.example.xlsx`) and the vim navigation base class (`com.example.grid.VimTableController`). Applies `org.jetbrains.intellij.platform.module` (no `plugin.xml`, no ZIP of its own). Each plugin bundles these classes via `pluginComposedModule(implementation(project(":common")))` — composed into the plugin's MAIN jar, because plugin classes extend them directly; do not switch this to plain `implementation` (the jar would land in `lib/modules/` as an unloaded v2 content module) .

The module READMEs are the real documentation — they are detailed and kept current. When you change behavior, update the module README (and `log-viewer/CHANGELOG.md` for log-viewer).

## Build & test commands

Gradle needs a **JDK 21** toolchain. On this machine that is already configured globally (`~/.zshrc` exports `JAVA_HOME` to Temurin 21; `~/.gradle/gradle.properties` sets `riderLocalPath=/Applications/Rider.app` and the toolchain path), so the wrapper just works. On an unconfigured machine, point `JAVA_HOME` at any JDK 21 first.

```bash
./gradlew :xlsx-editor:buildPlugin   # -> xlsx-editor/build/distributions/xlsx-editor-<ver>.zip
./gradlew :log-viewer:buildPlugin    # -> log-viewer/build/distributions/log-viewer-<ver>.zip
./gradlew buildPlugin                # all plugins
./gradlew :xlsx-editor:runIde        # sandbox Rider with the plugin loaded (interactive — don't run headless)

./gradlew test                       # all headless unit tests
./gradlew :log-viewer:test           # log pipeline tests (LogPipelineTest, LineFormatTest)
./gradlew :common:test               # vim state-machine tests (VimTableControllerTest)
./gradlew :log-viewer:test --tests "com.example.logview.LogPipelineTest"   # single test class
```

`buildPlugin` succeeding is the standard verification for changes; there is no UI test harness. Headless tests cover the log pipeline (byte→line→parse→model) and the shared vim state machine (counts/marks over a plain Swing `JTable`) — put new pure logic where it can be tested the same way.

## Gradle layout (root vs module)

- Root `build.gradle.kts` declares plugin *versions* once with `apply false`; module build files apply them without versions. Shared dependency versions live in `gradle/libs.versions.toml`.
- `riderLocalPath` (machine-global gradle.properties) makes builds compile against the locally installed Rider instead of downloading the ~1.5 GB IDE. Module build files branch on it: `local(riderLocalPath)` vs `rider("2026.1.3")`.
- `kotlin.stdlib.default.dependency=false` — the IDE provides the Kotlin stdlib; never bundle one.
- Compose/Jewel/Gson are referenced via `bundledLibrary(...)` (IDE provides them at runtime; they must NOT ship in the plugin ZIP). POI and JSch are real `implementation` deps that DO ship in the ZIP.
- New plugin = copy a module's `build.gradle.kts`, add `src/main/kotlin` + `src/main/resources/META-INF/plugin.xml`, `include(":<name>")` in `settings.gradle.kts`.
- Module version lives in each module's `build.gradle.kts` (`version = "..."`). Release commits follow the pattern `Release xlsx-editor 0.6.2 — <summary>`.

## Architecture invariants

- **Both plugins are strictly read-only viewers.** No write-back to files or remote hosts — don't add an edit/save path.
- **Every Apache POI call must be wrapped in `withPoiClassLoader { ... }`** (from `:common`). XMLBeans/JAXP service discovery otherwise resolves against the IDE classloader and parsing fails at runtime, not compile time.
- **Shared design language between the two plugins:** virtualized Swing `JBTable` grid (Swing kept deliberately for large-file performance), filter-only `TableRowSorter` (sorting disabled / attached after streaming so row appends never reshuffle), an always-on modal vim controller (`VimGridController` / `VimLogController`, both extending `:common`'s `VimTableController` which owns key installation, count/mark state, and the scroll family) installed on the table's `WHEN_FOCUSED` InputMap, and Compose/Jewel chrome (filter bar, status bar, popups) around the Swing grid. New UI should follow this split: Swing for the data grid, Compose for chrome.
- **When writing or touching ANY UI, follow `docs/UI-GUIDELINES.md`** — modern-platform rules (JB components over raw Swing, scheme-sourced colors + the deletions-are-red decision, `JBUI.scale`, EDT rules, viewport-sync-not-model-sharing, `UiDataProvider`) distilled from real incidents in this repo.
- Vim controllers convert view↔model indices explicitly so navigation/marks survive filtering and streaming; marks are stored in model coordinates.
- Shortcuts that must beat IDE-global bindings (`Ctrl+D/U/E/Y`, `Ctrl+Alt+F`, …) are registered via `DumbAwareAction.registerCustomShortcutSet`, not the InputMap.
- xlsx open path is streaming/lazy/parallel by design (SAX per sheet, compact display strings, batched row push) — keep memory and first-paint characteristics in mind when touching `XlsxStreamingReader`/`SheetTableModel`.

## refs.json / MCP tools — judgment-free rule (firm user decision)

The `RefsMcpToolset` tools must stay **mechanical only**: enumerate sheets, expose raw cells, compute set-arithmetic numbers, validate. ALL interpretation — header/data layout, id columns, display columns, which column references which table — is the calling AI's job, made primarily from the game source code. Do not add heuristics, guessing, or auto-detection to the tool side. `build_refs` writes `{file, sheet}` skeletons only, additively (re-runs must never clobber filled-in entries). `_`-prefixed keys in `refs.json` are AI metadata the viewer ignores. See `xlsx-editor/README.md` § "Relationship graph & refs.json" and `samples/gamedata/refs.json`.
