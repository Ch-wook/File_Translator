plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
// Maven 1.1.11 predates upstream #315. Keep every other class/resource unchanged;
// compile the exact upstream boundary fix from core/src/main/java instead.
val hwplibOriginal by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareHwplibRuntime by tasks.registering(Jar::class) {
    archiveFileName.set("hwplib-1.1.11-studio.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from({ zipTree(hwplibOriginal.singleFile) }) {
        exclude("kr/dogfoot/hwplib/reader/bodytext/paragraph/control/gso/part/ForCtrlHeaderGso.class", "META-INF/MANIFEST.MF")
    }
}
dependencies {
    implementation("org.apache.poi:poi:5.4.1")
    implementation("org.apache.poi:poi-scratchpad:5.4.1")
    hwplibOriginal("kr.dogfoot:hwplib:1.1.11")
    implementation(files(prepareHwplibRuntime))
    implementation("kr.dogfoot:hwpxlib:1.0.9")
    testImplementation("junit:junit:4.13.2")
    // Desktop parser only in JVM verification; never included in the Android app.
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")
}

listOf("prepareVisualFixtures" to "prepare","verifyVisualRoundTrip" to "verify").forEach {(taskName,mode)->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath=sourceSets.test.get().runtimeClasspath
        mainClass.set("kr.documentstudio.core.VisualFixtureTool")
        args(mode,layout.buildDirectory.dir("visual-fixtures").get().asFile.absolutePath)
    }
}

tasks.register<JavaExec>("verifyMobileRoundTrip") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("kr.documentstudio.core.MobileRoundTripTool")
    args(layout.buildDirectory.dir("visual-fixtures").get().asFile.absolutePath)
}

tasks.register<JavaExec>("inspectTableSave") {
 dependsOn(tasks.testClasses)
 classpath=sourceSets.test.get().runtimeClasspath
 mainClass.set("kr.documentstudio.core.TableSaveDiagnostic")
 args(layout.buildDirectory.dir("visual-fixtures").get().asFile.absolutePath)
}
