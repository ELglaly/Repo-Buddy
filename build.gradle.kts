plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.elglaly"
version = "1.0.1"
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
    from(project(":agent").tasks.named<Jar>("jar")) {
        into("agent")
        rename { "repoBuddy-agent.jar" }
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")

    intellijPlatform {
        create("IC", "2025.1")
        bundledPlugin("com.intellij.java")
    }
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

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("253.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}