package net.evrt.acrab.analysis.index

import kotlinx.serialization.Serializable

@Serializable
data class ProjectRecord(
    val projectId: String,
    val fileName: String,
    val archivePath: String,
    val sha256: String,
    val createdAt: Long,
)

@Serializable
data class JarSummary(
    val projectId: String,
    val archiveSha256: String,
    val classCount: Int,
    val packageCount: Int,
    val methodCount: Int,
    val fieldCount: Int,
    val kotlinClassCount: Int,
    val manifestMainClass: String? = null,
    val topPackages: List<PackageSummary>,
)

@Serializable
data class PackageSummary(
    val name: String,
    val classCount: Int,
)

@Serializable
data class ProjectIndex(
    val summary: JarSummary,
    val classes: List<ClassInfo>,
    val strings: List<StringReference>,
    val invocations: List<InvocationReference>,
    val fieldReferences: List<FieldReference> = emptyList(),
)

@Serializable
data class ClassInfo(
    val name: String,
    val internalName: String,
    val packageName: String,
    val accessFlags: List<String>,
    val superName: String? = null,
    val interfaces: List<String>,
    val annotations: List<String>,
    val sourceFile: String? = null,
    val isKotlin: Boolean,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
)

@Serializable
data class FieldInfo(
    val name: String,
    val descriptor: String,
    val accessFlags: List<String>,
    val constantValue: String? = null,
)

@Serializable
data class MethodInfo(
    val id: String,
    val name: String,
    val descriptor: String,
    val accessFlags: List<String>,
    val instructionCount: Int,
    val stringConstantCount: Int,
    val invocationCount: Int,
    val metrics: MethodMetrics,
)

@Serializable
data class MethodMetrics(
    val branchCount: Int = 0,
    val gotoCount: Int = 0,
    val switchCount: Int = 0,
    val arithmeticCount: Int = 0,
    val bitwiseCount: Int = 0,
    val arrayOperationCount: Int = 0,
    val numericConstantCount: Int = 0,
    val branchIndexes: List<Int> = emptyList(),
    val gotoIndexes: List<Int> = emptyList(),
    val switchIndexes: List<Int> = emptyList(),
    val numericConstantIndexes: List<Int> = emptyList(),
    val arithmeticIndexes: List<Int> = emptyList(),
    val bitwiseIndexes: List<Int> = emptyList(),
)

@Serializable
data class StringReference(
    val value: String,
    val ownerClass: String,
    val methodName: String? = null,
    val methodDescriptor: String? = null,
    val instructionIndex: Int? = null,
    /** Shannon entropy in bits per character over the raw constant. */
    val entropyBitsPerChar: Double = 0.0,
    /** Fraction of characters that are printable ASCII. */
    val printableRatio: Double = 1.0,
    /** `plain`, `suspicious`, or `likely-encrypted`. */
    val classification: String = "plain",
)

@Serializable
data class InvocationReference(
    val ownerClass: String,
    val sourceMethod: String,
    val sourceDescriptor: String,
    val targetOwner: String,
    val targetName: String,
    val targetDescriptor: String,
    val bootstrapOwner: String? = null,
    val bootstrapName: String? = null,
    val bootstrapDescriptor: String? = null,
    val bootstrapArguments: List<String> = emptyList(),
    val opcode: String,
    val instructionIndex: Int,
)

@Serializable
data class FieldReference(
    val ownerClass: String,
    val sourceMethod: String,
    val sourceDescriptor: String,
    val targetOwner: String,
    val targetName: String,
    val targetDescriptor: String,
    val opcode: String,
    val instructionIndex: Int,
)

@Serializable
data class ArchiveIdentity(
    val projectId: String,
    val recordedSha256: String,
    val observedSha256: String?,
    val matches: Boolean,
    val expectedSha256: String? = null,
    val expectedMatches: Boolean? = null,
)
