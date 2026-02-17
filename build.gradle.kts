plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.bedrockbridge"
version = "1.0.0-SNAPSHOT"
description = "BedrockBridge - A Bedrock-to-Bedrock proxy, like Geyser but for Bedrock servers"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-snapshots/")
    maven("https://repo.opencollab.dev/maven-releases/")
}

dependencies {
    // CloudburstMC Bedrock Protocol Library (same one Geyser uses)
    implementation("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta6-SNAPSHOT")
    implementation("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta6-SNAPSHOT")

    // Netty (RakNet transport - Bedrock uses RakNet over UDP)
    implementation("org.cloudburstmc.netty:netty-transport-raknet:1.0.0.CR3-SNAPSHOT")
    implementation("io.netty:netty-all:4.1.97.Final")

    // Config (YAML like Geyser uses)
    implementation("org.yaml:snakeyaml:2.2")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Lombok for boilerplate reduction
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("BedrockBridge.jar")
    manifest {
        attributes["Main-Class"] = "dev.bedrockbridge.bootstrap.BedrockBridgeMain"
        attributes["Multi-Release"] = "true"
    }
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
