import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "com.cloudstream.desktop"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":library"))

    // JavaFX for WebView and media
    val javafxVersion = "21.0.5"
    val javafxPlatform = when {
        System.getProperty("os.name").startsWith("Mac") ->
            if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
        System.getProperty("os.name").startsWith("Win") -> "win"
        else -> "linux"
    }
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach { mod ->
        implementation("org.openjfx:javafx-$mod:$javafxVersion:$javafxPlatform")
    }

    // Do not add coroutines-javafx here: it installs a JavaFX Dispatchers.Main,
    // while Compose Desktop owns its lifecycle on the AWT event thread.
    implementation(libs.kotlinx.coroutines.core)

    // Jackson (matches library)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")

    // Ktor, JSoup, Rhino (needed by library on JVM)
    implementation("io.ktor:ktor-http:3.5.0")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("org.mozilla:rhino:1.8.1")

    // NiceHttp (matches library)
    implementation("com.github.Blatzar:NiceHttp:0.4.18")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-Xannotation-default-target=param-property"
        )
    }
}

compose.desktop {
    application {
        mainClass = "com.cloudstream.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb
            )
            packageName = "CloudStreamDesktop"
            packageVersion = "1.0.0"
            description = "CloudStream for Desktop"
            vendor = "CloudStream Community"

            windows {
                menuGroup = "CloudStream"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                dirChooser = true
                perUserInstall = true
            }

            linux {
                menuGroup = "CloudStream"
                debMaintainer = "Skywave22@users.noreply.github.com"
            }

            macOS {
                bundleID = "com.cloudstream.desktop"
            }
        }
    }
}

// jpackage cannot inspect JavaFX native libraries embedded inside dependency JARs.
// Patch the generated control metadata so a fresh Linux system installs GTK/ALSA.
val patchDebDependencies by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Adds JavaFX native dependencies to the generated Debian package"
    val script = rootProject.layout.projectDirectory.file("scripts/patch-deb-dependencies.sh")
    val debDirectory = layout.buildDirectory.dir("compose/binaries/main/deb")
    inputs.file(script)
    commandLine("bash", script.asFile.absolutePath, debDirectory.get().asFile.absolutePath)
    onlyIf("Linux host") { System.getProperty("os.name").startsWith("Linux", ignoreCase = true) }
}

tasks.matching { it.name == "packageDeb" }.configureEach {
    finalizedBy(patchDebDependencies)
}
