plugins {
    java
}

group = "paperlab"
version = "1.0.0"

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
val forkLibs = file("../../fork/paper-lab")

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
    compileOnly("com.mojang:datafixerupper:8.0.16")
    compileOnly("org.jspecify:jspecify:1.0.0")
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
