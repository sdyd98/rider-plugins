// The rdgen model for codemap's frontend↔backend protocol.
//
// Nothing here ships. rdgen reads *compiled* model classes, not sources, so this module exists to produce
// them — and to keep that compilation on a plain Kotlin toolchain, away from the plugin's Compose plugin.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

dependencies {
    implementation("com.jetbrains.rd:rd-gen:2026.1.3")
    // The repo turns off Gradle's implicit Kotlin stdlib (the IDE provides one at runtime); this module
    // is not the IDE, so it asks for one.
    implementation(kotlin("stdlib"))
    // Rider's model definitions — the Solution model this protocol extends. Not in an installed Rider,
    // only in the SDK archive: see resharper/tools/fetch-rider-model.sh.
    implementation(files(rootProject.layout.projectDirectory.file("codemap/resharper/lib/rider-model.jar")))
}

kotlin {
    jvmToolchain(21)
}
