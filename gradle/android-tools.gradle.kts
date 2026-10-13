import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.file.RelativePath

val smaliTools = configurations.create("androidSmaliTools") {
    isCanBeConsumed = false
}
val d8Tools = configurations.create("androidD8Tools") {
    isCanBeConsumed = false
    isTransitive = false
}
val jasminTools = configurations.create("androidJasminTools") {
    isCanBeConsumed = false
    isTransitive = false
}
val toolSources = configurations.create("androidToolSources") {
    isCanBeConsumed = false
    isTransitive = false
}
val toolPoms = configurations.create("androidToolPoms") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    add(smaliTools.name, "com.android.tools.smali:smali:3.0.10")
    add(smaliTools.name, "com.android.tools.smali:smali-baksmali:3.0.10")
    add(d8Tools.name, "com.android.tools:r8:9.5.23")
    add(jasminTools.name, "ca.mcgill.sable:jasmin:3.0.3:jar-with-dependencies@jar")
    add(toolSources.name, "ca.mcgill.sable:jasmin:3.0.3:sources@jar")
    add(toolSources.name, "ca.mcgill.sable:java_cup:0.9.2:sources@jar")
    for (module in listOf("com.android.tools.smali:smali:3.0.10", "com.android.tools.smali:smali-baksmali:3.0.10",
        "com.android.tools:r8:9.5.23", "ca.mcgill.sable:jasmin:3.0.3", "ca.mcgill.sable:java_cup:0.9.2")) {
        add(toolPoms.name, "$module@pom")
    }
}

fun checksum(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

val dex2jarArchive = layout.buildDirectory.file("downloads/dex-tools-2.4.38.zip")
val dex2jarSha256 = "5d88e269bd120852fec68de268f6900f5466601e06ca8c16611f4377c6254e5c"
val downloadDex2jar by tasks.registering {
    inputs.property("url", "https://github.com/ThexXTURBOXx/dex2jar/releases/download/2.4.38/dex-tools-2.4.38.zip")
    inputs.property("sha256", dex2jarSha256)
    outputs.file(dex2jarArchive)
    doLast {
        val target = dex2jarArchive.get().asFile
        if (!target.isFile || checksum(target) != dex2jarSha256) {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val request = HttpRequest.newBuilder(URI.create(inputs.properties.getValue("url").toString()))
                .timeout(java.time.Duration.ofMinutes(5)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            require(response.statusCode() == 200) { "dex2jar download failed: HTTP ${response.statusCode()}" }
            target.parentFile.mkdirs()
            target.writeBytes(response.body())
        }
        require(checksum(target) == dex2jarSha256) { "dex2jar release checksum mismatch." }
    }
}

val bundle = layout.buildDirectory.dir("android-tools")
val prepareAndroidTools by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Packages isolated, pinned Android Java toolchains with checksums and upstream notices."
    dependsOn(downloadDex2jar)
    into(bundle)
    includeEmptyDirs = false
    from(smaliTools) { into("smali/lib") }
    from(d8Tools) { into("d8/lib") }
    from(jasminTools) { into("jasmin/lib") }
    from(toolSources) { into("licenses/sources") }
    from(toolPoms) { into("licenses/poms") }
    from(rootProject.layout.projectDirectory.dir("licenses/android-tools")) { into("licenses") }
    from(provider { zipTree(dex2jarArchive.get().asFile) }) {
        include("dex-tools-2.4.38/lib/*.jar", "dex-tools-2.4.38/lib/open-source-license.txt",
            "dex-tools-2.4.38/LICENSE.txt", "dex-tools-2.4.38/NOTICE.txt")
        eachFile {
            relativePath = if (name.endsWith(".jar")) RelativePath(true, "dex2jar", "lib", name)
                else RelativePath(true, "licenses", "dex2jar", name)
        }
    }
    inputs.property("toolchainFormat", 1)
    doFirst {
        require(checksum(dex2jarArchive.get().asFile) == dex2jarSha256) { "dex2jar archive checksum mismatch." }
        val pins = mapOf(
            "smali-3.0.10.jar" to "b54c34f7a1b769b1e637f8a26909c68f399c273f3e5cde3e308e91107c135305",
            "smali-baksmali-3.0.10.jar" to "7d6578dfe5c44f1984b64268e91834432b0942d49e08186a501039dd92b58213",
            "r8-9.5.23.jar" to "748ab0ef66e85b54cec5ad83d168999db9e3dad8c8f63170e836eff195cbd09b",
            "jasmin-3.0.3-jar-with-dependencies.jar" to "78e69b6324cb16c0a6875a057b9a03f52a7b239009a027dca1bda16010fd020b",
        )
        val artifacts = (smaliTools.files + d8Tools.files + jasminTools.files).associateBy(File::getName)
        pins.forEach { (name, sha256) -> require(artifacts[name]?.let(::checksum) == sha256) { "Tool checksum mismatch: $name" } }
    }
    doLast {
        val root = bundle.get().asFile
        val files = root.walkTopDown().filter { it.isFile && it.extension == "jar" && !it.relativeTo(root).path.startsWith("licenses") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.associate { it.relativeTo(root).invariantSeparatorsPath to checksum(it) }
        fun tool(directory: String, main: String, version: String): Map<String, Any> = mapOf(
            "mainClass" to main, "version" to version,
            "classpath" to files.keys.filter { it.startsWith("$directory/lib/") },
        )
        val tools = linkedMapOf(
            "d2j-dex2jar" to tool("dex2jar", "com.googlecode.dex2jar.tools.Dex2jarCmd", "2.4.38"),
            "smali" to tool("smali", "com.android.tools.smali.smali.Main", "3.0.10"),
            "baksmali" to tool("smali", "com.android.tools.smali.baksmali.Main", "3.0.10"),
            "d8" to tool("d8", "com.android.tools.r8.D8", "9.5.23"),
            "jasmin" to tool("jasmin", "jasmin.Main", "3.0.3"),
        )
        root.resolve("toolchain.json").writeText(groovy.json.JsonOutput.prettyPrint(
            groovy.json.JsonOutput.toJson(mapOf("formatVersion" to 1, "tools" to tools, "sha256" to files))) + "\n")
        // Keep license texts embedded in unchanged upstream JARs accessible without unpacking them.
        files.keys.forEach { relative ->
            val jar = root.resolve(relative)
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.matches(Regex("(?i)(?:META-INF/)?(?:LICENSE|NOTICE|COPYING)(?:[._-].*)?")) }
                    .forEach { entry ->
                        val target = root.resolve("licenses/embedded/${jar.name}/${entry.name.substringAfterLast('/')}")
                        target.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                    }
            }
        }
    }
}
