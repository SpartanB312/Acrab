plugins {
    application
}

apply(from = rootProject.file("gradle/android-tools.gradle.kts"))

application {
    mainClass.set("net.evrt.acrab.app.MainKt")
    applicationName = "acrab"
}

val generatedToolCatalog = layout.buildDirectory.file("generated/tool-catalog/tools-catalog.json")
val acrabManualDocs = rootProject.layout.projectDirectory.dir("docs/acrab-manual")
val packagedManualDocs = listOf(
    "README.md",
    "USER-QUICKSTART.md",
    "TOOL-REFERENCE.md",
    "SPECIALIST-WORKFLOWS.md",
    "METHOD-PATCHING.md",
    "ANALYSIS-AND-VERIFICATION.md",
    "ANDROID.md",
)
val acrabSkills = rootProject.layout.projectDirectory.dir("skills/acrab")
val packagedSkillFiles = listOf(
    "SKILL.md",
    "workflows/**",
    "references/**",
    "specialists/bytecode-analyst/**",
    "specialists/bytecode-patcher/**",
    "specialists/decompiler/**",
    "specialists/evidence-finder/**",
    "specialists/jvm-indexer/**",
    "specialists/android/**",
    "agents/openai.yaml",
    "agents/acrab-bytecode-analyst.toml",
    "agents/acrab-bytecode-patcher.toml",
    "agents/acrab-decompiler.toml",
    "agents/acrab-evidence-finder.toml",
    "agents/acrab-jvm-indexer.toml",
    "agents/acrab-android.toml",
)

val generateToolCatalog by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Registers all Acrab tools and writes a machine-readable catalog snapshot."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.evrt.acrab.app.ToolCatalogGenerator")
    argumentProviders.add {
        listOf(generatedToolCatalog.get().asFile.absolutePath)
    }
    outputs.file(generatedToolCatalog)
}

distributions {
    main {
        contents {
            from(rootProject.layout.projectDirectory) {
                include("LICENSE")
            }
            from(generatedToolCatalog) {
                into("catalog")
            }
            from(acrabManualDocs) {
                into("docs/acrab-manual")
                include(packagedManualDocs)
            }
            from(rootProject.layout.projectDirectory.dir("licenses")) {
                into("licenses")
                include("AOSP-Apache-2.0.txt", "AOSP-NOTICE.txt")
            }
            from(layout.buildDirectory.dir("android-tools")) {
                into("tools/android")
            }
            from(acrabSkills) {
                into("skills/acrab")
                include(packagedSkillFiles)
            }
        }
    }
}

dependencies {
    implementation(project(":acrab-analysis"))
    implementation(project(":acrab-mcp"))
    runtimeOnly(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.asm)
}

tasks.matching { it.name in setOf("distZip", "distTar", "installDist") }.configureEach {
    dependsOn(generateToolCatalog, "prepareAndroidTools")
}

