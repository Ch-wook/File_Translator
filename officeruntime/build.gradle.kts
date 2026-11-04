plugins { id("com.android.library") }
val runtimeDir = rootProject.layout.projectDirectory.dir(".engine-cache/runtime/main")
val prepareEngine by tasks.registering(Exec::class) {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase=true)) {
        commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            rootProject.file("scripts/prepare-office-engine.ps1").absolutePath)
    } else {
        commandLine("python3", rootProject.file("scripts/prepare-office-engine.py").absolutePath)
    }
    inputs.file(rootProject.file("scripts/prepare-office-engine.ps1"))
    inputs.file(rootProject.file("scripts/prepare-office-engine.py"))
    outputs.dir(runtimeDir)
}
android {
    namespace = "org.libreoffice.androidlib"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 29
        resValue("string", "app_name", "Document Studio")
        buildConfigField("String", "GIT_COMMIT", "\"cf7b84af545e7112ee7ea69d7ae1ccbd2b38e4e9\"")
        buildConfigField("boolean", "GOOGLE_PLAY_ENABLED", "false")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main") {
        assets.srcDir(runtimeDir.dir("assets"))
        assets.srcDir(rootProject.file("shared/editor-ui"))
        assets.srcDir(rootProject.file(".engine-cache/studio-fonts/assets"))
        jniLibs.srcDir(runtimeDir.dir("jniLibs"))
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs.keepDebugSymbols += "**/*.so" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { disable += "ExpiredTargetSdkVersion" }
}
val prepareStudioFonts by tasks.registering(Copy::class) {
    dependsOn(prepareEngine,rootProject.tasks.named("prepareStudioFonts"))
    from(rootProject.file("app/src/main/assets/fonts/NotoSansKR.ttf"))
    from(rootProject.file(".engine-cache/studio-fonts/assets/fonts"))
    into(runtimeDir.dir("assets/unpack/user/fonts"))
}
tasks.named("preBuild") { dependsOn(prepareStudioFonts) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("r8:keepanno-annotations:9.3.16")
    api("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}
