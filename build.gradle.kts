plugins {
    kotlin("jvm") version "2.1.0"    // was 1.9.x
    id("org.jetbrains.intellij.platform") version "2.7.2" // or newer 2.x is fine
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
// Target the selected IDE + version from gradle.properties

        val type = providers.gradleProperty("platformType").get()
        val ver = providers.gradleProperty("platformVersion").get()
        create(type, ver)


// YAML plugin is a bundled plugin we depend on
        bundledPlugin("org.jetbrains.plugins.yaml")
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
}


// Enable Gradle’s Run IDE task
tasks {
// JetBrains plugin verifier (optional, but recommended if you plan to publish)
// runPluginVerifier { }
}