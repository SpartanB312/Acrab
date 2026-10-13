package net.evrt.acrab.analysis.android

internal data class DexArchiveModel(val files: List<DexFileModel>)

internal data class DexFileModel(
    val name: String,
    val bytes: ByteArray,
    val strings: List<String>,
    val classes: List<DexClassModel>,
    val fields: List<DexFieldModel>,
    val methods: List<DexMethodModel>,
    val stringReferences: List<AndroidStringReference>,
    val invocations: List<AndroidInvocationReference>,
)

internal data class DexClassModel(
    val dexFile: String,
    val className: String,
    val type: String,
    val accessFlags: List<String>,
    val superClass: String?,
    val interfaces: List<String>,
    val sourceFile: String?,
    val fields: List<DexFieldModel>,
    val methods: List<DexMethodModel>,
)

internal data class DexFieldModel(
    val ownerClass: String,
    val name: String,
    val descriptor: String,
    val accessFlags: List<String>,
)

internal data class DexMethodModel(
    val dexFile: String,
    val ownerClass: String,
    val name: String,
    val descriptor: String,
    val accessFlags: List<String>,
    val code: DexCodeModel?,
)

internal class DexCodeModel(
    val registerCount: Int,
    val tryBlockCount: Int,
    val instructions: List<AndroidInstructionInfo>,
)
