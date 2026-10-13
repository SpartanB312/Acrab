package net.evrt.acrab.analysis.android

import kotlinx.serialization.Serializable

@Serializable
data class AndroidPackageSummary(val name: String, val classCount: Int)

@Serializable
data class AndroidDexSummaryResult(
    val projectId: String,
    val inputSha256: String,
    val archivePath: String,
    val parserVersion: String,
    val dexFileCount: Int,
    val classCount: Int,
    val methodCount: Int,
    val fieldCount: Int,
    val stringCount: Int,
    val packageSummary: List<AndroidPackageSummary>,
    val dexFiles: List<AndroidDexFileInfo>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AndroidDexFileInfo(
    val name: String,
    val classCount: Int,
    val methodCount: Int,
    val fieldCount: Int,
    val stringCount: Int,
)

@Serializable
data class AndroidClassInfo(
    val dexFile: String,
    val type: String,
    val className: String,
    val packageName: String,
    val accessFlags: List<String>,
    val superClass: String? = null,
    val interfaces: List<String>,
    val sourceFile: String? = null,
    val fieldCount: Int,
    val methodCount: Int,
    val methods: List<AndroidMethodInfo> = emptyList(),
)

@Serializable
data class AndroidMethodInfo(
    val className: String,
    val name: String,
    val descriptor: String,
    val accessFlags: List<String>,
    val registerCount: Int? = null,
    val instructionCount: Int,
    val tryBlockCount: Int,
)

@Serializable
data class AndroidStringReference(
    val dexFile: String,
    val value: String,
    val ownerClass: String? = null,
    val methodName: String? = null,
    val methodDescriptor: String? = null,
    val instructionIndex: Int? = null,
)

@Serializable
data class AndroidInvocationReference(
    val dexFile: String,
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
data class AndroidInstructionInfo(
    val index: Int,
    val codeOffset: Int,
    val opcodeName: String,
    val codeUnits: Int,
    val registers: List<Int> = emptyList(),
    val literal: String? = null,
    val referenceType: String? = null,
    val reference: String? = null,
    val branchTargetIndex: Int? = null,
    val branchTargetOffset: Int? = null,
    val rawCodeUnits: List<Int> = emptyList(),
    val isPayload: Boolean = false,
    val payloadElementWidth: Int? = null,
    val payloadElements: List<String> = emptyList(),
    val payloadSwitchElements: List<AndroidSwitchPayloadElement> = emptyList(),
)

@Serializable
data class AndroidSwitchPayloadElement(val key: Int, val targetOffsetDelta: Int)

@Serializable
data class AndroidMethodBytecodeResult(
    val projectId: String,
    val inputSha256: String,
    val dexFile: String,
    val className: String,
    val methodName: String,
    val descriptor: String,
    val registerCount: Int,
    val instructionCount: Int,
    val tryBlockCount: Int,
    val truncated: Boolean,
    val instructions: List<AndroidInstructionInfo>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AndroidSensitiveFinding(
    val id: String,
    val category: String,
    val severity: String,
    val title: String,
    val description: String,
    val dexFile: String,
    val className: String,
    val methodName: String,
    val evidence: List<String>,
    val limitations: List<String>,
)

@Serializable
data class AndroidSensitiveFindingsResult(
    val projectId: String,
    val inputSha256: String,
    val candidateCount: Int,
    val truncated: Boolean,
    val findings: List<AndroidSensitiveFinding>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AndroidManifestSummaryResult(
    val projectId: String,
    val inputSha256: String,
    val archivePath: String,
    val manifestPath: String?,
    val parser: String,
    val packageName: String?,
    val versionCode: String?,
    val versionName: String?,
    val minSdkVersion: String?,
    val targetSdkVersion: String?,
    val compileSdkVersion: String?,
    val application: AndroidManifestApplicationInfo?,
    val usesPermissions: List<String>,
    val declaredPermissions: List<AndroidDeclaredPermissionInfo>,
    val usesFeatures: List<AndroidUsesFeatureInfo>,
    val componentCounts: Map<String, Int>,
    val components: List<AndroidManifestComponentInfo>,
    val warnings: List<String> = emptyList(),
    val modules: List<AndroidManifestModuleInfo> = emptyList(),
)

@Serializable
data class AndroidManifestRawResult(
    val projectId: String,
    val inputSha256: String,
    val archivePath: String,
    val manifestPath: String?,
    val parser: String,
    val decodedXml: String,
    val truncated: Boolean,
    val warnings: List<String> = emptyList(),
    val encoding: String = "utf-8",
    val byteLength: Int = 0,
    val returnedBytes: Int = 0,
)

@Serializable
data class AndroidManifestApplicationInfo(
    val name: String?,
    val label: String?,
    val icon: String?,
    val theme: String?,
    val debuggable: Boolean?,
    val allowBackup: Boolean?,
    val usesCleartextTraffic: Boolean?,
    val networkSecurityConfig: String?,
)

@Serializable
data class AndroidDeclaredPermissionInfo(val name: String, val protectionLevel: String?, val group: String?)
@Serializable
data class AndroidUsesFeatureInfo(val name: String, val required: Boolean?)

@Serializable
data class AndroidManifestComponentInfo(
    val type: String,
    val name: String?,
    val exported: Boolean?,
    val enabled: Boolean?,
    val permission: String?,
    val process: String?,
    val authorities: String?,
    val directBootAware: Boolean?,
    val intentFilters: List<AndroidIntentFilterInfo>,
    val moduleName: String? = null,
    val explicitExported: Boolean? = null,
    val effectiveExported: Boolean? = null,
)

@Serializable
data class AndroidManifestModuleInfo(
    val name: String,
    val path: String,
)

@Serializable
data class AndroidIntentFilterInfo(
    val actions: List<String>,
    val categories: List<String>,
    val dataSchemes: List<String>,
    val dataHosts: List<String>,
    val dataPorts: List<String>,
    val dataPaths: List<String>,
    val mimeTypes: List<String>,
)

@Serializable
data class AndroidManifestFinding(
    val id: String,
    val category: String,
    val severity: String,
    val title: String,
    val description: String,
    val evidence: List<String>,
    val limitations: List<String>,
)

@Serializable
data class AndroidManifestFindingsResult(
    val projectId: String,
    val inputSha256: String,
    val parser: String,
    val findings: List<AndroidManifestFinding>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AndroidDex2JarStatusResult(
    val available: Boolean,
    val version: String,
    val components: Map<String, String?>,
    val source: String,
    val capabilities: List<String>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AndroidDexToolsCatalogResult(
    val sourceDirectory: String?,
    val bundledArtifact: String,
    val tools: List<AndroidDexToolInfo>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AndroidDexToolInfo(val toolName: String, val scriptName: String, val mainClass: String, val category: String, val mcpSupport: String, val notes: String)

@Serializable
data class AndroidToolCommandOutput(val completed: Boolean, val executable: String, val arguments: List<String>, val stdout: String, val stderr: String, val exitCode: Int? = null, val durationMs: Long = 0, val timedOut: Boolean = false)

@Serializable
data class AndroidGeneratedFileInfo(val name: String, val path: String, val sizeBytes: Long, val sha256: String)

@Serializable
data class AndroidDex2JarConvertResult(val projectId: String, val inputArchivePath: String, val outputJarPath: String?, val outputFileName: String, val dexClassCount: Int, val jarClassCount: Int, val jarMethodCount: Int, val jarFieldCount: Int, val completed: Boolean, val command: AndroidToolCommandOutput, val warnings: List<String> = emptyList(), val toolVersion: String? = null, val sourceDexFiles: List<String> = emptyList(), val commands: List<AndroidToolCommandOutput> = emptyList())
@Serializable
data class AndroidBaksmaliResult(val projectId: String, val inputArchivePath: String, val outputDirectory: String?, val dexFileCount: Int, val smaliFileCount: Int, val sampleFiles: List<String>, val completed: Boolean, val command: AndroidToolCommandOutput, val warnings: List<String> = emptyList(), val toolVersion: String? = null, val sourceDexFiles: List<String> = emptyList(), val commands: List<AndroidToolCommandOutput> = emptyList())
@Serializable
data class AndroidAsmVerifyIssueInfo(val className: String, val methodName: String, val descriptor: String, val message: String)
@Serializable
data class AndroidAsmVerifyResult(val projectId: String, val inputJarPath: String, val classCount: Int, val methodCount: Int, val issueCount: Int, val truncated: Boolean, val issues: List<AndroidAsmVerifyIssueInfo>, val warnings: List<String> = emptyList())
@Serializable
data class AndroidDexChecksumResult(val projectId: String, val inputArchivePath: String, val outputArtifactPath: String?, val dexFileCount: Int, val outputs: List<AndroidGeneratedFileInfo>, val completed: Boolean, val warnings: List<String> = emptyList())
@Serializable
data class AndroidSmaliAssembleResult(val projectId: String, val inputPath: String, val outputDexPath: String?, val smaliFileCount: Int, val dexClassCount: Int, val dexMethodCount: Int, val completed: Boolean, val command: AndroidToolCommandOutput, val warnings: List<String> = emptyList(), val toolVersion: String? = null)
@Serializable
data class AndroidJar2DexConvertResult(val projectId: String, val inputJarPath: String, val outputDexPath: String?, val outputExists: Boolean, val outputSizeBytes: Long, val dexClassCount: Int, val dexMethodCount: Int, val command: AndroidToolCommandOutput, val warnings: List<String> = emptyList(), val outputFormat: String? = null, val dexFiles: List<String> = emptyList(), val minApi: Int? = null, val toolVersion: String? = null)
@Serializable
data class AndroidJar2JasminResult(val projectId: String, val inputJarPath: String, val outputDirectory: String?, val classFileCount: Int, val jasminFileCount: Int, val sampleFiles: List<String>, val completed: Boolean = false, val command: AndroidToolCommandOutput, val warnings: List<String> = emptyList())
@Serializable
data class AndroidJasmin2JarResult(val projectId: String, val inputDirectoryPath: String, val outputJarPath: String?, val outputExists: Boolean, val completed: Boolean = false, val jasminFileCount: Int, val jarClassCount: Int, val jarMethodCount: Int, val command: AndroidToolCommandOutput, val warnings: List<String> = emptyList())
@Serializable
data class AndroidClassVersionSwitchResult(val projectId: String, val inputJarPath: String, val outputJarPath: String?, val targetMajorVersion: Int, val inputClassCount: Int, val changedClassCount: Int, val completed: Boolean, val warnings: List<String> = emptyList())
