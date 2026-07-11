// Shared library used by the plugin modules: POI helpers + shared grid/vim UI infrastructure.
// Applies `org.jetbrains.intellij.platform.module` (no plugin.xml, no ZIP of its own) so shared
// code can compile against platform classes (JBTable, DumbAwareAction, …); the consuming plugins
// bundle these classes via `implementation(project(":common"))`.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

group = "com.example.xlsx"
version = "0.1.0"

repositories {
    mavenCentral()

    // Required by the IntelliJ Platform Gradle Plugin to resolve the IDE distribution.
    intellijPlatform {
        defaultRepositories()
    }
}

// When `riderLocalPath` is set in gradle.properties the build compiles against a locally
// installed Rider (skips the ~1.5 GB download). Blank it out to download Rider 2026.1.3.
val riderLocalPath: String? = providers.gradleProperty("riderLocalPath").orNull

dependencies {
    intellijPlatform {
        if (riderLocalPath.isNullOrBlank()) {
            rider("2026.1.3")
        } else {
            local(riderLocalPath)
        }
    }

    // The repo disables the auto-added Kotlin stdlib (the IDE provides it for plugin modules), so
    // this plain library needs it on the compile classpath only — the host plugin/IDE has it at runtime.
    compileOnly(kotlin("stdlib"))

    // POI types are referenced by the shared formatting/classloader helpers, but POI itself is
    // bundled by the consuming plugin (which has it on its runtime classpath) — so compileOnly here.
    compileOnly(libs.poi.ooxml)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Headless unit tests for the vim state machine (plain Swing JTable, no IDE runtime). The
    // repo-wide `kotlin.stdlib.default.dependency=false` also applies to tests, so add it back here.
    testImplementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
