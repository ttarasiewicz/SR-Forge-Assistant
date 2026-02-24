plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}


group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()


repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}


kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}


dependencies {
    intellijPlatform {
        val ver = providers.gradleProperty("platformVersion").get()
        pycharm(ver) { useInstaller = false }

        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("PythonCore")

        javaCompiler()
    }
}


intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set(providers.gradleProperty("pluginSinceBuild").get()) // e.g., 233
            // DO NOT call untilBuild.set(...)
        }
        name = providers.gradleProperty("pluginName").get()
    }
    buildSearchableOptions = false

    pluginVerification {
        ides {
            // PyCharm Professional
            ide("PY", "2024.2.6")
            ide("PY", "2025.3.3")
            // PyCharm Community (no longer published separately since 2025.3)
            ide("PC", "2024.2.6")
            ide("PC", "2025.2.6")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}