# rider-plugins

A monorepo of JetBrains / Rider plugins (Kotlin), built with the IntelliJ Platform Gradle Plugin.
Shared infrastructure lives in one module; each plugin is its own module that produces its own
installable ZIP.

## Layout

```
rider-plugins/
├─ settings.gradle.kts          registers the modules
├─ build.gradle.kts             declares plugin versions (apply false) for all modules
├─ gradle/libs.versions.toml    shared dependency versions (version catalog)
├─ gradle.properties            riderLocalPath + shared Gradle/Kotlin flags
├─ common/                      shared library (POI helpers; grid/vim infra will move here)
│  └─ src/main/kotlin/…
└─ xlsx-editor/                 plugin: in-IDE .xlsx/.xls grid editor (see its own README)
   ├─ build.gradle.kts
   ├─ README.md
   └─ src/main/{kotlin,resources/META-INF/plugin.xml}
```

- **`common`** — a plain Kotlin library for code shared across plugins (currently the POI
  thread-context-classloader helper and cached-formula formatting). It has no `plugin.xml`; its
  classes are bundled into whichever plugin depends on it (`implementation(project(":common"))`).
  When shared UI (grid renderer, vim controller) moves here it will also apply
  `org.jetbrains.intellij.platform.module` to compile against the platform.
- **`xlsx-editor`** — the first plugin. Opens/edits/saves Excel files in an in-IDE grid. See
  [`xlsx-editor/README.md`](xlsx-editor/README.md) for how it works.

## Build & run

A JDK 21 is required to *run* Gradle. This machine has no standalone JDK on PATH, so point
`JAVA_HOME` at a JetBrains Runtime 21 first (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\PyCharm 2025.2.1.1\jbr"

./gradlew :xlsx-editor:buildPlugin   # -> xlsx-editor/build/distributions/xlsx-editor-<ver>.zip
./gradlew :xlsx-editor:runIde        # launch a sandbox Rider with the plugin loaded
```

`gradle.properties` sets `riderLocalPath` so the build compiles against the locally installed Rider
instead of downloading the ~1.5 GB IDE. Each plugin module has its own `buildPlugin` / `runIde`
tasks; `./gradlew buildPlugin` (no module prefix) builds every plugin.

## Adding a new plugin

1. Create `<plugin>/build.gradle.kts` (copy `xlsx-editor/build.gradle.kts`, adjust `plugin.xml`).
2. Add `src/main/kotlin` + `src/main/resources/META-INF/plugin.xml`.
3. `include(":<plugin>")` in `settings.gradle.kts`.
4. Reuse shared code via `implementation(project(":common"))`.

## Distribution

Each plugin ships as a self-contained ZIP (plugin + bundled libraries such as Apache POI). Install on
another machine via **Settings → Plugins → ⚙ → Install Plugin from Disk…** (the target IDE must be a
compatible build — `xlsx-editor` targets Rider 2026.1.x). Releases are published per plugin (e.g. a
GitHub Release tagged `xlsx-editor-v0.2.0` with that module's ZIP attached).
