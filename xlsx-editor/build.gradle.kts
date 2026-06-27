// The XLSX Grid Editor plugin. Produces the installable ZIP via `:xlsx-editor:buildPlugin`.
plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose") // enables @Composable (Jewel UI demo)
    id("org.jetbrains.intellij.platform")
}

group = "com.example.xlsx"
version = "0.2.0"

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
        // them at runtime (so they are NOT shipped in the plugin ZIP). Demo for the Compose help UI.
        bundledLibrary("lib/intellij.libraries.compose.foundation.desktop.jar")
        bundledLibrary("lib/intellij.libraries.compose.runtime.desktop.jar")
        bundledLibrary("lib/intellij.libraries.skiko.jar")
        bundledLibrary("lib/intellij.platform.compose.jar")
        bundledLibrary("lib/intellij.platform.jewel.foundation.jar")
        bundledLibrary("lib/intellij.platform.jewel.ui.jar")
        bundledLibrary("lib/intellij.platform.jewel.ideLafBridge.jar")
    }

    // Shared helpers (POI classloader swap, cached-formula formatting). Bundled into the plugin.
    implementation(project(":common"))

    // Apache POI — bundled into the plugin's lib/ so we can read & write .xlsx at runtime.
    // The optional pdfbox/batik/bouncycastle/xmlsec chains are <optional> in POI's POM and
    // are not needed for plain spreadsheet read/write; excluded to keep the bundle small.
    implementation(libs.poi.ooxml) {
        exclude(group = "org.apache.pdfbox")
        exclude(group = "org.apache.xmlgraphics")
        exclude(group = "org.bouncycastle")
        exclude(group = "org.apache.santuario")
    }
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

// Give the sandbox more heap so large (100 MB+) spreadsheets can be load-tested. Dev-only — this
// does NOT affect the shipped plugin; users adjust their own IDE memory (Help → Change Memory Settings).
tasks.runIde {
    maxHeapSize = "4g"
}
