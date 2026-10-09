plugins {
    application
}

application {
    mainClass.set("net.evrt.antarelite.app.MainKt")
    applicationName = "antares-lite"
}

val generatedToolCatalog = layout.buildDirectory.file("generated/tool-catalog/tools-catalog.json")
val antaresLiteManualDocs = rootProject.layout.projectDirectory.dir("docs/antares-lite-manual")
val packagedManualDocs = listOf(
    "README.md",
    "USER-QUICKSTART.md",
    "TOOL-REFERENCE.md",
    "SPECIALIST-WORKFLOWS.md",
    "METHOD-PATCHING.md",
)
val antaresLiteSkills = rootProject.layout.projectDirectory.dir("skills/antares-lite")
val packagedSkillFiles = listOf(
    "SKILL.md",
    "workflows/**",
    "specialists/bytecode-analyst/**",
    "specialists/bytecode-patcher/**",
    "specialists/decompiler/**",
    "specialists/evidence-finder/**",
    "specialists/jvm-indexer/**",
    "agents/openai.yaml",
    "agents/antares-lite-bytecode-analyst.toml",
    "agents/antares-lite-bytecode-patcher.toml",
    "agents/antares-lite-decompiler.toml",
    "agents/antares-lite-evidence-finder.toml",
    "agents/antares-lite-jvm-indexer.toml",
)

val generateToolCatalog by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Registers all Lite tools and writes a machine-readable catalog snapshot."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.evrt.antarelite.app.ToolCatalogGenerator")
    argumentProviders.add {
        listOf(generatedToolCatalog.get().asFile.absolutePath)
    }
    outputs.file(generatedToolCatalog)
}

distributions {
    main {
        contents {
            from(generatedToolCatalog) {
                into("catalog")
            }
            from(antaresLiteManualDocs) {
                into("docs/antares-lite-manual")
                include(packagedManualDocs)
            }
            from(antaresLiteSkills) {
                into("skills/antares-lite")
                include(packagedSkillFiles)
            }
        }
    }
}

dependencies {
    implementation(project(":antares-lite-analysis"))
    implementation(project(":antares-lite-mcp"))
    runtimeOnly(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
}

tasks.matching { it.name in setOf("distZip", "distTar", "installDist") }.configureEach {
    dependsOn(generateToolCatalog)
}

