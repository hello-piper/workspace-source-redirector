plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "piper"
version = "1.1.0"

// 目标平台：IDEA 2023.1（build 231）。对着它编译，API 是否存在于 2023.1 由编译器兜底，
// 这样 since-build=231 才不是猜的。版本号可在 gradle.properties 里调整。
val platformVersion: String by project
// 本机 IDEA 路径，仅用于 verifyPlugin 校验"新版本上是否还能用"
val localIdePath: String by project

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 用 Community 作为编译 SDK 即可（Java 插件/com.intellij.java 在社区版里也有），
        // 体积比 Ultimate 小很多，编出来的插件在 Ultimate 上照样能用。
        intellijIdeaCommunity(platformVersion)

        // Java PSI / JavaPsiFacade / ClassImplementationsSearch 都在这个内置插件里
        bundledPlugin("com.intellij.java")
    }
}

java {
    // 目标字节码 1.8：JBR 8/11/17/21 都能加载
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 用 --release 而不是 -source/-target，顺带把 JDK 8 之后才有的 JDK API 也挡在门外
    options.release.set(8)
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "231"
            // 不写 untilBuild，避免被锁死在某个小版本上
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            local(file(localIdePath))
        }
    }

    // 发布到 JetBrains Marketplace：./gradlew publishPlugin
    // token 只从环境变量读，绝不写进仓库或 gradle.properties
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN").orElse("")
    }

    // 签名（可选）。两个环境变量都齐了才启用，否则 signPlugin 自动跳过，
    // 日常 buildPlugin 不受影响。
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
