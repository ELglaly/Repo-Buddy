plugins {
    application
}

group = "com.repoinspector"
version = rootProject.version

repositories { mavenCentral() }

dependencies {
    implementation(project(":repo-buddy-core"))
    implementation(project(":repo-buddy-ipc"))
    implementation(project(":repo-buddy-mcp"))
    implementation("info.picocli:picocli:4.7.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<JavaCompile> { sourceCompatibility = "17"; targetCompatibility = "17"; options.encoding = "UTF-8" }
application { mainClass.set("com.repoinspector.cli.RepoBuddyCli") }
tasks.test { useJUnitPlatform() }

tasks.jar {
    dependsOn(":repo-buddy-core:jar", ":repo-buddy-ipc:jar", ":repo-buddy-mcp:jar")
    archiveFileName.set("repobuddy.jar")
    manifest.attributes["Main-Class"] = application.mainClass.get()
    manifest.attributes["Implementation-Version"] = project.version.toString()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val generatedVersion = layout.buildDirectory.file("generated/cli-dist/VERSION")

val generateDistributionVersion by tasks.registering {
    outputs.file(generatedVersion)
    doLast {
        val output = generatedVersion.get().asFile
        output.parentFile.mkdirs()
        output.writeText(project.version.toString() + System.lineSeparator())
    }
}

val cliDistZip by tasks.registering(org.gradle.api.tasks.bundling.Zip::class) {
    dependsOn(tasks.jar, generateDistributionVersion)
    archiveBaseName.set("repobuddy-cli")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("src/dist/bin") {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    from("src/dist/README.txt")
    from(generatedVersion)
    from(tasks.jar) { into("lib") }
}

tasks.register("verifyCliDistribution") {
    dependsOn(cliDistZip)
    doLast {
        val archive = cliDistZip.get().archiveFile.get().asFile
        val unpacked = layout.buildDirectory.dir("verification/cli distribution with spaces").get().asFile
        delete(unpacked)
        copy { from(zipTree(archive)); into(unpacked) }
        val jar = unpacked.resolve("lib/repobuddy.jar")
        val unixLauncher = unpacked.resolve("bin/repobuddy")
        val windowsLauncher = unpacked.resolve("bin/repobuddy.bat")
        check(jar.isFile && unixLauncher.isFile && windowsLauncher.isFile) { "CLI distribution is incomplete" }
        val launcherText = unixLauncher.readText() + windowsLauncher.readText()
        check(!launcherText.contains("gradlew") && !launcherText.contains("build/libs")) {
            "End-user launchers must not reference Gradle build outputs"
        }
        providers.exec {
            commandLine(System.getProperty("java.home") + "/bin/java", "-jar", jar.absolutePath, "version")
        }.result.get().assertNormalExitValue()
    }
}
