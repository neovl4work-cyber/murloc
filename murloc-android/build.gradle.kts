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
}
