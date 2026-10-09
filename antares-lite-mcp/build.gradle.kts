dependencies {
    api(project(":antares-lite-core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
}
