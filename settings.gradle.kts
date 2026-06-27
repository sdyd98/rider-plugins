rootProject.name = "rider-plugins"

// Monorepo of JetBrains/Rider plugins:
//   :common      shared library (POI helpers, and later grid/vim infrastructure)
//   :xlsx-editor the in-IDE .xlsx/.xls grid viewer plugin
// Add new plugins as sibling modules: create <plugin>/build.gradle.kts + src, then include it here.
include(":common", ":xlsx-editor")

// No foojay toolchain resolver: this machine has no standalone JDK on PATH, so the build is run
// with JAVA_HOME pointing at a JDK 21 (e.g. a JetBrains Runtime), which Gradle auto-detects as the
// toolchain. Add the foojay-resolver-convention plugin here to let Gradle auto-download JDK 21.
