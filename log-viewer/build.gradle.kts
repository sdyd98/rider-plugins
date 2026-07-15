// The Log Viewer plugin. Produces the installable ZIP via `:log-viewer:buildPlugin`.
// A fast, read-only viewer for local and remote (SSH/SFTP) log files: live tail, level/regex
// filtering, custom highlight rules, stack-trace folding, and vim navigation.
plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose") // enables @Composable (Jewel chrome bars)
    id("org.jetbrains.intellij.platform")
}

group = "com.example.logview"
version = "0.5.1"

repositories {
    mavenCentral()

    // Required by the IntelliJ Platform Gradle Plugin to resolve the IDE distribution,
    // bundled modules, the JBR, marketplace plugins, etc.
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

        // Compose + Jewel are BUNDLED with the IDE — reference them for compilation; the IDE provides
        // them at runtime (so they are NOT shipped in the plugin ZIP). Used for the filter/status bars.
        bundledLibrary("lib/intellij.libraries.compose.foundation.desktop.jar")
        bundledLibrary("lib/intellij.libraries.compose.runtime.desktop.jar")
        bundledLibrary("lib/intellij.libraries.skiko.jar")
        bundledLibrary("lib/intellij.platform.compose.jar")
        bundledLibrary("lib/intellij.platform.jewel.foundation.jar")
        bundledLibrary("lib/intellij.platform.jewel.ui.jar")
        bundledLibrary("lib/intellij.platform.jewel.ideLafBridge.jar")

        // Shared helpers (vim table-controller base). Composed into the MAIN plugin jar (not
        // lib/modules/) — VimLogController extends the base class directly, so it must be on the
        // main plugin classloader, not in a plugin-model-v2 content module.
        pluginComposedModule(implementation(project(":common")))
    }

    // JSch (mwiede fork) — bundled into the plugin's lib/ for SSH exec (`tail -F`) + SFTP reads.
    // A single self-contained jar; password / RSA / ECDSA auth needs no BouncyCastle.
    implementation(libs.jsch)

    // Headless unit tests for the parse/append data path (no IDE/Swing UI needed).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // NOTE: to test xlsx-editor + log-viewer in ONE sandbox you can add
    //   intellijPlatform { localPlugin(project(":xlsx-editor")) }
    // here, but that couples this module's build/runIde to xlsx-editor compiling — left out so
    // log-viewer always builds/runs independently of xlsx-editor's (active WIP) state.
}

// Prefer the plugin's own bundled libraries over any older copy the IDE ships
// (JetBrains-recommended snippet for plugin-bundled library precedence).
configurations.all {
    resolutionStrategy.sortArtifacts(ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
}

// Compile against JDK 21 (the Java level required by IntelliJ Platform build 261),
// even though Rider 2026.1 runs on the newer bundled JBR 25.
kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
