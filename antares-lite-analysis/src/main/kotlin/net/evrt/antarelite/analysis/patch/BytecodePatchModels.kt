package net.evrt.antarelite.analysis.patch

import kotlinx.serialization.Serializable

@Serializable
data class BytecodePatchOperation(
    val operation: String,
    val anchorInstructionIndex: Int,
    val anchorOpcode: String? = null,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class BytecodePatchRequest(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    val operations: List<BytecodePatchOperation>,
    val inputSha256: String? = null,
    val methodInstructionSha256: String? = null,
    val debugMetadataSha256: String? = null,
    val idempotencyKey: String? = null,
) {
    init {
        require(operations.isNotEmpty()) { "Bytecode patch requires at least one operation." }
        require(operations.size <= MAX_OPERATIONS) { "Bytecode patch exceeds $MAX_OPERATIONS operations." }
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported bytecode patch schema version: $schemaVersion; this server requires v$SCHEMA_VERSION. " +
                "Run method_patch_draft and use its archive, instruction, and debug-metadata hashes for a new v2 preview."
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_OPERATIONS = 50
    }
}

@Serializable
data class BytecodePatchOperationSpec(
    val operation: String,
    val schemaVersion: Int,
    val requiredArguments: List<String>,
    val optionalArguments: List<String>,
    val anchorRequired: Boolean,
    val proofRequired: Boolean,
    val description: String,
    val limitations: List<String>,
)

@Serializable
data class BytecodePatchCatalogResult(
    val engineVersion: String,
    val schemaVersion: Int,
    val operations: List<BytecodePatchOperationSpec>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class BytecodePatchDraftOperation(
    val operation: String,
    val anchorInstructionIndex: Int,
    val anchorOpcode: String,
    val schemaVersion: Int,
    val status: String,
    val failureReason: String? = null,
)

@Serializable
data class BytecodePatchDraftResult(
    val projectId: String,
    val inputSha256: String,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val methodInstructionSha256: String,
    val debugMetadataSha256: String,
    val draftId: String,
    val supported: Boolean,
    val status: String,
    val operations: List<BytecodePatchDraftOperation>,
    val patchMetadataAvailability: PatchMetadataAvailability? = null,
    val warnings: List<String> = emptyList(),
    val failureReason: String? = null,
)

@Serializable
data class BytecodePatchInstructionChange(
    val kind: String,
    val before: String? = null,
    val after: String? = null,
    /**
     * `semantic` for a change that alters the instruction (including its operand) and
     * `structural` for a branch-target rewrite where only the jump destination moved.
     */
    val category: String = "semantic",
)

@Serializable
data class BytecodePatchPreviewOperation(
    val operation: String,
    val anchorInstructionIndex: Int,
    val anchorOpcode: String,
    val status: String,
    val arguments: Map<String, String> = emptyMap(),
    val beforeEvidence: String? = null,
    val afterEvidence: String? = null,
    val failureReason: String? = null,
)

@Serializable
data class BytecodePatchPreviewResult(
    val projectId: String,
    val inputSha256: String,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val sourceMethodInstructionSha256: String,
    val sourceDebugMetadataSha256: String,
    val patchedMethodInstructionSha256: String,
    val patchedDebugMetadataSha256: String,
    val patchedClassSha256: String,
    val previewId: String,
    val supported: Boolean,
    val status: String,
    val operations: List<BytecodePatchPreviewOperation>,
    val beforeInstructions: List<String>,
    val afterInstructions: List<String>,
    val instructionChanges: List<BytecodePatchInstructionChange>,
    /** Length of `instructionChanges`; every entry is counted, semantic and structural alike. */
    val changedInstructionCount: Int,
    val patchMetadataAvailability: PatchMetadataAvailability? = null,
    val semanticChanges: List<BytecodePatchInstructionChange> = emptyList(),
    val structuralChanges: List<BytecodePatchInstructionChange> = emptyList(),
    val semanticChangeCount: Int = 0,
    val structuralChangeCount: Int = 0,
    val debugChanges: List<BytecodePatchInstructionChange> = emptyList(),
    val diffTruncated: Boolean = false,
    val instructionCountDelta: Int? = null,
    val verifierDiagnostics: List<String> = emptyList(),
    val verificationScope: String = "none",
    val verificationChecksPassed: List<String> = emptyList(),
    val failureReason: String? = null,
    val warnings: List<String> = emptyList(),
)

/** Debug-metadata availability for the addressed method, reported before a preview id is minted. */
@Serializable
data class PatchMetadataAvailability(
    val hasLocalVariableTable: Boolean,
    val localVariableCount: Int,
    val hasLineNumberTable: Boolean,
    val lineNumberCount: Int,
)

@Serializable
data class BytecodePatchApplyRequest(
    val projectId: String,
    val previewId: String,
    val inputSha256: String? = null,
    val request: BytecodePatchRequest,
)

@Serializable
data class BytecodePatchApplyResult(
    val projectId: String,
    val inputSha256: String,
    val previewId: String,
    val className: String,
    val methodName: String? = null,
    val methodDescriptor: String? = null,
    val artifactPath: String,
    val artifactChecksum: String,
    val outputProjectId: String,
    val verified: Boolean,
    val verificationScope: String = "none",
    val verificationChecksPassed: List<String> = emptyList(),
    val reindexed: Boolean,
    val changedMethodCount: Int,
    val changedClasses: List<String>,
    val warnings: List<String> = emptyList(),
)
