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
        bundledPlugin("Pythonid")

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
}


// Enable Gradle’s Run IDE task
tasks {
// JetBrains plugin verifier (optional, but recommended if you plan to publish)
// runPluginVerifier { }
}