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
