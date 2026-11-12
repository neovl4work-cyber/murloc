rootProject.name = "murglar-plugin-murloc"

include("murloc-core")

// The android module requires a configured Android SDK (sdk.dir in local.properties
// or ANDROID_HOME/ANDROID_SDK_ROOT env) and a keystore in local.properties,
// so it is included in the build only when the SDK is available.
// To build the apk - copy local.template.properties to local.properties, fix sdk.dir/keystore,
// and this module will be picked up automatically.
val androidSdkAvailable = System.getenv("ANDROID_HOME") != null
    || System.getenv("ANDROID_SDK_ROOT") != null
    || file("local.properties").takeIf(File::exists)?.readText()?.contains("sdk.dir") == true

if (androidSdkAvailable)
    include("murloc-android")

include("murloc-cli")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven { url = java.net.URI.create("https://jitpack.io") }
        mavenLocal()
    }
    resolutionStrategy {
        eachPlugin {
            // workaround for requesting plugins from plain maven repositories with new syntax
            val prefix = "murglar-gradle-plugin-"
            if (requested.id.id.startsWith(prefix)) {
                val artifactId = "${requested.id.id.substringAfter(prefix)}-plugin-gradle-plugin"
                useModule("com.github.badmannersteam.murglar-plugins:$artifactId:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("catalog") {
            version("murglar-plugins", "9.0")
            version("murloc", "12")  // use a single number

            // for core module
            plugin("murglar-plugin-core", "murglar-gradle-plugin-core").versionRef("murglar-plugins")
            // for android module
            plugin("murglar-plugin-android", "murglar-gradle-plugin-android").versionRef("murglar-plugins")

            // for CLI client
            library("murglar-core", "com.github.badmannersteam.murglar-plugins", "core").versionRef("murglar-plugins")

            version("nanohttpd", "2.3.1")
            library("nanohttpd", "org.nanohttpd", "nanohttpd").versionRef("nanohttpd")
        }
    }
}
