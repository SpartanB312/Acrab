dependencies {
    api(project(":antares-lite-core"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.bundles.asm)
    implementation(libs.cfr)
    implementation(libs.vineflower)
}
