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

        changeNotes = provider {
            val changelog = file("CHANGELOG.md").readText()
            val version = project.version.toString()
            // Extract the section for the current version
            val pattern = Regex("""## \[$version].*?\n(.*?)(?=\n## \[|\n\[Unreleased])""", RegexOption.DOT_MATCHES_ALL)
            val section = pattern.find(changelog)?.groupValues?.get(1)?.trim() ?: return@provider "See CHANGELOG.md"
            // Convert markdown to simple HTML
            section.lines()
                .filter { it.isNotBlank() }
                .joinToString("\n") { line ->
                    when {
                        line.startsWith("### ") -> "<b>${line.removePrefix("### ")}</b>"
                        line.startsWith("- ") -> "<li>${line.removePrefix("- ")}</li>"
                        else -> line
                    }
                }
                .let { "<ul>\n$it\n</ul>" }
        }
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