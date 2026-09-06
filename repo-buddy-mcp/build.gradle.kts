plugins {
    application
}

group = "com.repoinspector"
version = rootProject.version

repositories { mavenCentral() }

dependencies {
    implementation(project(":repo-buddy-core"))
    implementation(project(":repo-buddy-ipc"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<JavaCompile> { sourceCompatibility = "17"; targetCompatibility = "17"; options.encoding = "UTF-8" }
application { mainClass.set("com.repoinspector.mcp.RepoBuddyMcpServer") }
tasks.test { useJUnitPlatform() }

tasks.jar {
    dependsOn(":repo-buddy-core:jar", ":repo-buddy-ipc:jar")
    archiveFileName.set("repo-buddy-mcp.jar")
    manifest.attributes["Main-Class"] = application.mainClass.get()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
