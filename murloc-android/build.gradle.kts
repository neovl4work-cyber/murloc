plugins {
    alias(catalog.plugins.murglar.plugin.android)
}

murglarAndroidPlugin {
    id = "local"
    name = "Local Files"
    version = catalog.versions.murloc.map(String::toInt)
    entryPointClass = "com.badmanners.murglar.lib.local.LocalMurglar"
}

android {
    buildTypes.named("release") {
        proguardFiles += file("proguard-rules.pro")
    }
}

dependencies {
    implementation(project(":murloc-core"))
    // kotlin-stdlib ОБЯЗАТЕЛЕН в apk: activity и provider исполняются в процессе
    // плагина (системный classloader), а не в процессе хоста. murglar-плагин
    // ИСКЛЮЧАЕТ модуль kotlin-stdlib из всех зависимостей — поэтому подключаем
    // FILE-зависимостью (module exclusions на неё не действуют).
    implementation(files("libs/kotlin-stdlib.jar"))
}
