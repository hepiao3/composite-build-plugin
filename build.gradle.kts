import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localAndroidStudioPath = "/Applications/Android Studio Preview.app/Contents"
val isLocalBuild = file(localAndroidStudioPath).exists()

dependencies {
    intellijPlatform {
        // 本地开发用本地 Android Studio；CI 环境自动下载指定版本
        if (isLocalBuild) {
            local(localAndroidStudioPath)
        } else {
            androidStudio(providers.gradleProperty("ciAndroidStudioVersion").get())
        }

        // Required bundled plugins
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.plugins.gradle")
        // 使用 androidStudio() 下载时，org.jetbrains.android 是平台核心组件，无需单独声明
        if (isLocalBuild) {
            bundledPlugin("org.jetbrains.android")
        }

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        name = "Composite Build Manager"
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // 无版本上限，不设置 untilBuild
            // 通过设置一个很大的版本号来覆盖 platformVersion 自动推断的值
            untilBuild.convention(providers.provider { "999.0" })
        }
    }

    signing {
        certificateChainFile = file("chain.crt")
        privateKeyFile = file("private.pem")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10"
    }

    test {
        useJUnitPlatform()
    }

    // Microsoft JDK (ms-*) 在 macOS 上缺少 Packages 目录，导致 instrumentCode 失败
    // 禁用字节码插桩任务，不影响 Kotlin null-safety 编译期检查
    instrumentCode {
        enabled = false
    }
    instrumentTestCode {
        enabled = false
    }
    // 使用本地 Android Studio 作为平台时，沙箱环境不兼容，禁用此任务
    buildSearchableOptions {
        enabled = false
    }
}
