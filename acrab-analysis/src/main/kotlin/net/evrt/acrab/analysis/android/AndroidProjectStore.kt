package net.evrt.acrab.analysis.android

import net.evrt.acrab.analysis.index.ProjectRecord
import net.evrt.acrab.analysis.index.ProjectRepository
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

internal data class AndroidArtifact(val relativePath: String)

/** Adapts Android outputs to Acrab's verified project store without introducing a database. */
internal class AndroidProjectStore(private val projects: ProjectRepository) {
    fun getProject(projectId: String): ProjectRecord = projects.requireVerified(projectId)

    fun projectRoot(projectId: String): Path {
        projects.require(projectId)
        require(projectId.matches(Regex("p-[a-f0-9]{32}"))) { "Invalid Android project id." }
        var directory = projects.workspaceRoot
        for (part in listOf("android", projectId)) {
            directory = directory.resolve(part)
            try { Files.createDirectory(directory) } catch (_: FileAlreadyExistsException) { }
            require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Android workspace must not contain symbolic links." }
        }
        return directory
    }

    fun createArtifact(projectId: String, relativePath: String, tool: String, bytes: ByteArray): AndroidArtifact {
        getProject(projectId)
        require(tool.startsWith("android_")) { "Invalid Android tool." }
        require(relativePath.matches(Regex("artifacts/[a-zA-Z0-9._-]+"))) { "Invalid Android artifact path." }
        val root = projectRoot(projectId)
        val artifacts = root.resolve("artifacts")
        try { Files.createDirectory(artifacts) } catch (_: FileAlreadyExistsException) { }
        require(Files.isDirectory(artifacts, NOFOLLOW_LINKS)) { "Artifact directory must not be a symbolic link." }
        Files.write(root.resolve(relativePath), bytes, CREATE_NEW, WRITE)
        return AndroidArtifact(relativePath)
    }

    fun importArtifact(projectId: String, artifactPath: String, expectedSha256: String?): ProjectRecord {
        getProject(projectId)
        val artifacts = projectRoot(projectId).resolve("artifacts")
        val path = Path.of(artifactPath).toAbsolutePath().normalize()
        require(path.parent == artifacts && Files.isDirectory(artifacts, NOFOLLOW_LINKS)) {
            "artifactPath must name an artifact belonging to this Android project."
        }
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().parent == artifacts.toRealPath()) {
            "Artifact must be a regular file without symbolic links."
        }
        require(path.fileName.toString().substringAfterLast('.').lowercase() in setOf("jar", "dex", "apk", "aab", "zip")) {
            "Only derived JAR/DEX/APK/AAB/ZIP artifacts can become analysis projects."
        }
        val maxBytes = 128 * 1024 * 1024
        require(Files.size(path) in 1..maxBytes.toLong()) { "Artifact exceeds the import budget or is empty." }
        val bytes = Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
        require(bytes.size <= maxBytes) { "Artifact exceeds the import budget." }
        if (expectedSha256 != null) {
            require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "expectedSha256 must contain 64 hexadecimal characters." }
            val observed = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(observed.equals(expectedSha256, ignoreCase = true)) { "Artifact checksum does not match expectedSha256." }
        }
        return projects.createFromBytes(path.fileName.toString(), bytes)
    }
}
