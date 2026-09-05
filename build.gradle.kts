plugins {
    java
}

group = "paperlab"
version = "1.0.4"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Компилируемся против jar'ов, собранных из нашего же форка.
//
// Почему не paper-api из репозитория: инструментам нужны внутренности сервера —
// ServerPlayer.mobCounts, ChunkMap.getMobCountNear, Moonrise ChunkHolderManager.
// В paper-api их нет по определению. Paper 26.2 работает в mojang-маппинге, поэтому
// плагин, скомпилированный против серверного jar, грузится без ремаппинга.
//
// Побочный эффект: плагин привязан к конкретной сборке сервера. Для стенда это
// приемлемо, для распространения — нет.
val forkLibs = if (file("../paperlab-core").exists()) file("../paperlab-core") else file("../../fork/paper-lab")

dependencies {
    // paper-api из репозитория — ради транзитивных adventure, brigadier, netty и аннотаций.
    // Пин, а не floating "26.2.build.+": сборка 92 — та же, что лежит в архиве
    // исследования и соответствует эпохе нашего форка (commit 0a99345).
    compileOnly("io.papermc.paper:paper-api:26.2.build.92-stable")
    // NMS берём из локальной сборки форка: в published paper-api его нет.
    compileOnly(files("$forkLibs/paper-server/build/libs/paper-server-26.2.local-SNAPSHOT.jar"))

    // Библиотеки, которых нет в paper-api, но которые нужны для работы с NMS:
    // netty — буферы протокола, DFU и jspecify — аннотации в сигнатурах NMS-классов.
    // Версии пиним: floating-диапазоны в проверяемой сборке недопустимы.
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

    // Кладём плагин прямо в каталог стенда, чтобы не копировать руками.
    register<Copy>("deploy") {
        dependsOn(jar)
        from(jar)
        into("../../testlab/plugins")
    }
}
