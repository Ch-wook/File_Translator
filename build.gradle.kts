plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("jvm") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

// Optional separate build disk for the embedded engine's large intermediates.
providers.gradleProperty("studio.buildRoot").orNull?.takeIf {it.isNotBlank()}?.let {directory ->
    val output=rootProject.file(directory)
    allprojects {layout.buildDirectory.set(output.resolve(if(path==":") "root" else path.removePrefix(":").replace(':','/')))}
}
