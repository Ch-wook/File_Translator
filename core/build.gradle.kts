plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.apache.poi:poi:5.4.1")
    implementation("org.apache.poi:poi-scratchpad:5.4.1")
    implementation("kr.dogfoot:hwplib:1.1.10") { exclude(group = "org.apache.poi") }
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
