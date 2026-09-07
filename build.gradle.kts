import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.elglaly"
version = "1.0.7"
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// ── Embed the agent JAR as a resource inside the plugin JAR ──────────────────
// The agent JAR is stored at /agent/repoBuddy-agent.jar inside the plugin JAR.
// AgentRunConfigPatcher extracts it to the system temp directory at runtime,
// so it works regardless of how or where the plugin is installed.
evaluationDependsOn(":agent")

tasks.processResources {
    dependsOn(":agent:jar")
    val agentJar = project(":agent").tasks.named<Jar>("jar")
    inputs.files(agentJar.map { it.outputs.files })
    from(agentJar) {
        into("agent")
        rename { "repoBuddy-agent.jar" }
    }
}

dependencies {
    implementation(project(":repo-buddy-core"))
    implementation(project(":repo-buddy-ipc"))
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")

    intellijPlatform {
        create("IC", "2025.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    patchPluginXml {
        pluginDescription = providers.fileContents(
            layout.projectDirectory.file("src/main/resources/META-INF/description.html")
        ).asText
        changeNotes = providers.fileContents(
            layout.projectDirectory.file("src/main/resources/META-INF/change-notes.html")
        ).asText
        sinceBuild = "232"
    }

    publishPlugin {
        token.set(providers.gradleProperty("publishToken").orElse(System.getenv("PUBLISH_TOKEN") ?: ""))
    }

    named<org.gradle.api.tasks.bundling.Zip>("buildPlugin") {
        archiveFileName.set("repobuddy-plugin-${project.version}.zip")
    }
}

val releaseChecksums by tasks.registering {
    dependsOn("buildPlugin", ":repo-buddy-cli:cliDistZip")
    val output = layout.buildDirectory.file("distributions/SHA256SUMS")
    outputs.file(output)
    doLast {
        val directory = layout.buildDirectory.dir("distributions").get().asFile
        val artifacts = listOf(
            directory.resolve("repobuddy-plugin-${project.version}.zip"),
            directory.resolve("repobuddy-cli-${project.version}.zip")
        )
        val lines = artifacts.map { artifact ->
            check(artifact.isFile) { "Release artifact is missing: $artifact" }
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
                .joinToString("") { "%02x".format(it) }
            "$digest  ${artifact.name}"
        }
        output.get().asFile.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    }
}

tasks.register("assembleRelease") {
    dependsOn(releaseChecksums)
}

tasks.register("verifyRelease") {
    dependsOn(releaseChecksums, ":repo-buddy-cli:verifyCliDistribution")
    doLast {
        val directory = layout.buildDirectory.dir("distributions").get().asFile
        val pluginDistribution = directory.resolve("repobuddy-plugin-${project.version}.zip")
        check(pluginDistribution.isFile) { "Plugin distribution is missing" }
        check(directory.resolve("repobuddy-cli-${project.version}.zip").isFile) { "CLI distribution is missing" }
        check(directory.resolve("SHA256SUMS").isFile) { "Release checksums are missing" }
        ZipFile(pluginDistribution).use { outer ->
            val pluginJar = outer.entries().asSequence().firstOrNull {
                it.name.endsWith("/lib/RepoBuddy-${project.version}.jar")
            } ?: error("Plugin JAR is missing from plugin distribution")
            val nested = outer.getInputStream(pluginJar).readBytes()
            var agentFound = false
            var metadataFound = false
            ZipInputStream(ByteArrayInputStream(nested)).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    if (entry.name == "agent/repoBuddy-agent.jar") agentFound = true
                    if (entry.name == "META-INF/plugin.xml") metadataFound = true
                    entry = input.nextEntry
                }
            }
            check(agentFound) { "Embedded RepoBuddy Java agent is missing" }
            check(metadataFound) { "Plugin metadata is missing" }
        }
    }
}
