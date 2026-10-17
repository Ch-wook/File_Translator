plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "kr.documentstudio"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.documentstudio"
        minSdk = 29
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = 6
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    val keystore = System.getenv("STUDIO_KEYSTORE")
    if (!keystore.isNullOrBlank()) {
        val releaseSigning = signingConfigs.create("studioRelease") {
            storeFile = file(keystore)
            storePassword = requireNotNull(System.getenv("STUDIO_STORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("STUDIO_KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("STUDIO_KEY_PASSWORD"))
        }
        buildTypes.getByName("release").signingConfig = releaseSigning
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/versions/**", "META-INF/services/javax.xml.stream.*"); jniLibs.keepDebugSymbols += "**/*.so" }
}
dependencies {
    androidTestImplementation("kr.dogfoot:hwplib:1.1.10") { exclude(group="org.apache.poi") }
    implementation(project(":officeruntime"))
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
        exclude(group = "commons-io")
    }
    implementation("commons-io:commons-io:2.20.0")
    implementation("org.commonmark:commonmark:0.25.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.25.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
