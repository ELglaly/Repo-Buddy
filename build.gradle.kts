plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.elglaly"
version = "1.0.6"
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
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")

    intellijPlatform {
        create("IC", "2025.1")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            // IntelliJ IDEA Community is published separately only through 2025.2 (252).
            // 2025.3+ ships the unified distribution, whose reorganized layout the current
            // Plugin Verifier (1.405) can't read, so we verify against the latest IC builds.
            create("IC", "2025.1")
            create("IC", "2025.2")
        }
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
        untilBuild = provider { null }
    }

    publishPlugin {
        token.set(providers.gradleProperty("publishToken").orElse(System.getenv("PUBLISH_TOKEN") ?: ""))
    }
}
