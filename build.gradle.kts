plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "piper"
version = "1.2.1"

// Target platform: IntelliJ IDEA 2023.1 (build 231). Compiling against it lets the compiler
// prove that every API exists in 2023.1, so since-build=231 is a verified fact, not a guess.
// Change the version in gradle.properties.
val platformVersion: String by project
// Path to the locally installed IDE, used only by verifyPlugin to check newer versions.
val localIdePath: String by project

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Community is enough as the compile SDK (it also bundles the Java plugin /
        // com.intellij.java) and is much smaller than Ultimate. The resulting plugin
        // works in Ultimate as well.
        intellijIdeaCommunity(platformVersion)

        // Java PSI / JavaPsiFacade / ClassImplementationsSearch all live in this bundled plugin.
        bundledPlugin("com.intellij.java")
    }
}

java {
    // Target bytecode 1.8: loadable by JBR 8 / 11 / 17 / 21.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Use --release instead of -source/-target so JDK APIs newer than 8 are rejected too.
    options.release.set(8)
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "231"
            // Leave untilBuild unset so the plugin is not pinned to a specific minor version.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            local(file(localIdePath))
        }
    }

    // Publish to JetBrains Marketplace: ./gradlew publishPlugin
    // The token is read from the environment only, never committed to the repository
    // or to gradle.properties.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN").orElse("")
    }

    // Signing (optional). Enabled only when both environment variables are present;
    // otherwise signPlugin is skipped and a regular buildPlugin is unaffected.
    signing {
        val chain = providers.environmentVariable("CERTIFICATE_CHAIN").orNull
        val key = providers.environmentVariable("PRIVATE_KEY").orNull
        if (!chain.isNullOrBlank() && !key.isNullOrBlank()) {
            certificateChain = chain
            privateKey = key
            password = providers.environmentVariable("PRIVATE_KEY_PASSWORD").orElse("")
        }
    }
}
