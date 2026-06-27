// Shared library used by the plugin modules. Plain Kotlin (no IntelliJ Platform yet): it only holds
// POI helpers right now. When grid/vim UI code is shared here it will additionally apply
// `org.jetbrains.intellij.platform.module` to compile against the platform.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.example.xlsx"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
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
