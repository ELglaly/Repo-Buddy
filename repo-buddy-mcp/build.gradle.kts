plugins { `java-library` }

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
tasks.test { useJUnitPlatform() }
