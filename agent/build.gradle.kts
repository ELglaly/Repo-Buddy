plugins {
    java
    `java-library`
}

group = "com.repoinspector"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:3.2.0"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-core")
    compileOnly("org.springframework.data:spring-data-jpa")
    compileOnly("org.hibernate.orm:hibernate-core:6.4.0.Final")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")

    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:3.2.0"))
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")
}

tasks.jar {
    archiveBaseName.set("repoBuddy-agent")
    manifest {
        attributes(
            "Premain-Class"          to "com.repoinspector.agent.AgentPremain",
            "Agent-Class"            to "com.repoinspector.agent.AgentPremain",
            "Can-Redefine-Classes"   to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
}
