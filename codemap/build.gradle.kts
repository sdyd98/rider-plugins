// The Codemap plugin. Produces the installable ZIP via `:codemap:buildPlugin`.
// A read-only tool window that shows the AI-authored understanding note for the C++ file you have
// open (`.codemap/`), plus MCP tools an AI client uses to queue, fetch facts for, and write those
// notes. The plugin itself never interprets code — it only reports facts that are 100% exact.
// rdgen is not on the Gradle plugin portal for this version — only the library is on Maven Central, and
// the version has to track Rider's own rd because both ends of a protocol must agree on the wire format.
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("com.jetbrains.rd:rd-gen:2026.1.3")
        // The generator forks a java process whose classpath comes from here, and the repo's
        // implicit-stdlib-off setting means nothing would otherwise put a Kotlin stdlib on it.
        classpath("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    }
}

apply(plugin = "com.jetbrains.rdgen")

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose") // enables @Composable (Jewel tool-window chrome)
    id("org.jetbrains.intellij.platform")
}

group = "com.example.codemap"
version = "0.5.0"

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
        // them at runtime (so they are NOT shipped in the plugin ZIP). The whole tool window is chrome.
        bundledLibrary("lib/intellij.libraries.compose.foundation.desktop.jar")
        bundledLibrary("lib/intellij.libraries.compose.runtime.desktop.jar")
        bundledLibrary("lib/intellij.libraries.skiko.jar")
        bundledLibrary("lib/intellij.platform.compose.jar")
        bundledLibrary("lib/intellij.platform.jewel.foundation.jar")
        bundledLibrary("lib/intellij.platform.jewel.ui.jar")
        bundledLibrary("lib/intellij.platform.jewel.ideLafBridge.jar")

        // Gson is bundled with the IDE (notes and the pending queue are JSON). IDE provides it at
        // runtime, so it is NOT shipped in the plugin ZIP.
        bundledLibrary("lib/intellij.libraries.gson.jar")

        // The IDE's integrated MCP server (2025.2+). We contribute the note-authoring tools to it via
        // its mcpToolset extension point, so they ship with the plugin (optional dependency).
        bundledPlugin("com.intellij.mcpServer")

        // The shared graph canvas lives in :common. Composed into this plugin's MAIN jar (not
        // lib/modules/) because plugin classes call it directly — see CLAUDE.md on pluginComposedModule.
        pluginComposedModule(implementation(project(":common")))
    }

    // Headless unit tests for the pure logic (path mapping, .h/.cpp pairing, #include extraction,
    // staleness). The repo-wide `kotlin.stdlib.default.dependency=false` also applies to tests.
    testImplementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    // Settings-search index for the 코드맵 settings page. Off because building it LAUNCHES a headless IDE,
    // which fails outright while a sandbox is running ("Only one instance of Rider can be run at a time") —
    // so `buildPlugin` would depend on nobody having runIde open. The page is still reachable the normal way
    // (Settings | Tools | 코드맵); only typing its field names into the settings search box goes unindexed.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // No upper bound (JetBrains' recommendation): the plugin uses only stable core APIs, so a
            // 261.* cap would needlessly block install on later IDEs and force a re-release each time.
            untilBuild = provider { null }
        }
    }
}

// ── 프로토콜 (rdgen) ─────────────────────────────────────────────────────────────────────────────
// One model definition, two generated halves: Kotlin for the plugin and C# for the backend. They have
// to be generated together or the two ends disagree about the wire, which is exactly the class of bug
// hand-written RPC produces.
//
// rider-model.jar is not in an installed Rider — only in the SDK archive — so it is fetched once by
// resharper/tools/fetch-rider-model.sh. Without it this task fails with a message saying so.
val riderModelJar = layout.projectDirectory.file("resharper/lib/rider-model.jar")
val generatedKotlin = layout.buildDirectory.dir("generated/rdgen/kotlin")
val generatedCSharp = layout.projectDirectory.dir("resharper/src/Codemap.Backend/Model")

// The model is Kotlin that has to be *compiled* before rdgen can read it — the generator scans classes,
// not sources. :codemap:protocol produces those classes.
val protocolClasses = project(":codemap:protocol").layout.buildDirectory.dir("classes/kotlin/main")

val rdGenTool: Configuration by configurations.creating
dependencies {
    // rdgen runs as a plain java process outside the IDE, so it needs a real Kotlin stdlib on its
    // classpath — the repo's implicit-stdlib-off setting is about what the plugin ships, not this.
    rdGenTool("com.jetbrains.rd:rd-gen:2026.1.3")
    rdGenTool("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

configure<com.jetbrains.rd.generator.gradle.RdGenExtension> {
    verbose = false
    packages = "model"

    generator {
        language = "kotlin"
        transform = "asis"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        namespace = "com.example.codemap.protocol"
        directory = generatedKotlin.get().asFile.absolutePath
    }
    generator {
        language = "csharp"
        transform = "reversed"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        namespace = "Codemap.Backend.Protocol"
        directory = generatedCSharp.asFile.absolutePath
    }
}

tasks.named<JavaExec>("rdgen") {
    dependsOn(":codemap:protocol:classes")
    // Everything goes on the JVM classpath, including what the generator scans: this rd-gen's CLI has
    // no classpath option, and the extension's `classpath` would pass one it rejects.
    classpath = files(rdGenTool, protocolClasses, riderModelJar)
    doFirst {
        if (!riderModelJar.asFile.exists()) {
            throw GradleException(
                "rider-model.jar 이 없습니다. 한 번만 받으면 됩니다:\n" +
                    "  ./codemap/resharper/tools/fetch-rider-model.sh",
            )
        }
    }
}

sourceSets.main { kotlin.srcDir(generatedKotlin) }
tasks.named("compileKotlin") { dependsOn("rdgen") }

// ── ReSharper backend ────────────────────────────────────────────────────────────────────────────
// C++ semantics live in Rider's .NET backend and nowhere the JVM side can reach. `resharper/` is the
// half that runs there; it ships as a plain DLL under the plugin's `dotnet/` directory, which is where
// the backend looks for a plugin's own assemblies.
//
// Built with the SDK Rider itself bundles rather than a system dotnet: the C++ API is not in the
// published ReSharper SDK, so this compiles against the installed Rider's assemblies anyway — and
// depending on the same installation for the compiler removes a second thing to get right.
val riderDotnet = riderLocalPath?.let { "$it/Contents/lib/ReSharperHost/macos-arm64/dotnet/dotnet" }
val backendProject = layout.projectDirectory.dir("resharper/src/Codemap.Backend")
val backendDll = backendProject.file("bin/Release/net10.0/Codemap.Backend.dll")

val buildBackend by tasks.registering(Exec::class) {
    description = "Compiles the ReSharper-side assembly with Rider's bundled .NET SDK."
    onlyIf { riderDotnet != null && file(riderDotnet).exists() }
    workingDir = backendProject.asFile
    commandLine(riderDotnet ?: "dotnet", "build", "-c", "Release", "--nologo", "-v", "q")
    environment("DOTNET_CLI_TELEMETRY_OPTOUT", "1")
    inputs.files(fileTree(backendProject) { include("**/*.cs", "**/*.csproj") })
    outputs.file(backendDll)
}

tasks.prepareSandbox {
    dependsOn(buildBackend)
    from(backendDll) { into("${project.name}/dotnet") }
}

// Dev convenience: open a project straight away in the sandbox IDE, so a manual check of the tool
// window doesn't start with a file-chooser every time.
//   ./gradlew :codemap:runIde -PrunIdeProject=/path/to/a/cpp/project
tasks.runIde {
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }

    // The first-run wizard blocks project opening until someone clicks through it, which makes an
    // automated check of the sandbox impossible — nothing loads, and the silence looks like a failure
    // of whatever was being tested.
    systemProperty("idea.show.customize.ide.wizard", "false")

    // A GUI-launched IDE does not inherit a login shell's PATH, and neither does this fork reliably.
    // Handing it the usual tool locations is what lets a CMake project actually configure in the
    // sandbox — without it Rider reports "Cannot run program cmake" and never indexes the C++ code.
    environment("PATH", listOf("/opt/homebrew/bin", "/usr/local/bin", System.getenv("PATH")).joinToString(":"))
}

tasks.test {
    useJUnitPlatform()
    // Opt-in cross-check of FileFacts against ground truth on a real C++ git repo (RealRepoFactsTest):
    //   ./gradlew :codemap:test -Dcodemap.testRepo=/path/to/repo
    // Forwarded explicitly — a -D on the Gradle command line reaches the Gradle JVM, not the test JVM.
    systemProperty("codemap.testRepo", providers.systemProperty("codemap.testRepo").getOrElse(""))
    testLogging { showStandardStreams = true }
}
