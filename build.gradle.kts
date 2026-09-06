plugins {
    java
}

group = "paperlab"
version = "1.0.5"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// We compile against jars built from our own fork.
//
// Why not the published paper-api: the tools need server internals -
// ServerPlayer.mobCounts, ChunkMap.getMobCountNear, Moonrise's ChunkHolderManager.
// paper-api does not expose those by design. Paper 26.2 runs on mojang mappings, so a
// plugin compiled against the server jar loads without remapping.
//
// The side effect: the plugin is tied to a specific server build. Acceptable for a lab,
// not for distribution.
val forkLibs = if (file("../paperlab-core").exists()) file("../paperlab-core") else file("../../fork/paper-lab")

dependencies {
    // The published paper-api, for the transitive adventure, brigadier, netty and annotations.
    // Pinned rather than floating "26.2.build.+": build 92 is the one in the research archive
    // and matches the era of our fork (commit 0a99345).
    compileOnly("io.papermc.paper:paper-api:26.2.build.92-stable")
    // NMS comes from the local fork build: the published paper-api does not carry it.
    compileOnly(files("$forkLibs/paper-server/build/libs/paper-server-26.2.local-SNAPSHOT.jar"))

    // Libraries absent from paper-api but needed to work with NMS: netty for protocol buffers,
    // DFU and jspecify for annotations in NMS class signatures.
    // Versions are pinned: floating ranges have no place in a build under test.
    compileOnly("io.netty:netty-buffer:4.2.2.Final")
    compileOnly("com.mojang:datafixerupper:9.0.19")
    compileOnly("org.jspecify:jspecify:1.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.2.build.92-stable")
    testImplementation(files("$forkLibs/paper-server/build/libs/paper-server-26.2.local-SNAPSHOT.jar"))
    testImplementation("io.netty:netty-buffer:4.2.2.Final")
    testImplementation("io.netty:netty-codec:4.2.2.Final")
    testImplementation("com.mojang:datafixerupper:9.0.19")
    testImplementation("org.jspecify:jspecify:1.0.0")
    testImplementation("ca.spottedleaf:concurrentutil:0.0.3")
    testImplementation("ca.spottedleaf:leafpile:1.0.0")
    testImplementation("com.mojang:authlib:7.0.63")
    testImplementation("it.unimi.dsi:fastutil:8.5.15")
    testImplementation("io.leangen.geantyref:geantyref:1.3.15")
    testImplementation("net.kyori:adventure-text-serializer-ansi:5.2.0")
    testImplementation("net.fabricmc:fabric-loader:0.16.10")
    testImplementation(fileTree("libs") { include("*.jar") })
}

// The differential tests check our encoding against the real code of the client mods:
// malilib/MiniHUD (jars) and g4mespeed (protocol class sources). Neither is in the repository
// and neither can be - third-party code under its own licence, and g4mespeed's is GPL-2.0,
// incompatible with our GPL-3.0.
//
// Anyone wanting to run them puts the files into libs/ themselves (see README, "Building").
// Without them these tests simply do not compile and are excluded; the rest run as usual.
val vendorJars = fileTree("libs") { include("*.jar") }.files.isNotEmpty()
val vendorSources = file("libs/src")

sourceSets {
    test {
        if (vendorSources.isDirectory) {
            java.srcDir(vendorSources)
        } else {
            java.exclude("paperlab/cplay/protocol/CPlayDifferentialFuzzTest.java")
            java.exclude("paperlab/cplay/protocol/CPlaySequenceDifferentialTest.java")
        }
        if (!vendorJars) {
            java.exclude("paperlab/servux/ServuxHudDifferentialTest.java")
            java.exclude("paperlab/servux/ServuxEntitiesDifferentialTest.java")
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName = "PaperLab"
        archiveClassifier = ""
    }

    // Drop the plugin straight into the lab directory, to save copying it by hand.
    register<Copy>("deploy") {
        dependsOn(jar)
        from(jar)
        into("../../testlab/plugins")
    }
}
