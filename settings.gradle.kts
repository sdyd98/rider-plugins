rootProject.name = "rider-plugins"

// Monorepo of JetBrains/Rider plugins:
//   :common      shared library (POI helpers, and later grid/vim infrastructure)
//   :xlsx-editor the in-IDE .xlsx/.xls grid viewer plugin
//   :log-viewer  the local + remote (SSH/SFTP) log file viewer plugin
//   :codemap     per-file code-understanding notes (.codemap/) authored by an AI over MCP
// Add new plugins as sibling modules: create <plugin>/build.gradle.kts + src, then include it here.
include(":common", ":xlsx-editor", ":log-viewer", ":codemap")

// The rdgen model for codemap's frontend↔backend protocol. Its own module because it is compiled by a
// plain Kotlin toolchain — inside :codemap the Compose compiler plugin would apply to it too, and a
// protocol definition has no business needing a Compose runtime to build.
include(":codemap:protocol")

// No foojay toolchain resolver: this machine has no standalone JDK on PATH, so the build is run
// with JAVA_HOME pointing at a JDK 21 (e.g. a JetBrains Runtime), which Gradle auto-detects as the
// toolchain. Add the foojay-resolver-convention plugin here to let Gradle auto-download JDK 21.
