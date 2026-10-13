package net.evrt.acrab.analysis.decompiler

import org.jetbrains.java.decompiler.main.extern.IResultSaver
import java.util.jar.Manifest

class VineflowerSinkFactory(
    private val warnings: MutableList<String>,
    private val failedRegions: MutableList<String>,
) : IResultSaver {
    val sources = linkedMapOf<String, String>()

    fun sourceFor(internalName: String): String? = sources[internalName]
        ?: sources[internalName.replace('/', '.')]

    override fun saveFolder(path: String) = Unit
    override fun copyFile(source: String, path: String, entryName: String) = Unit
    override fun saveDirEntry(path: String, archiveName: String, entryName: String) = Unit
    override fun copyEntry(source: String, path: String, archiveName: String, entry: String) = Unit
    override fun createArchive(path: String, archiveName: String, manifest: Manifest?) = Unit
    override fun closeArchive(path: String, archiveName: String) = Unit

    override fun saveClassFile(
        path: String,
        qualifiedName: String,
        entryName: String,
        content: String,
        mapping: IntArray?,
    ) {
        sources[qualifiedName] = content
    }

    override fun saveClassEntry(
        path: String,
        archiveName: String,
        qualifiedName: String,
        entryName: String,
        content: String,
    ) {
        sources[qualifiedName] = content
    }
}
