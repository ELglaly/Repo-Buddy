pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "RepoBuddy"

include("agent")
include("repo-buddy-core")
include("repo-buddy-ipc")
include("repo-buddy-cli")
include("repo-buddy-mcp")
