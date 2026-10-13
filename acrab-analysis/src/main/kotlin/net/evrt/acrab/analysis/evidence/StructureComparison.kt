package net.evrt.acrab.analysis.evidence

import kotlinx.serialization.Serializable
import net.evrt.acrab.analysis.index.JvmIndexService
import org.objectweb.asm.commons.Remapper

@Serializable
data class StructureDifference(val location: String, val source: String?, val output: String?)

@Serializable
data class StructureComparisonResult(
    val sourceProjectId: String,
    val outputProjectId: String,
    val inputSha256: String,
    val outputSha256: String,
    val classNames: Map<String, String>,
    val memberNames: Map<String, String>,
    val differenceCount: Int,
    val truncated: Boolean,
    val differences: List<StructureDifference>,
    val scope: String = "class-and-member-structure",
    val warnings: List<String> = listOf("Matching structure does not establish method-body or behavioral equivalence."),
)

class StructureComparison(private val indexes: JvmIndexService) {
    fun compare(sourceId: String, outputId: String, classNames: Map<String, String>,
        memberNames: Map<String, String>, limit: Int): StructureComparisonResult {
        require(limit in 1..1000) { "limit must be between 1 and 1000." }
        val source = indexes.ensureIndex(sourceId)
        val output = indexes.ensureIndex(outputId)
        val original = source.classes.associateBy { it.name }
        val rebuilt = output.classes.associateBy { it.name }
        require(classNames.keys.all { it in original }) { "classNames contains an unknown source class." }
        val mapped = original.keys.map { classNames[it] ?: it }
        require(mapped.distinct().size == mapped.size) { "classNames must be one-to-one." }
        val memberKeys = source.classes.flatMap { clazz ->
            clazz.methods.map { "${clazz.name}#${it.name}${it.descriptor}" } +
                clazz.fields.map { "${clazz.name}#${it.name}:${it.descriptor}" }
        }.toSet()
        require(memberNames.keys.all { it in memberKeys } && memberNames.values.all { it.isNotBlank() }) {
            "memberNames must map exact source member signatures to nonblank names."
        }
        val remapper = object : Remapper(org.objectweb.asm.Opcodes.ASM9) {
            override fun map(internalName: String): String =
                (classNames[internalName.replace('/', '.')] ?: internalName).replace('.', '/')
        }
        val differences = mutableListOf<StructureDifference>()
        fun compareValue(location: String, before: String?, after: String?) {
            if (before != after) differences += StructureDifference(location, before, after)
        }
        compareValue("manifest.Main-Class", source.summary.manifestMainClass?.let { classNames[it] ?: it },
            output.summary.manifestMainClass)
        for ((name, clazz) in original) {
            val renamed = classNames[name] ?: name
            val target = rebuilt[renamed]
            if (target == null) { compareValue("class:$name", renamed, null); continue }
            compareValue("$name.access", clazz.accessFlags.sorted().joinToString(), target.accessFlags.sorted().joinToString())
            compareValue("$name.super", clazz.superName?.let { classNames[it] ?: it }, target.superName)
            compareValue("$name.interfaces", clazz.interfaces.map { classNames[it] ?: it }.sorted().joinToString(),
                target.interfaces.sorted().joinToString())
            val fields = clazz.fields.associate { field ->
                val key = "$name#${field.name}:${field.descriptor}"
                "${memberNames[key] ?: field.name}:${remapper.mapDesc(field.descriptor)}" to field.accessFlags.sorted().joinToString()
            }
            val targetFields = target.fields.associate { "${it.name}:${it.descriptor}" to it.accessFlags.sorted().joinToString() }
            require(fields.size == clazz.fields.size) { "memberNames creates a field signature collision." }
            for (key in fields.keys + targetFields.keys) compareValue("$name.field:$key", fields[key], targetFields[key])
            val methods = clazz.methods.associate { method ->
                val key = "$name#${method.name}${method.descriptor}"
                val newName = memberNames[key] ?: method.name
                require(!method.name.startsWith('<') || newName == method.name) { "Constructors and initializers cannot be renamed." }
                "$newName${remapper.mapMethodDesc(method.descriptor)}" to method.accessFlags.sorted().joinToString()
            }
            val targetMethods = target.methods.associate { "${it.name}${it.descriptor}" to it.accessFlags.sorted().joinToString() }
            require(methods.size == clazz.methods.size) { "memberNames creates a method signature collision." }
            for (key in methods.keys + targetMethods.keys) compareValue("$name.method:$key", methods[key], targetMethods[key])
        }
        for (extra in rebuilt.keys - mapped.toSet()) compareValue("class:$extra", null, extra)
        return StructureComparisonResult(sourceId, outputId, source.summary.archiveSha256, output.summary.archiveSha256,
            classNames, memberNames, differences.size, differences.size > limit, differences.take(limit))
    }
}
