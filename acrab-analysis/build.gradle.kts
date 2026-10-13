plugins {
    id("com.google.protobuf") version "0.10.0"
}

dependencies {
    api(project(":acrab-core"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.bundles.asm)
    implementation(libs.cfr)
    implementation(libs.vineflower)
    implementation(libs.smali.dexlib2)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.java)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobufJava.get()}" }
}

tasks.named<Test>("test") { exclude("**/BundledAndroidConversionTest.class") }

tasks.register<Test>("verifyBundledAndroidTools") {
    group = "verification"
    description = "Runs real bundled Android conversion/assembly with generated inputs, never target execution."
    dependsOn("testClasses", ":acrab-app:prepareAndroidTools")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/BundledAndroidConversionTest.class")
    val tools = project(":acrab-app").layout.buildDirectory.dir("android-tools")
    inputs.dir(tools)
    systemProperty("acrab.bundledAndroidTools", tools.get().asFile.absolutePath)
}
