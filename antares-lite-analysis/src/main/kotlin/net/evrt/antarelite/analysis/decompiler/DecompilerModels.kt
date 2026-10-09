package net.evrt.antarelite.analysis.decompiler

import kotlinx.serialization.Serializable

@Serializable
data class DecompilerBackendInfo(
    val id: String,
    val displayName: String,
    val version: String,
    val status: String,
    val notes: String,
)

@Serializable
data class DecompileResult(
    val backend: String,
    val backendVersion: String,
    val projectId: String,
    val className: String,
    val inputSha256: String,
    val scope: String,
    val methodName: String? = null,
    val methodDescriptor: String? = null,
    val requestedDescriptor: String? = null,
    val methodMatchKind: String? = null,
    val emittedMethodName: String? = null,
    val emittedDescriptor: String? = null,
    val emittedDeclaration: String? = null,
    val emittedParameterCount: Int? = null,
    val source: String,
    val warnings: List<String>,
    val failedRegions: List<String>,
    val exactMethodMatch: Boolean = true,
    val confidence: String,
    val elapsedMs: Long,
)
