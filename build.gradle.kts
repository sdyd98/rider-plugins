// Root build of the rider-plugins monorepo. Plugin *versions* are declared once here with
// `apply false`; each module applies them without repeating the version. Per-module config
// (IntelliJ Platform target, dependencies, plugin.xml) lives in each module's own build file.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.intellij.platform") version "2.16.0" apply false
}
