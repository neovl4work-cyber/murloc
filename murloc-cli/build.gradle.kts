plugins {
    java
    alias(catalog.plugins.murglar.plugin.core)
}

dependencies {
    implementation(catalog.murglar.core)

    implementation(project(":murloc-core"))
}
