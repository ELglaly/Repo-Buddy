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
version = "1.0.8"
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
evaluationDependsOn(":repo-buddy-cli")

tasks.processResources {
    dependsOn(":agent:jar", ":repo-buddy-cli:cliDistZip")
    val agentJar = project(":agent").tasks.named<Jar>("jar")
    val cliDistribution = project(":repo-buddy-cli").tasks.named<Zip>("cliDistZip")
    inputs.files(agentJar.map { it.outputs.files })
    inputs.files(cliDistribution.map { it.outputs.files })
    from(agentJar) {
        into("agent")
        rename { "repoBuddy-agent.jar" }
    }
    from(cliDistribution.map { it.archiveFile }) {
        into("cli")
        rename { "repobuddy-cli.zip" }
    }
}

dependencies {
    implementation(project(":repo-buddy-core"))
    implementation(project(":repo-buddy-ipc"))
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
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

// Process-level CLI/MCP coverage lives separately from unit and IntelliJ fixture tests.
// It always exercises the packaged distribution, never classes from the Gradle runtime.
val systemTestSourceSet = sourceSets.create("systemTest") {
    java.srcDir("src/systemTest/java")
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations[systemTestSourceSet.implementationConfigurationName].extendsFrom(configurations["testImplementation"])
configurations[systemTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations["testRuntimeOnly"])

fun registerSystemSuite(name: String, includes: List<String>) = tasks.register<Test>(name) {
    group = "verification"
    description = "Runs RepoBuddy packaged $name coverage."
    dependsOn(":repo-buddy-cli:cliDistZip")
    testClassesDirs = systemTestSourceSet.output.classesDirs
    classpath = systemTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    includes.forEach { include(it) }
    shouldRunAfter(tasks.test)
}

val cliSystemTest = registerSystemSuite("cliSystemTest", listOf("**/CliProcessSystemTest.class"))
val mcpSystemTest = registerSystemSuite("mcpSystemTest", listOf("**/McpProcessSystemTest.class"))
val contractTest = registerSystemSuite("contractTest", listOf("**/ContractSystemTest.class"))
val packagingTest = registerSystemSuite("packagingTest", listOf("**/PackagingSystemTest.class"))
val systemTest = tasks.register("systemTest") {
    group = "verification"
    description = "Runs all black-box packaged CLI and MCP system-test suites."
    dependsOn(cliSystemTest, mcpSystemTest, contractTest, packagingTest)
}

tasks.named("check") { dependsOn(systemTest) }

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
    inputs.files(
        layout.buildDirectory.file("distributions/repobuddy-plugin-${project.version}.zip"),
        layout.buildDirectory.file("distributions/repobuddy-cli-${project.version}.zip")
    )
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
        val checksums = directory.resolve("SHA256SUMS")
        check(checksums.isFile) { "Release checksums are missing" }
        val expectedChecksums = listOf(
            directory.resolve("repobuddy-plugin-${project.version}.zip"),
            directory.resolve("repobuddy-cli-${project.version}.zip")
        ).associate { artifact ->
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
                .joinToString("") { "%02x".format(it) }
            artifact.name to digest
        }
        val actualChecksums = checksums.readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                check(parts.size == 2) { "Malformed release checksum line: $line" }
                parts[1] to parts[0]
            }
        check(actualChecksums == expectedChecksums) { "Release checksums do not match generated artifacts" }
        ZipFile(pluginDistribution).use { outer ->
            val pluginJar = outer.entries().asSequence().firstOrNull {
                it.name.endsWith("/lib/RepoBuddy-${project.version}.jar")
            } ?: error("Plugin JAR is missing from plugin distribution")
            val nested = outer.getInputStream(pluginJar).readBytes()
            var agentFound = false
            var metadataFound = false
            var metadata: String? = null
            var cliBytes: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(nested)).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    if (entry.name == "agent/repoBuddy-agent.jar") agentFound = true
                    if (entry.name == "META-INF/plugin.xml") {
                        metadataFound = true
                        metadata = input.bufferedReader().readText()
                    }
                    if (entry.name == "cli/repobuddy-cli.zip") cliBytes = input.readBytes()
                    entry = input.nextEntry
                }
            }
            check(agentFound) { "Embedded RepoBuddy Java agent is missing" }
            check(metadataFound) { "Plugin metadata is missing" }
            check(Regex("<version>\\s*${Regex.escape(project.version.toString())}\\s*</version>").containsMatchIn(metadata!!)) {
                "Plugin metadata version does not match ${project.version}"
            }
            check(cliBytes != null) { "Embedded RepoBuddy CLI distribution is missing" }
            val requiredCliEntries = mutableSetOf(
                "VERSION", "bin/repobuddy", "bin/repobuddy.bat", "lib/repobuddy.jar"
            )
            var embeddedVersion: String? = null
            ZipInputStream(ByteArrayInputStream(cliBytes!!)).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    requiredCliEntries.remove(entry.name)
                    if (entry.name == "VERSION") embeddedVersion = input.bufferedReader().readText().trim()
                    entry = input.nextEntry
                }
            }
            check(requiredCliEntries.isEmpty()) { "Embedded CLI is incomplete: $requiredCliEntries" }
            check(embeddedVersion == project.version.toString()) {
                "Embedded CLI version $embeddedVersion does not match plugin ${project.version}"
            }
        }
    }
}
