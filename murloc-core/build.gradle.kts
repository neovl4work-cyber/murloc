plugins {
    alias(catalog.plugins.murglar.plugin.core)
}

murglarPlugin {
    id = "local"
    name = "Local Files"
    version = catalog.versions.murloc.map(String::toInt)
    entryPointClass = "com.badmanners.murglar.lib.local.LocalMurglar"
}

dependencies {
    // Embedded HTTP server for streaming local files to the host player (fallback when
    // the host can't play file:// or content:// urls directly).
    implementation(catalog.nanohttpd)
}
