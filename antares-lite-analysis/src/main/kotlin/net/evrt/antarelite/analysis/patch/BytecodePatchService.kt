package net.evrt.antarelite.analysis.patch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.evrt.antarelite.analysis.index.JvmIndexService
import net.evrt.antarelite.analysis.index.ProjectRepository
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.util.Printer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.io.InputStream

object BytecodePatchCatalog {
    const val ENGINE_VERSION = "antares-lite-bytecode-patch-v2"
    const val SCHEMA_VERSION = BytecodePatchRequest.SCHEMA_VERSION

    val operations: List<BytecodePatchOperationSpec> by lazy {
        listOf(
            operation(
                "replace_insn",
                required = listOf("kind", "opcode"),
                optional = listOf(
                    "operand", "typeDescriptor", "owner", "name", "descriptor", "itf", "targetInstructionIndex",
                ),
                proofRequired = false,
                description = "Replace the anchored instruction with one instruction.",
                limitations = listOf(
                    "The kind must match the opcode and required operands.",
                    "Jump replacements require an existing targetInstructionIndex from method_bytecode.labelIndexes.",
                    "The resulting method must pass offline ASM verification.",
                ),
            ),
            operation(
                "delete_insn",
                required = emptyList(),
                optional = emptyList(),
                proofRequired = false,
                description = "Delete the anchored instruction.",
                limitations = listOf("The resulting method must pass stack verification."),
            ),
            operation(
                "replace_constant",
                required = listOf("value"),
                optional = listOf("valueType"),
                proofRequired = false,
                description = "Replace a string or bounded integer constant at the anchor.",
                limitations = listOf("Only same-type LDC string and integer replacements are supported."),
            ),
            operation(
                "redirect_call",
                required = listOf("owner", "name", "descriptor"),
                optional = listOf("itf"),
                proofRequired = false,
                description = "Replace the anchored method reference owner/name/descriptor.",
                limitations = listOf("The target descriptor must preserve the original invocation stack shape."),
            ),
            operation(
                "patch_local_name",
                required = listOf("name"),
                optional = listOf("descriptor"),
                proofRequired = false,
                description = "Restore local-variable debug metadata at the anchor.",
                limitations = listOf(
                    "Requires an existing local-variable table.",
                    "Fails with `unsupported` when the method has no local-variable table or no " +
                        "entry for the anchored slot; it never reports a no-op as applied.",
                ),
            ),
            operation(
                "patch_line_table",
                required = listOf("line"),
                optional = emptyList(),
                proofRequired = false,
                description = "Restore line-number debug metadata at the anchor.",
                limitations = listOf(
                    "Debug metadata only; bytecode semantics are unchanged.",
                    "Requires an existing line-number table and fails with `unsupported` without one.",
                ),
            ),
        )
    }

    fun require(operation: String): BytecodePatchOperationSpec =
        operations.firstOrNull { it.operation == operation }
            ?: throw IllegalArgumentException("Unsupported bytecode patch operation: $operation")

    private fun operation(
        name: String,
        required: List<String>,
        optional: List<String>,
        proofRequired: Boolean,
        description: String,
        limitations: List<String>,
    ) = BytecodePatchOperationSpec(
        operation = name,
        schemaVersion = SCHEMA_VERSION,
        requiredArguments = required,
        optionalArguments = optional,
        anchorRequired = true,
        proofRequired = proofRequired,
        description = description,
        limitations = limitations,
    )
}

class BytecodePatchService(
    private val projects: ProjectRepository,
    private val indexes: JvmIndexService,
) : AutoCloseable {
    private val applyJournal = PatchApplyJournal(projects.workspaceRoot)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        PatchPreviewStore.acquire(projects.workspaceRoot)
        try {
            applyJournal.recover(projects)
        } catch (failure: Throwable) {
            PatchPreviewStore.release(projects.workspaceRoot)
            throw failure
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) PatchPreviewStore.release(projects.workspaceRoot)
    }

    fun catalog(): BytecodePatchCatalogResult = BytecodePatchCatalogResult(
        engineVersion = BytecodePatchCatalog.ENGINE_VERSION,
        schemaVersion = BytecodePatchCatalog.SCHEMA_VERSION,
        operations = BytecodePatchCatalog.operations,
        warnings = listOf(
            "Patch verification uses offline ASM type and stack analysis; it does not prove runtime linking or intended behavior.",
            "The source archive is never modified; apply writes a new artifact and project.",
        ),
    )

    fun draft(
        projectId: String,
        request: BytecodePatchRequest,
    ): BytecodePatchDraftResult {
        val project = projects.require(projectId)
        val source = archiveSnapshot(project)
        requireIdentity(project, source, request.inputSha256)
        val classNode = loadClass(source.bytes, request.className)
        val method = findMethod(classNode, request.methodName, request.methodDescriptor)
        val methodHash = BytecodeMethodFingerprint.instructions(method)
        val debugHash = BytecodeMethodFingerprint.debugMetadata(method)
        requireMethodIdentity(methodHash, request.methodInstructionSha256)
        requireMethodIdentity(debugHash, request.debugMetadataSha256, "Debug metadata fingerprint")
        val draftOperations = validateOperations(method, request.operations)
        val supported = draftOperations.all { it.status == "valid" }
        val firstFailure = draftOperations.firstOrNull { it.failureReason != null }?.failureReason
        val metadata = metadataAvailability(method)
        // Reject debug-metadata operations up front when the method cannot carry them, instead of
        // minting a preview id for a change that cannot take effect.
        val unavailableMetadata = request.operations.filter { operation ->
            when (operation.operation) {
                "patch_local_name" -> !metadata.hasLocalVariableTable
                "patch_line_table" -> !metadata.hasLineNumberTable
                else -> false
            }
        }
        val metadataFailure = unavailableMetadata.firstOrNull()?.let { operation ->
            "Unsupported ${operation.operation}: " +
                if (operation.operation == "patch_local_name") {
                    "no local-variable table present on this method."
                } else {
                    "no line-number table present on this method."
                }
        }
        return BytecodePatchDraftResult(
            projectId = project.projectId,
            inputSha256 = source.sha256,
            className = request.className,
            methodName = request.methodName,
            methodDescriptor = request.methodDescriptor,
            methodInstructionSha256 = methodHash,
            debugMetadataSha256 = debugHash,
            draftId = request.idempotencyKey?.let { sha256(it.toByteArray()) }
                ?: stableId(project.sha256, request, methodHash),
            supported = supported && metadataFailure == null,
            status = when {
                metadataFailure != null -> "unsupported"
                supported -> "valid"
                firstFailure?.contains("requires a proof token") == true -> "proof-required"
                else -> "invalid"
            },
            operations = draftOperations,
            patchMetadataAvailability = metadata,
            failureReason = metadataFailure ?: firstFailure?.takeIf { !supported },
            warnings = listOf(
                "Draft validation does not rewrite bytecode or prove final verification.",
                "Debug-metadata operations require the corresponding table to exist; " +
                    "availability is reported in patchMetadataAvailability.",
            ),
        )
    }

    fun preview(
        projectId: String,
        request: BytecodePatchRequest,
    ): BytecodePatchPreviewResult {
        require(!request.inputSha256.isNullOrBlank()) { "Preview requires inputSha256 from method_patch_draft." }
        require(!request.methodInstructionSha256.isNullOrBlank()) {
            "Preview requires methodInstructionSha256 from method_patch_draft."
        }
        require(!request.debugMetadataSha256.isNullOrBlank()) {
            "Preview requires debugMetadataSha256 from method_patch_draft."
        }
        val project = projects.require(projectId)
        val source = archiveSnapshot(project)
        requireIdentity(project, source, request.inputSha256)
        val classNode = loadClass(source.bytes, request.className)
        val method = findMethod(classNode, request.methodName, request.methodDescriptor)
        val sourceHash = BytecodeMethodFingerprint.instructions(method)
        val sourceDebugHash = BytecodeMethodFingerprint.debugMetadata(method)
        requireMethodIdentity(sourceHash, request.methodInstructionSha256)
        requireMethodIdentity(sourceDebugHash, request.debugMetadataSha256, "Debug metadata fingerprint")
        val beforeInstructions = describeInstructions(method.instructions.toArray())
        val beforeDebugMetadata = describeDebugMetadata(method)
        val diagnostics = mutableListOf<String>()
        val appliedOperations = mutableListOf<BytecodePatchPreviewOperation>()
        val semanticJumpAnchors = mutableSetOf<Int>()
        val verifier = BytecodePatchVerifier(source.bytes)

        val (patchedClass, verification) = try {
            val originalNodes = method.instructions.toArray()
            val resolvedOperations = request.operations.map { operation ->
                val spec = BytecodePatchCatalog.require(operation.operation)
                val anchor = requireAnchor(operation, method, spec, originalNodes, requireOpcode = true)
                val failure = operationFailure(operation, anchor, method)
                require(failure == null) { failure ?: "Invalid operation." }
                operation to anchor
            }
            val instructionAnchors = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<AbstractInsnNode, Boolean>(),
            )
            resolvedOperations.forEach { (operation, anchor) ->
                if (operation.operation !in setOf("patch_local_name", "patch_line_table")) {
                    require(instructionAnchors.add(anchor)) {
                        "Multiple bytecode operations target instruction ${operation.anchorInstructionIndex}."
                    }
                }
                operationConflict(operation, request.operations)?.let { conflict ->
                    throw IllegalArgumentException(conflict)
                }
            }
            resolvedOperations.forEach { (operation, anchor) ->
                val spec = BytecodePatchCatalog.require(operation.operation)
                val beforeEvidence = describeOperationEvidence(operation, anchor, method, originalNodes)
                if (operation.operation == "replace_insn" &&
                    operation.arguments["kind"].equals("jump", ignoreCase = true) &&
                    (anchor as? JumpInsnNode)?.label !== originalNodes.getOrNull(
                        operation.arguments["targetInstructionIndex"]?.toIntOrNull() ?: -1,
                    )
                ) {
                    semanticJumpAnchors += operation.anchorInstructionIndex
                }
                val resultingNode = applyOperation(method, anchor, operation, spec, originalNodes, diagnostics)
                val afterEvidence = resultingNode?.let {
                    describeOperationEvidence(operation, it, method, method.instructions.toArray())
                }
                appliedOperations += BytecodePatchPreviewOperation(
                    operation = operation.operation,
                    anchorInstructionIndex = operation.anchorInstructionIndex,
                    anchorOpcode = opcodeName(anchor.opcode),
                    status = "applied",
                    arguments = operation.arguments.toSortedMap(),
                    beforeEvidence = beforeEvidence,
                    afterEvidence = afterEvidence,
                )
            }
            val bytes = verifier.writeClass(classNode)
            bytes to verifier.verify(bytes)
        } catch (failure: Exception) {
            return previewFailure(project, request, sourceHash, failure)
        }
        diagnostics += verification.diagnostics

        val patchedClassNode = runCatching {
            ClassReader(patchedClass).let { reader ->
                ClassNode().also { reader.accept(it, ClassReader.SKIP_FRAMES) }
            }
        }.getOrNull()
        val patchedMethod = patchedClassNode?.methods?.firstOrNull { methodNode ->
            methodNode.name == request.methodName && methodNode.desc == request.methodDescriptor
        }
        val afterInstructions = patchedMethod?.instructions?.toArray()
            ?.let(::describeInstructions).orEmpty()
        val debugChanges = patchedMethod?.let(::describeDebugMetadata)
            ?.let { after -> diffDebugMetadata(beforeDebugMetadata, after) }.orEmpty()
        val patchedMethodHash = patchedMethod?.let(BytecodeMethodFingerprint::instructions).orEmpty()
        val patchedDebugHash = patchedMethod?.let(BytecodeMethodFingerprint::debugMetadata).orEmpty()
        if (patchedMethodHash.isEmpty()) {
            diagnostics += "Patched method could not be re-read; verify the class manually."
        }

        val diffResult = diff(beforeInstructions, afterInstructions, semanticJumpAnchors)
        val changes = if (diffResult.truncated) operationChanges(appliedOperations) else diffResult.changes
        val semantic = changes.filter { it.category == "semantic" }
        val structural = changes.filter { it.category == "structural" }
        val supported = appliedOperations.all { it.status == "applied" } && verification.verified &&
            patchedMethodHash.isNotEmpty()
        val patchedClassHash = sha256(patchedClass)
        val canonicalRequestHash = sha256(canonicalRequest(request).toByteArray(StandardCharsets.UTF_8))
        val identityHash = sha256(
            "${project.projectId}\u0000${source.sha256}\u0000$canonicalRequestHash".toByteArray(StandardCharsets.UTF_8),
        )
        val candidatePreviewId = if (supported) {
            stablePreviewId(
                project.projectId,
                source.sha256,
                sourceHash,
                sourceDebugHash,
                patchedClassHash,
                canonicalRequestHash,
            )
        } else {
            ""
        }
        val cached = if (supported) {
            applyJournal.registerPreview(
                candidatePreviewId,
                request.idempotencyKey?.let { sha256(it.toByteArray(StandardCharsets.UTF_8)) },
                identityHash,
            )
            PatchPreviewStore.put(
                projects.workspaceRoot,
                PreparedPatch(
                    previewId = candidatePreviewId,
                    projectId = project.projectId,
                    inputSha256 = source.sha256,
                    canonicalRequestSha256 = canonicalRequestHash,
                    patchedClassSha256 = patchedClassHash,
                    patchedClassBytes = patchedClass,
                    verificationScope = verification.scope,
                    verificationChecksPassed = verification.checksPassed,
                ),
            )
        } else {
            false
        }
        val previewReady = supported && cached
        return BytecodePatchPreviewResult(
            projectId = project.projectId,
            inputSha256 = source.sha256,
            className = request.className,
            methodName = request.methodName,
            methodDescriptor = request.methodDescriptor,
            sourceMethodInstructionSha256 = sourceHash,
            sourceDebugMetadataSha256 = sourceDebugHash,
            patchedMethodInstructionSha256 = patchedMethodHash,
            patchedDebugMetadataSha256 = patchedDebugHash,
            patchedClassSha256 = patchedClassHash,
            previewId = candidatePreviewId.takeIf { previewReady }.orEmpty(),
            supported = previewReady,
            status = when {
                previewReady -> "valid"
                supported -> "preview-cache-limit"
                !verification.verified -> "verification-failed"
                else -> "invalid"
            },
            operations = appliedOperations,
            beforeInstructions = beforeInstructions,
            afterInstructions = afterInstructions,
            instructionChanges = changes,
            // Aligned with `instructionChanges`: every entry is counted. The former value counted
            // only applied operations while the array also held branch-target rewrites.
            changedInstructionCount = changes.size,
            semanticChanges = semantic,
            structuralChanges = structural,
            semanticChangeCount = semantic.size,
            structuralChangeCount = structural.size,
            debugChanges = debugChanges,
            diffTruncated = diffResult.truncated,
            instructionCountDelta = afterInstructions.size - beforeInstructions.size,
            verifierDiagnostics = diagnostics.distinct(),
            verificationScope = verification.scope,
            verificationChecksPassed = verification.checksPassed,
            failureReason = when {
                supported && !cached -> "Prepared patch exceeds the available preview cache capacity."
                !verification.verified -> verification.diagnostics.joinToString()
                else -> null
            },
            warnings = listOf(
                "Preview does not modify the source archive.",
                "Prepared preview bytes are held in process memory for up to 15 minutes.",
                "Structural verification does not prove intended program behavior.",
                "changedInstructionCount describes instructionChanges, including branch-target rewrites.",
                if (diffResult.truncated) {
                    "Instruction diff exceeded the configured work limit; instructionChanges contains operation-level evidence only."
                } else {
                    ""
                },
            ).filter(String::isNotEmpty),
            patchMetadataAvailability = metadataAvailability(patchedMethod ?: method),
        )
    }

    fun applyMethod(
        projectId: String,
        applyRequest: BytecodePatchApplyRequest,
    ): BytecodePatchApplyResult {
        require(applyRequest.previewId.isNotBlank()) { "Preview id must not be blank." }
        require(applyRequest.projectId == projectId) { "Apply project id does not match the request project id." }
        val request = applyRequest.request
        require(!request.inputSha256.isNullOrBlank()) { "Apply requires inputSha256 from method_patch_draft." }
        if (applyRequest.inputSha256 != null) {
            require(applyRequest.inputSha256 == request.inputSha256) {
                "Outer inputSha256 does not match request.inputSha256."
            }
        }
        val canonicalRequestHash = sha256(canonicalRequest(request).toByteArray(StandardCharsets.UTF_8))

        return applyJournal.withLock {
            applyJournal.recoverLocked(projects)
            val prior = applyJournal.readLocked(applyRequest.previewId)
            require(prior?.state != PatchApplyJournal.CONSUMED) {
                "preview-consumed: this preview already published output project ${prior?.outputProject?.projectId.orEmpty()}."
            }
            require(prior == null) { "Preview apply state is unresolved; refusing to publish again." }

            val prepared = PatchPreviewStore.get(projects.workspaceRoot, applyRequest.previewId)
                ?: throw IllegalArgumentException("Preview is unknown, expired, or lost after restart; create a new preview.")
            require(prepared.previewId == applyRequest.previewId && prepared.projectId == projectId) {
                "Preview does not belong to this project."
            }
            require(prepared.canonicalRequestSha256 == canonicalRequestHash) {
                "Apply request does not match the request bound to the preview."
            }

            val project = projects.require(projectId)
            val source = archiveSnapshot(project)
            requireIdentity(project, source, request.inputSha256)
            require(prepared.inputSha256 == source.sha256) { "Input checksum changed since preview." }
            require(sha256(prepared.patchedClassBytes) == prepared.patchedClassSha256) {
                "Prepared class bytes failed their cached checksum."
            }

            val verification = BytecodePatchVerifier(source.bytes).verify(prepared.patchedClassBytes)
            require(verification.verified) {
                "Prepared class failed ${verification.scope} verification: ${verification.diagnostics.joinToString()}"
            }
            require(
                verification.scope == prepared.verificationScope &&
                    verification.checksPassed == prepared.verificationChecksPassed,
            ) { "Prepared verification report does not match the cached preview." }

            val patchedJar = rewriteJar(source.bytes, request.className, prepared.patchedClassBytes)
            val outputProjectId = "p-" + java.util.UUID.randomUUID().toString().replace("-", "")
            val outputProject = projects.stagePreparedProject(
                projectId = outputProjectId,
                fileName = project.fileName.removeSuffix(".jar") + "-patched.jar",
                bytes = patchedJar,
            )
            val preparedEntry = PatchApplyJournalEntry(
                previewId = applyRequest.previewId,
                state = PatchApplyJournal.PREPARED,
                projectId = projectId,
                inputSha256 = source.sha256,
                canonicalRequestSha256 = canonicalRequestHash,
                outputProject = outputProject,
                stagedArchivePath = projects.stagedArchivePath(outputProjectId).toString(),
            )
            applyJournal.writePreparedLocked(preparedEntry)
            val publishedProject = projects.publishPreparedProject(outputProject)
            applyJournal.markConsumedLocked(preparedEntry)
            PatchPreviewStore.remove(projects.workspaceRoot, applyRequest.previewId)

            BytecodePatchApplyResult(
                projectId = project.projectId,
                inputSha256 = source.sha256,
                previewId = applyRequest.previewId,
                className = request.className,
                methodName = request.methodName,
                methodDescriptor = request.methodDescriptor,
                artifactPath = Path.of(publishedProject.archivePath).toAbsolutePath().normalize().toString(),
                artifactChecksum = publishedProject.sha256,
                outputProjectId = publishedProject.projectId,
                verified = verification.verified,
                verificationScope = verification.scope,
                verificationChecksPassed = verification.checksPassed,
                reindexed = false,
                changedMethodCount = 1,
                changedClasses = listOf(request.className),
                warnings = listOf(
                    "The source archive was not modified.",
                    "Structural verification does not prove intended program behavior.",
                    "A successfully applied preview id cannot be reapplied to the same source artifact.",
                ),
            )
        }
    }

    private fun previewFailure(
        project: net.evrt.antarelite.analysis.index.ProjectRecord,
        request: BytecodePatchRequest,
        sourceHash: String,
        failure: Throwable,
    ): BytecodePatchPreviewResult {
        val classNode = runCatching { loadClass(project, request.className) }.getOrNull()
        val method = classNode?.methods?.firstOrNull {
            it.name == request.methodName && it.desc == request.methodDescriptor
        }
        return BytecodePatchPreviewResult(
            projectId = project.projectId,
            inputSha256 = project.sha256,
            className = request.className,
            methodName = request.methodName,
            methodDescriptor = request.methodDescriptor,
            sourceMethodInstructionSha256 = sourceHash,
            sourceDebugMetadataSha256 = "",
            patchedMethodInstructionSha256 = "",
            patchedDebugMetadataSha256 = "",
            patchedClassSha256 = "",
            previewId = "",
            supported = false,
            status = "invalid",
            operations = request.operations.map { operation ->
                BytecodePatchPreviewOperation(
                    operation = operation.operation,
                    anchorInstructionIndex = operation.anchorInstructionIndex,
                    anchorOpcode = method?.instructions?.toArray()
                        ?.getOrNull(operation.anchorInstructionIndex)?.let { opcodeName(it.opcode) }
                        ?: "UNKNOWN",
                    status = "failed",
                    failureReason = failure.message ?: failure::class.java.simpleName,
                )
            },
            beforeInstructions = method?.instructions?.toArray()?.let(::describeInstructions).orEmpty(),
            afterInstructions = emptyList(),
            instructionChanges = emptyList(),
            changedInstructionCount = 0,
            verifierDiagnostics = listOf("Preview failed: ${failure.message ?: failure::class.java.simpleName}"),
            failureReason = failure.message ?: failure::class.java.simpleName,
            warnings = listOf("Preview does not modify the source archive."),
        )
    }

    private fun validateOperations(
        method: MethodNode,
        operations: List<BytecodePatchOperation>,
    ): List<BytecodePatchDraftOperation> = operations.map { operation ->
        val spec = try {
            BytecodePatchCatalog.require(operation.operation)
        } catch (failure: IllegalArgumentException) {
            return@map BytecodePatchDraftOperation(
                operation = operation.operation,
                anchorInstructionIndex = operation.anchorInstructionIndex,
                anchorOpcode = "UNKNOWN",
                schemaVersion = BytecodePatchCatalog.SCHEMA_VERSION,
                status = "invalid",
                failureReason = failure.message,
            )
        }
        val requiredArguments = (spec.requiredArguments + when (operation.operation) {
            "replace_insn" -> when (operation.arguments["kind"]?.lowercase()) {
                "int", "var" -> listOf("operand")
                "type" -> listOf("typeDescriptor")
                "field" -> listOf("owner", "name", "descriptor")
                "method" -> listOf("owner", "name", "descriptor")
                "jump" -> listOf("targetInstructionIndex")
                "ldc" -> listOf("operand")
                else -> emptyList()
            }
            else -> emptyList()
        }).distinct()
        val missingArguments = requiredArguments.filter { argument ->
            operation.arguments[argument]?.let { value ->
                val allowsEmpty = argument == "value" ||
                    operation.arguments["kind"].equals("ldc", ignoreCase = true) && argument == "operand"
                !allowsEmpty && value.isBlank()
            } ?: true
        }
        val allowedArguments = spec.requiredArguments + spec.optionalArguments +
            if (operation.operation == "replace_insn") setOf("kind", "opcode") else emptySet()
        val unexpectedArguments = operation.arguments.keys - allowedArguments
        val invalidAnchor = if (spec.anchorRequired) anchorFailure(operation, method) else null
        val anchor = method.instructions.toArray().getOrNull(operation.anchorInstructionIndex)
        val invalidOperation = if (invalidAnchor == null && anchor != null) {
            operationFailure(operation, anchor, method)
        } else {
            null
        }
        val failure = when {
            missingArguments.isNotEmpty() ->
                "Missing required arguments: ${missingArguments.joinToString()}."
            unexpectedArguments.isNotEmpty() ->
                "Unexpected arguments: ${unexpectedArguments.sorted().joinToString()}."
            invalidAnchor != null -> invalidAnchor
            invalidOperation != null -> invalidOperation
            spec.proofRequired && operation.arguments["proofToken"].isNullOrBlank() ->
                "This operation requires a proof token."
            else -> null
        }
        BytecodePatchDraftOperation(
            operation = operation.operation,
            anchorInstructionIndex = operation.anchorInstructionIndex,
            anchorOpcode = opcodeName(
                method.instructions.toArray().getOrNull(operation.anchorInstructionIndex)?.opcode ?: -1,
            ),
            schemaVersion = BytecodePatchCatalog.SCHEMA_VERSION,
            status = when {
                failure == null -> "valid"
                failure.startsWith("Unsupported") -> "unsupported"
                else -> "invalid"
            },
            failureReason = failure,
        )
    }.mapIndexed { index, draftOperation ->
        val conflict = operationConflict(operations[index], operations)
        if (conflict == null) draftOperation else draftOperation.copy(status = "invalid", failureReason = conflict)
    }

    private fun operationConflict(
        operation: BytecodePatchOperation,
        operations: List<BytecodePatchOperation>,
    ): String? {
        if (operation.operation !in DEBUG_OPERATIONS) return null
        val conflicting = operations.firstOrNull {
            it.anchorInstructionIndex == operation.anchorInstructionIndex &&
                it.operation in DESTRUCTIVE_INSTRUCTION_OPERATIONS
        } ?: return null
        return "${operation.operation} conflicts with ${conflicting.operation} on the same instruction."
    }

    private fun operationFailure(
        operation: BytecodePatchOperation,
        anchor: AbstractInsnNode,
        method: MethodNode,
    ): String? {
        return try {
            val spec = BytecodePatchCatalog.require(operation.operation)
            val allowedArguments = spec.requiredArguments + spec.optionalArguments +
                if (operation.operation == "replace_insn") setOf("kind", "opcode") else emptySet()
            val unexpected = operation.arguments.keys - allowedArguments
            require(unexpected.isEmpty()) {
                "Unexpected arguments: ${unexpected.sorted().joinToString()}."
            }
            when (operation.operation) {
                "replace_constant" -> {
                    val constant = (anchor as? LdcInsnNode)?.cst
                        ?: return "Unsupported replace_constant: anchor is not an LDC constant."
                    val actualType = when (constant) {
                        is String -> "string"
                        is Int -> "int"
                        else -> return "Unsupported replace_constant: only String and Int LDC constants are supported."
                    }
                    val requestedType = operation.arguments["valueType"]?.lowercase() ?: actualType
                    require(requestedType == actualType) {
                        "Unsupported replace_constant: valueType must preserve the existing $actualType constant type."
                    }
                    if (actualType == "int") {
                        require(operation.arguments.getValue("value").toIntOrNull() != null) {
                            "Invalid integer constant value."
                        }
                    }
                    null
                }
                "replace_insn" -> replacementFailure(operation, method)
                "redirect_call" -> redirectFailure(operation, anchor)
                "patch_local_name" -> localNameFailure(operation, anchor, method)
                "patch_line_table" -> lineTableFailure(operation, anchor, method)
                else -> null
            }
        } catch (failure: Exception) {
            failure.message ?: "Invalid operation arguments."
        }
    }

    private fun replacementFailure(operation: BytecodePatchOperation, method: MethodNode): String? {
        val kind = operation.arguments.getValue("kind").lowercase()
        val opcode = opcodeValue(operation.arguments.getValue("opcode"))
        val args = operation.arguments
        val kindArguments = when (kind) {
            "simple" -> emptySet()
            "int", "var", "ldc" -> setOf("operand")
            "type" -> setOf("typeDescriptor")
            "field" -> setOf("owner", "name", "descriptor")
            "method" -> setOf("owner", "name", "descriptor", "itf")
            "jump" -> setOf("targetInstructionIndex")
            else -> emptySet()
        }
        val unexpected = args.keys - (setOf("kind", "opcode") + kindArguments)
        require(unexpected.isEmpty()) {
            "Arguments ${unexpected.sorted().joinToString()} are not valid for replacement kind '$kind'."
        }
        return when (kind) {
            "simple" -> if (opcode in SIMPLE_OPCODES) null else
                "Opcode ${opcodeName(opcode)} requires an operand and cannot use kind 'simple'."
            "int" -> {
                require(opcode in setOf(Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.NEWARRAY)) {
                    "Opcode ${opcodeName(opcode)} cannot use kind 'int'."
                }
                val operand = args.getValue("operand").toIntOrNull()
                    ?: throw IllegalArgumentException("Integer operand is invalid.")
                when (opcode) {
                    Opcodes.BIPUSH -> require(operand in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                        "BIPUSH operand must fit in a signed byte."
                    }
                    Opcodes.SIPUSH -> require(operand in Short.MIN_VALUE..Short.MAX_VALUE) {
                        "SIPUSH operand must fit in a signed short."
                    }
                    Opcodes.NEWARRAY -> require(operand in Opcodes.T_BOOLEAN..Opcodes.T_LONG) {
                        "NEWARRAY operand must be a valid JVM array type code."
                    }
                }
                null
            }
            "var" -> {
                require(opcode in VARIABLE_OPCODES) {
                    "Opcode ${opcodeName(opcode)} cannot use kind 'var'."
                }
                val slot = args.getValue("operand").toIntOrNull()
                    ?: throw IllegalArgumentException("Variable slot is invalid.")
                val maxSlot = if (opcode in WIDE_VARIABLE_OPCODES) 65533 else 65534
                require(slot in 0..maxSlot) { "Variable slot must be in range 0..$maxSlot for this opcode." }
                null
            }
            "type" -> {
                require(opcode in TYPE_OPCODES) {
                    "Opcode ${opcodeName(opcode)} cannot use kind 'type'."
                }
                val typeName = args.getValue("typeDescriptor")
                require(validTypeOperand(typeName, opcode == Opcodes.NEW)) {
                    "Type operand is invalid for ${opcodeName(opcode)}."
                }
                null
            }
            "field" -> {
                require(opcode in Opcodes.GETSTATIC..Opcodes.PUTFIELD) {
                    "Opcode ${opcodeName(opcode)} cannot use kind 'field'."
                }
                require(validOwner(args.getValue("owner"))) { "Field owner must be an internal JVM class name." }
                require(validMemberName(args.getValue("name"))) { "Field name is invalid." }
                val fieldDescriptor = args.getValue("descriptor")
                val fieldType = Type.getType(fieldDescriptor)
                require(fieldType.descriptor == fieldDescriptor && isValidValueType(fieldType)) {
                    "Field descriptor must describe a non-void field type."
                }
                null
            }
            "method" -> {
                require(opcode in INVOCATION_OPCODES) {
                    "Opcode ${opcodeName(opcode)} cannot use kind 'method'."
                }
                require(validOwner(args.getValue("owner"))) { "Method owner must be an internal JVM class name." }
                val name = args.getValue("name")
                require(validMethodName(name)) { "Method name is invalid." }
                val descriptor = parseMethodDescriptor(
                    args.getValue("descriptor"),
                    hasReceiver = opcode != Opcodes.INVOKESTATIC,
                )
                val itf = parseInterfaceFlag(args["itf"], opcode)
                validateInterfaceFlag(opcode, itf)
                validateConstructorReference(name, descriptor, opcode)
                null
            }
            "jump" -> {
                require(opcode in JUMP_OPCODES) { "Opcode ${opcodeName(opcode)} cannot use kind 'jump'." }
                val target = args.getValue("targetInstructionIndex").toIntOrNull()
                    ?: throw IllegalArgumentException("targetInstructionIndex must be an integer label index.")
                require(method.instructions.toArray().getOrNull(target) is LabelNode) {
                    "Jump target $target is not a label from this method."
                }
                null
            }
            "ldc" -> {
                require(opcode == Opcodes.LDC) { "LDC replacement requires opcode LDC." }
                require(args.containsKey("operand")) { "LDC replacement requires operand." }
                null
            }
            else -> "Unsupported replacement kind: $kind."
        }
    }

    private fun redirectFailure(operation: BytecodePatchOperation, anchor: AbstractInsnNode): String? {
        val invoke = anchor as? MethodInsnNode
            ?: return "Unsupported redirect_call: anchor is not a method invocation."
        val args = operation.arguments
        if (!validOwner(args.getValue("owner"))) return "Target owner must be an internal JVM class name."
        val name = args.getValue("name")
        if (!validMethodName(name)) return "Target method name is invalid."
        val methodType = parseMethodDescriptor(
            args.getValue("descriptor"),
            hasReceiver = invoke.opcode != Opcodes.INVOKESTATIC,
        )
        val descriptor = methodType.descriptor
        if (descriptor != invoke.desc) {
            return "Unsupported redirect_call: descriptor must remain ${invoke.desc} to preserve the operand stack shape."
        }
        val itf = parseInterfaceFlag(args["itf"], invoke.opcode, invoke.itf)
        validateInterfaceFlag(invoke.opcode, itf)
        validateConstructorReference(name, methodType, invoke.opcode)
        return null
    }

    private fun localNameFailure(
        operation: BytecodePatchOperation,
        anchor: AbstractInsnNode,
        method: MethodNode,
    ): String? {
        val name = operation.arguments["name"] ?: return "Missing required argument: name."
        if (!validLocalName(name)) return "Local-variable name is invalid."
        operation.arguments["descriptor"]?.let { descriptor ->
            val type = runCatching { Type.getType(descriptor) }.getOrNull()
            if (type == null || type.descriptor != descriptor || !isValidValueType(type)) {
                return "Local-variable descriptor is invalid."
            }
        }
        val slot = when (anchor) {
            is VarInsnNode -> anchor.`var`
            is IincInsnNode -> anchor.`var`
            else -> return "Unsupported patch_local_name: anchor must read, write, or increment a local slot."
        }
        val matching = matchingLocalVariables(anchor, slot, operation, method)
        return when (matching.size) {
            0 -> "Unsupported patch_local_name: no local-variable entry matches slot $slot at this instruction."
            1 -> null
            else -> "Unsupported patch_local_name: multiple local-variable entries match slot $slot at this instruction."
        }
    }

    private fun lineTableFailure(
        operation: BytecodePatchOperation,
        anchor: AbstractInsnNode,
        method: MethodNode,
    ): String? {
        val line = operation.arguments.getValue("line").toIntOrNull()
            ?: return "Line number must be an integer."
        if (line !in 0..65535) return "Line number must be in range 0..65535."
        return if (lineNumberAt(anchor, method) == null) {
            "Unsupported patch_line_table: no unambiguous line-number entry applies at this anchor."
        } else {
            null
        }
    }

    private fun lineNumberAt(anchor: AbstractInsnNode, method: MethodNode): LineNumberNode? {
        val anchorIndex = method.instructions.indexOf(anchor)
        val candidates = method.instructions.toArray().filterIsInstance<LineNumberNode>()
            .map { line -> method.instructions.indexOf(line.start) to line }
            .filter { (start, _) -> start <= anchorIndex }
        val nearestIndex = candidates.maxOfOrNull { it.first } ?: return null
        val nearest = candidates.filter { it.first == nearestIndex }
        if (nearest.map { it.second.line }.distinct().size != 1) return null
        return nearest.last().second
    }

    private fun matchingLocalVariables(
        anchor: AbstractInsnNode,
        slot: Int,
        operation: BytecodePatchOperation,
        method: MethodNode,
    ): List<org.objectweb.asm.tree.LocalVariableNode> {
        val index = method.instructions.indexOf(anchor)
        return method.localVariables.orEmpty().filter { variable ->
            variable.index == slot &&
                (operation.arguments["descriptor"] == null || variable.desc == operation.arguments["descriptor"]) &&
                method.instructions.indexOf(variable.start) <= index &&
                index < method.instructions.indexOf(variable.end)
        }
    }

    private fun describeOperationEvidence(
        operation: BytecodePatchOperation,
        anchor: AbstractInsnNode,
        method: MethodNode,
        instructions: Array<AbstractInsnNode>,
    ): String? = when (operation.operation) {
        "patch_local_name" -> {
            val slot = when (anchor) {
                is VarInsnNode -> anchor.`var`
                is IincInsnNode -> anchor.`var`
                else -> null
            }
            slot?.let { localSlot ->
                matchingLocalVariables(anchor, localSlot, operation, method).singleOrNull()
            }?.let { local ->
                val start = method.instructions.indexOf(local.start)
                val end = method.instructions.indexOf(local.end)
                "local slot=${local.index} descriptor=${local.desc} scope=[$start,$end) name=${local.name} " +
                    "signature=${local.signature.orEmpty()}"
            }
        }
        "patch_line_table" -> lineNumberAt(anchor, method)?.let { line ->
            "line anchor=${method.instructions.indexOf(anchor)} " +
                "start=${method.instructions.indexOf(line.start)} value=${line.line}"
        }
        else -> {
            val index = instructions.indexOf(anchor)
            if (index < 0) null else "$index: ${describeInstruction(anchor, instructions)}"
        }
    }

    private fun parseInterfaceFlag(
        value: String?,
        opcode: Int,
        default: Boolean = opcode == Opcodes.INVOKEINTERFACE,
    ): Boolean {
        if (value == null) return default
        return value.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("itf must be exactly true or false.")
    }

    private fun validateInterfaceFlag(opcode: Int, itf: Boolean) {
        require(opcode != Opcodes.INVOKEINTERFACE || itf) { "INVOKEINTERFACE requires itf=true." }
        require(opcode != Opcodes.INVOKEVIRTUAL || !itf) { "INVOKEVIRTUAL requires itf=false." }
    }

    private fun validateConstructorReference(name: String, descriptor: Type, opcode: Int) {
        require(name != "<clinit>") { "Method references cannot target <clinit>." }
        if (name == "<init>") {
            require(opcode == Opcodes.INVOKESPECIAL && descriptor.returnType.sort == Type.VOID) {
                "<init> requires INVOKESPECIAL and a void return descriptor."
            }
        }
    }

    private fun parseMethodDescriptor(descriptor: String, hasReceiver: Boolean): Type {
        val closingParen = descriptor.indexOf(')')
        require(descriptor.startsWith("(") && closingParen >= 0) { "Method descriptor is invalid." }
        val arguments = Type.getArgumentTypes(descriptor)
        val returnType = Type.getReturnType(descriptor)
        require(returnType.descriptor == descriptor.substring(closingParen + 1)) {
            "Method descriptor has trailing or malformed return data."
        }
        require(arguments.all { isValidValueType(it) } && isValidValueType(returnType, allowVoid = true)) {
            "Method descriptor contains an invalid value type."
        }
        val argumentSlots = arguments.sumOf { if (it.sort == Type.LONG || it.sort == Type.DOUBLE) 2 else 1 }
        require(argumentSlots + (if (hasReceiver) 1 else 0) <= 255) {
            "Method descriptor exceeds the JVM parameter slot limit."
        }
        return Type.getMethodType(descriptor)
    }

    private fun isValidValueType(type: Type, allowVoid: Boolean = false): Boolean = when (type.sort) {
        Type.VOID -> allowVoid
        Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE -> true
        Type.OBJECT -> validOwner(type.internalName)
        Type.ARRAY -> type.dimensions <= 255 && isValidValueType(type.elementType)
        else -> false
    }

    private fun validOwner(value: String): Boolean = validInternalName(value)

    private fun validInternalName(value: String): Boolean =
        value.isNotBlank() && !value.startsWith('/') && !value.endsWith('/') && "//" !in value &&
            value.split('/').all { segment ->
            segment.isNotEmpty() && segment.none { it in ".;[" }
        }

    private fun validMemberName(value: String): Boolean =
        value.isNotBlank() && value.none { it in ".;[/" } && value != "<init>" && value != "<clinit>"

    private fun validLocalName(value: String): Boolean =
        value.isNotEmpty() && value.none { it in ".;[/" }

    private fun validMethodName(value: String): Boolean =
        value == "<init>" || value == "<clinit>" ||
            value.isNotBlank() && value.none { it in ".;[/<>" }

    private fun validTypeOperand(value: String, newObject: Boolean): Boolean {
        if (value.isBlank()) return false
        if (newObject) return validOwner(value)
        return runCatching {
            if (value.startsWith("[")) {
                val type = Type.getType(value)
                type.sort == Type.ARRAY && type.descriptor == value && isValidValueType(type)
            } else {
                validOwner(value)
            }
        }.getOrDefault(false)
    }

    private fun requireAnchor(
        operation: BytecodePatchOperation,
        method: MethodNode,
        spec: BytecodePatchOperationSpec,
        originalNodes: Array<AbstractInsnNode> = method.instructions.toArray(),
        requireOpcode: Boolean = false,
    ): AbstractInsnNode {
        val instruction = originalNodes.getOrNull(operation.anchorInstructionIndex)
            ?: throw IllegalArgumentException("Instruction index out of range: ${operation.anchorInstructionIndex}")
        require(instruction !is LabelNode && instruction !is LineNumberNode &&
            instruction !is org.objectweb.asm.tree.FrameNode
        ) {
            "Instruction index ${operation.anchorInstructionIndex} points to an ASM metadata node."
        }
        if (spec.anchorRequired && requireOpcode) {
            require(!operation.anchorOpcode.isNullOrBlank()) {
                "Anchor opcode is required at instruction ${operation.anchorInstructionIndex}."
            }
        }
        if (spec.anchorRequired && operation.anchorOpcode != null && !anchorMatches(operation, instruction)) {
            throw IllegalArgumentException(
                "Anchor opcode mismatch at instruction ${operation.anchorInstructionIndex}.",
            )
        }
        return instruction
    }

    private fun anchorFailure(operation: BytecodePatchOperation, method: MethodNode): String? {
        val instruction = method.instructions.toArray().getOrNull(operation.anchorInstructionIndex)
            ?: return "Instruction index out of range: ${operation.anchorInstructionIndex}."
        if (instruction is LabelNode || instruction is LineNumberNode ||
            instruction is org.objectweb.asm.tree.FrameNode
        ) {
            return "Instruction index ${operation.anchorInstructionIndex} points to an ASM metadata node."
        }
        return if (operation.anchorOpcode != null && !anchorMatches(operation, instruction)) {
            "Anchor opcode mismatch at instruction ${operation.anchorInstructionIndex}."
        } else {
            null
        }
    }

    private fun anchorMatches(
        operation: BytecodePatchOperation,
        instruction: AbstractInsnNode,
    ): Boolean {
        val expected = operation.anchorOpcode?.uppercase() ?: return true
        return opcodeName(instruction.opcode) == expected
    }

    private fun applyOperation(
        method: MethodNode,
        anchor: AbstractInsnNode,
        operation: BytecodePatchOperation,
        spec: BytecodePatchOperationSpec,
        originalNodes: Array<AbstractInsnNode>,
        diagnostics: MutableList<String>,
    ): AbstractInsnNode? = when (operation.operation) {
        "replace_insn" -> replaceInsn(method, anchor, operation, originalNodes, diagnostics)
        "delete_insn" -> {
            method.instructions.remove(anchor)
            null
        }
        "replace_constant" -> anchor.also { replaceConstant(it, operation) }
        "redirect_call" -> anchor.also { redirectCall(it, operation) }
        "patch_local_name" -> anchor.also { patchLocalName(it, method, operation, diagnostics) }
        "patch_line_table" -> anchor.also { patchLineTable(it, method, operation, diagnostics) }
        else -> throw IllegalArgumentException("Unsupported bytecode patch operation: ${operation.operation}")
    }

    private fun replaceInsn(
        method: MethodNode,
        anchor: AbstractInsnNode,
        operation: BytecodePatchOperation,
        originalNodes: Array<AbstractInsnNode>,
        diagnostics: MutableList<String>,
    ): AbstractInsnNode {
        val kind = operation.arguments.getValue("kind").lowercase()
        val opcodeName = operation.arguments.getValue("opcode").uppercase()
        val opcode = opcodeValue(opcodeName)
        val replacement = when (kind) {
            "simple" -> InsnNode(opcode)
            "int" -> IntInsnNode(opcode, operation.arguments.getValue("operand").toInt())
            "var" -> VarInsnNode(opcode, operation.arguments.getValue("operand").toInt())
            "type" -> TypeInsnNode(opcode, operation.arguments.getValue("typeDescriptor"))
            "field" -> FieldInsnNode(
                opcode,
                operation.arguments.getValue("owner"),
                operation.arguments.getValue("name"),
                operation.arguments.getValue("descriptor"),
            )
            "method" -> MethodInsnNode(
                opcode,
                operation.arguments.getValue("owner"),
                operation.arguments.getValue("name"),
                operation.arguments.getValue("descriptor"),
                parseInterfaceFlag(operation.arguments["itf"], opcode),
            )
            "jump" -> JumpInsnNode(
                opcode,
                originalNodes.getOrNull(operation.arguments.getValue("targetInstructionIndex").toInt()) as? LabelNode
                    ?: throw IllegalArgumentException("Jump replacement requires an existing label target."),
            )
            "ldc" -> LdcInsnNode(operation.arguments.getValue("operand"))
            else -> throw IllegalArgumentException("Unsupported replacement kind: $kind")
        }
        method.instructions.insert(anchor, replacement)
        method.instructions.remove(anchor)
        return replacement
    }

    private fun replaceConstant(
        anchor: AbstractInsnNode,
        operation: BytecodePatchOperation,
    ) {
        val ldc = anchor as? LdcInsnNode
            ?: throw IllegalArgumentException("replace_constant requires an LDC anchor.")
        val actualType = when (ldc.cst) {
            is String -> "string"
            is Int -> "int"
            else -> throw IllegalArgumentException("Unsupported replace_constant constant type.")
        }
        val valueType = operation.arguments["valueType"]?.lowercase() ?: actualType
        require(valueType == actualType) {
            "replace_constant must preserve the existing $actualType constant type."
        }
        ldc.cst = when (valueType.lowercase()) {
            "string" -> operation.arguments.getValue("value")
            "int" -> operation.arguments.getValue("value").toIntOrNull()
                ?: throw IllegalArgumentException("Integer constant value is invalid.")
            else -> throw IllegalArgumentException("Unsupported constant type: $valueType")
        }
    }

    private fun redirectCall(
        anchor: AbstractInsnNode,
        operation: BytecodePatchOperation,
    ) {
        val invoke = anchor as? MethodInsnNode
            ?: throw IllegalArgumentException("redirect_call requires a method-instruction anchor.")
        require(operation.arguments.getValue("descriptor") == invoke.desc) {
            "redirect_call cannot change the method descriptor; operand stack shape must remain unchanged."
        }
        invoke.owner = operation.arguments.getValue("owner")
        invoke.name = operation.arguments.getValue("name")
        invoke.itf = parseInterfaceFlag(operation.arguments["itf"], invoke.opcode, invoke.itf)
    }

    private fun patchLocalName(
        anchor: AbstractInsnNode,
        method: MethodNode,
        operation: BytecodePatchOperation,
        diagnostics: MutableList<String>,
    ) {
        localNameFailure(operation, anchor, method)?.let { throw IllegalArgumentException(it) }
        val slot = when (anchor) {
            is VarInsnNode -> anchor.`var`
            is IincInsnNode -> anchor.`var`
            else -> error("Validated local-variable anchor changed type.")
        }
        val index = method.instructions.indexOf(anchor)
        val visible = method.localVariables.orEmpty().single { variable ->
            variable.index == slot &&
                (operation.arguments["descriptor"] == null || variable.desc == operation.arguments["descriptor"]) &&
                method.instructions.indexOf(variable.start) <= index &&
                index < method.instructions.indexOf(variable.end)
        }
        visible.name = operation.arguments.getValue("name")
    }

    private fun patchLineTable(
        anchor: AbstractInsnNode,
        method: MethodNode,
        operation: BytecodePatchOperation,
        diagnostics: MutableList<String>,
    ) {
        lineTableFailure(operation, anchor, method)?.let { throw IllegalArgumentException(it) }
        lineNumberAt(anchor, method)!!.line = operation.arguments.getValue("line").toInt()
    }

    private fun rewriteJar(
        sourceBytes: ByteArray,
        className: String,
        patchedClassBytes: ByteArray,
    ): ByteArray {
        val target = className.replace('.', '/') + ".class"
        val inputEntries = inspectArchive(sourceBytes, target)
        val output = ByteArrayOutputStream()
        var expandedBytes = 0L
        var index = 0
        withZipFile(sourceBytes) { input ->
            val entries = input.entries()
            ZipOutputStream(output, StandardCharsets.UTF_8).use { jar ->
                while (true) {
                    if (!entries.hasMoreElements()) break
                    val entry = entries.nextElement()
                    val expected = inputEntries.getOrNull(index)
                        ?: throw IllegalArgumentException("Archive entry list changed during patch publication.")
                    require(entry.name == expected.name && entry.method == expected.method) {
                        "Archive entry metadata changed during patch publication at ${entry.name}."
                    }
                    val originalBytes = input.getInputStream(entry).use { readEntryBounded(it, expandedBytes) }
                    expandedBytes += originalBytes.size
                    require(sha256(originalBytes) == expected.sha256) {
                        "Archive entry content changed during patch publication at ${entry.name}."
                    }
                    val isTarget = entry.name == target
                    val contents = if (isTarget) patchedClassBytes else originalBytes
                    require(contents.size.toLong() <= MAX_ENTRY_BYTES) {
                        "Output entry exceeds the uncompressed entry limit: ${entry.name}."
                    }
                    if (isTarget) {
                        expandedBytes += contents.size.toLong() - originalBytes.size
                    }
                    require(expandedBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        "Output archive exceeds the total uncompressed size limit."
                    }
                    jar.putNextEntry(copyZipEntry(entry, contents))
                    jar.write(contents)
                    jar.closeEntry()
                    index += 1
                }
            }
        }
        require(index == inputEntries.size) { "Archive entry list changed during patch publication." }
        val outputBytes = output.toByteArray()
        require(outputBytes.size.toLong() <= MAX_OUTPUT_ARCHIVE_BYTES) {
            "Output archive exceeds the compressed size limit."
        }
        val outputEntries = inspectArchive(outputBytes, target)
        require(outputEntries.size == inputEntries.size) { "Output archive entry count changed unexpectedly." }
        inputEntries.zip(outputEntries).forEach { (before, after) ->
            require(before.name == after.name && before.method == after.method) {
                "Output archive entry order or compression method changed at ${before.name}."
            }
            if (before.name == target) {
                require(after.sha256 == sha256(patchedClassBytes)) {
                    "Published target class does not match the prepared class bytes."
                }
            } else {
                require(before.size == after.size && before.sha256 == after.sha256) {
                    "Non-target archive entry changed during publication: ${before.name}."
                }
            }
        }
        return outputBytes
    }

    private fun inspectArchive(sourceBytes: ByteArray, target: String): List<ArchiveEntrySnapshot> {
        val entries = ArrayList<ArchiveEntrySnapshot>()
        val names = HashSet<String>()
        var expandedBytes = 0L
        var targetCount = 0
        withZipFile(sourceBytes) { zip ->
            val zipEntries = zip.entries()
            while (true) {
                if (!zipEntries.hasMoreElements()) break
                val entry = zipEntries.nextElement()
                require(entries.size < MAX_ARCHIVE_ENTRIES) { "Archive has too many entries to patch safely." }
                require(names.add(entry.name)) { "Duplicate archive entry is unsupported: ${entry.name}." }
                require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) {
                    "Unsupported ZIP compression method for ${entry.name}."
                }
                require(!isSignatureEntry(entry.name)) {
                    "Signed archives are unsupported because rewriting invalidates their signatures."
                }
                require(!isMultiReleaseOverride(entry.name, target)) {
                    "Multi-release override for $target is unsupported: ${entry.name}."
                }
                if (entry.name == target) targetCount += 1
                val contents = zip.getInputStream(entry).use { readEntryBounded(it, expandedBytes) }
                expandedBytes += contents.size
                entries += ArchiveEntrySnapshot(
                    name = entry.name,
                    size = contents.size.toLong(),
                    sha256 = sha256(contents),
                    method = entry.method,
                )
            }
        }
        require(targetCount == 1) {
            "Expected exactly one target class entry $target, found $targetCount."
        }
        return entries
    }

    private fun readEntryBounded(input: InputStream, expandedBytesBeforeEntry: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var entryBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            entryBytes += read
            require(entryBytes <= MAX_ENTRY_BYTES) { "Archive entry exceeds the uncompressed size limit." }
            require(expandedBytesBeforeEntry <= MAX_TOTAL_UNCOMPRESSED_BYTES - entryBytes) {
                "Archive exceeds the total uncompressed size limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun <T> withZipFile(sourceBytes: ByteArray, block: (ZipFile) -> T): T {
        val snapshot = Files.createTempFile("antares-patch-archive", ".zip")
        try {
            Files.write(snapshot, sourceBytes)
            return ZipFile(snapshot.toFile(), StandardCharsets.UTF_8).use(block)
        } finally {
            Files.deleteIfExists(snapshot)
        }
    }

    private fun copyZipEntry(source: ZipEntry, contents: ByteArray): ZipEntry = ZipEntry(source).also { copy ->
        copy.method = source.method
        if (copy.method == ZipEntry.STORED) {
            val crc = CRC32().apply { update(contents) }
            copy.size = contents.size.toLong()
            copy.compressedSize = contents.size.toLong()
            copy.crc = crc.value
        }
    }

    private fun isSignatureEntry(name: String): Boolean {
        val normalized = name.uppercase()
        if (!normalized.startsWith("META-INF/")) return false
        val fileName = normalized.substringAfterLast('/')
        return fileName.endsWith(".SF") || fileName.endsWith(".RSA") || fileName.endsWith(".DSA") ||
            fileName.endsWith(".EC") || fileName.startsWith("SIG-")
    }

    private fun isMultiReleaseOverride(name: String, target: String): Boolean {
        val prefix = "META-INF/versions/"
        if (!name.startsWith(prefix)) return false
        val versionAndPath = name.removePrefix(prefix)
        val separator = versionAndPath.indexOf('/')
        if (separator <= 0) return false
        val version = versionAndPath.substring(0, separator).toIntOrNull() ?: return false
        return version >= 9 && versionAndPath.substring(separator + 1) == target
    }

    private fun loadClass(project: net.evrt.antarelite.analysis.index.ProjectRecord, className: String): ClassNode =
        loadClass(archiveSnapshot(project).bytes, className)

    private fun loadClass(sourceBytes: ByteArray, className: String): ClassNode {
        val classBytes = readClassEntry(sourceBytes, className)
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, ClassReader.SKIP_FRAMES)
        require(classNode.name.replace('/', '.') == className.replace('/', '.')) {
            "Class not found: $className"
        }
        return classNode
    }

    private fun readClassEntry(sourceBytes: ByteArray, className: String): ByteArray {
        val target = className.replace('.', '/') + ".class"
        ZipInputStream(sourceBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == target) return zip.readBytes()
                zip.closeEntry()
            }
        }
        throw IllegalArgumentException("Class not found: $className")
    }

    private fun findMethod(classNode: ClassNode, methodName: String, descriptor: String): MethodNode =
        classNode.methods.firstOrNull { it.name == methodName && it.desc == descriptor }
            ?: throw IllegalArgumentException("Method not found: ${classNode.name.replace('/', '.')}#$methodName$descriptor")

    private fun requireIdentity(
        project: net.evrt.antarelite.analysis.index.ProjectRecord,
        source: ArchiveSnapshot,
        expected: String?,
    ) {
        require(source.sha256 == project.sha256) {
            "Project archive checksum mismatch: stored ${project.sha256}, actual ${source.sha256}."
        }
        if (expected != null) {
            require(expected == source.sha256) {
                "Input checksum mismatch: expected $expected but archive is ${source.sha256}."
            }
        }
    }

    private fun requireMethodIdentity(
        actual: String,
        expected: String?,
        label: String = "Method instruction fingerprint",
    ) {
        if (expected != null) {
            require(expected == actual) {
                "$label mismatch: expected $expected but actual is $actual."
            }
        }
    }

    private fun archiveSnapshot(project: net.evrt.antarelite.analysis.index.ProjectRecord): ArchiveSnapshot {
        val bytes = Files.readAllBytes(Path.of(project.archivePath))
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Source archive exceeds the patch input limit." }
        return ArchiveSnapshot(bytes, sha256(bytes))
    }

    /**
     * Render the instruction stream the same way `method_bytecode` does: label, line-number, and
     * frame pseudo-nodes are skipped but keep their index position, so an anchor index taken from
     * `method_bytecode` addresses the same node here.
     */
    private fun describeInstructions(instructions: Array<AbstractInsnNode>): List<String> =
        instructions.mapIndexedNotNull { index, instruction ->
            if (instruction is LabelNode || instruction is LineNumberNode ||
                instruction is org.objectweb.asm.tree.FrameNode
            ) {
                return@mapIndexedNotNull null
            }
            "$index: ${describeInstruction(instruction, instructions)}"
        }

    private fun describeInstruction(
        instruction: AbstractInsnNode,
        instructions: Array<AbstractInsnNode>,
    ): String {
        val operand = when (instruction) {
            is LdcInsnNode -> " ${formatConstant(instruction.cst)}"
            is IntInsnNode -> " ${instruction.operand}"
            is VarInsnNode -> " ${instruction.`var`}"
            is IincInsnNode -> " slot=${instruction.`var`} increment=${instruction.incr}"
            is TypeInsnNode -> " ${instruction.desc}"
            is FieldInsnNode -> " ${instruction.owner}.${instruction.name}:${instruction.desc}"
            is MethodInsnNode ->
                " ${instruction.owner}.${instruction.name}${instruction.desc} itf=${instruction.itf}"
            is InvokeDynamicInsnNode -> " ${instruction.name}${instruction.desc} " +
                "bootstrap=${formatConstant(instruction.bsm)} " +
                "arguments=[${instruction.bsmArgs.joinToString { formatConstant(it) }}]"
            is JumpInsnNode -> " -> ${targetIndexOf(instruction.label, instructions)}"
            is TableSwitchInsnNode -> {
                val cases = instruction.labels.mapIndexed { offset, label ->
                    "${instruction.min.toLong() + offset}->${targetIndexOf(label, instructions)}"
                }.joinToString()
                " cases=[$cases] default->${targetIndexOf(instruction.dflt, instructions)}"
            }
            is LookupSwitchInsnNode -> {
                val cases = instruction.keys.zip(instruction.labels).joinToString { (key, label) ->
                    "$key->${targetIndexOf(label, instructions)}"
                }
                " cases=[$cases] default->${targetIndexOf(instruction.dflt, instructions)}"
            }
            is MultiANewArrayInsnNode -> " ${instruction.desc} dimensions=${instruction.dims}"
            else -> ""
        }
        return opcodeName(instruction.opcode) + operand
    }

    private fun formatConstant(value: Any): String = when (value) {
        is String -> "\"${escapeString(value)}\""
        is Type -> "Type(${value.descriptor})"
        is Handle -> "Handle(tag=${value.tag},owner=${value.owner},name=${value.name}," +
            "descriptor=${value.desc},interface=${value.isInterface})"
        is ConstantDynamic -> "ConstantDynamic(name=${value.name},descriptor=${value.descriptor}," +
            "bootstrap=${formatConstant(value.bootstrapMethod)},arguments=[" +
            (0 until value.bootstrapMethodArgumentCount).joinToString { index ->
                formatConstant(value.getBootstrapMethodArgument(index))
            } + "])"
        is Float -> "Float(bits=0x${Integer.toHexString(value.toRawBits())})"
        is Double -> "Double(bits=0x${java.lang.Long.toHexString(value.toRawBits())})"
        is Long -> "Long($value)"
        is Int -> "Int($value)"
        else -> "${value::class.simpleName}($value)"
    }

    private fun escapeString(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }

    /**
     * Branch targets are reported as instruction indexes so they line up with `method_bytecode`.
     * Identity-hash labels were unstable across operations and forced callers to re-derive
     * targets from byte offsets.
     */
    private fun targetIndexOf(label: LabelNode, instructions: Array<AbstractInsnNode>): Int =
        instructions.indexOfFirst { it === label }

    /**
     * Diff two methods by longest common subsequence over instruction content. A positional diff
     * turned one deletion into a cascade of "replaced" entries, which is why a declared count of
     * one semantic change contradicted an array of fourteen entries.
     */
    private data class InstructionDiff(
        val changes: List<BytecodePatchInstructionChange>,
        val truncated: Boolean,
    )

    private fun diff(
        before: List<String>,
        after: List<String>,
        semanticJumpAnchors: Set<Int>,
    ): InstructionDiff {
        val matrixCells = (before.size.toLong() + 1L) * (after.size.toLong() + 1L)
        if (matrixCells > MAX_DIFF_CELLS) return InstructionDiff(emptyList(), truncated = true)
        val beforeText = before.map(::textOf)
        val afterText = after.map(::textOf)
        val common = longestCommonSubsequence(beforeText, afterText)
        val changes = mutableListOf<BytecodePatchInstructionChange>()
        var left = 0
        var right = 0
        common.forEach { (matchLeft, matchRight) ->
            changes += spanChanges(before, after, left, matchLeft, right, matchRight, semanticJumpAnchors)
            left = matchLeft + 1
            right = matchRight + 1
        }
        changes += spanChanges(before, after, left, before.size, right, after.size, semanticJumpAnchors)
        return InstructionDiff(changes, truncated = false)
    }

    /** Turn one unmatched span into deleted/inserted, or one replaced entry when both sides exist. */
    private fun spanChanges(
        before: List<String>,
        after: List<String>,
        left: Int,
        leftEnd: Int,
        right: Int,
        rightEnd: Int,
        semanticJumpAnchors: Set<Int>,
    ): List<BytecodePatchInstructionChange> {
        val removed = before.subList(left, leftEnd)
        val added = after.subList(right, rightEnd)
        if (removed.isEmpty() && added.isEmpty()) return emptyList()
        if (removed.isEmpty()) {
            return added.map { line ->
                BytecodePatchInstructionChange(kind = "inserted", after = line, category = "semantic")
            }
        }
        if (added.isEmpty()) {
            return removed.map { line ->
                BytecodePatchInstructionChange(kind = "deleted", before = line, category = "semantic")
            }
        }
        val paired = minOf(removed.size, added.size)
        val changes = mutableListOf<BytecodePatchInstructionChange>()
        for (offset in 0 until paired) {
            val old = removed[offset]
            val new = added[offset]
            changes += BytecodePatchInstructionChange(
                kind = "replaced",
                before = old,
                after = new,
                category = classifyChange(old, new, semanticJumpAnchors),
            )
        }
        removed.drop(paired).forEach { line ->
            changes += BytecodePatchInstructionChange(kind = "deleted", before = line, category = "semantic")
        }
        added.drop(paired).forEach { line ->
            changes += BytecodePatchInstructionChange(kind = "inserted", after = line, category = "semantic")
        }
        return changes
    }

    private fun longestCommonSubsequence(left: List<String>, right: List<String>): List<Pair<Int, Int>> {
        val rows = left.size
        val columns = right.size
        // Instructions are bounded per method, so the quadratic table stays small.
        val lengths = Array(rows + 1) { IntArray(columns + 1) }
        for (row in rows - 1 downTo 0) {
            for (column in columns - 1 downTo 0) {
                lengths[row][column] = if (left[row] == right[column]) {
                    lengths[row + 1][column + 1] + 1
                } else {
                    maxOf(lengths[row + 1][column], lengths[row][column + 1])
                }
            }
        }
        val matches = mutableListOf<Pair<Int, Int>>()
        var row = 0
        var column = 0
        while (row < rows && column < columns) {
            when {
                left[row] == right[column] -> {
                    matches += row to column
                    row += 1
                    column += 1
                }
                lengths[row + 1][column] >= lengths[row][column + 1] -> row += 1
                else -> column += 1
            }
        }
        return matches
    }

    /**
     * A branch whose mnemonic is unchanged and which differs only in its target index is a
     * structural rewrite, not a semantic edit. Reporting the split keeps a declared count from
     * contradicting the array when jumps are renumbered by an insert or delete.
     */
    private fun classifyChange(
        before: String?,
        after: String?,
        semanticJumpAnchors: Set<Int>,
    ): String {
        if (before == null || after == null) return "semantic"
        // An insert or delete renumbers every following instruction; a line that differs only in
        // its index prefix is the same instruction at a new position.
        if (textOf(before) == textOf(after)) return "structural"
        val beforeOpcode = before.substringAfter(": ").substringBefore(' ')
        val afterOpcode = after.substringAfter(": ").substringBefore(' ')
        val branchy = beforeOpcode.startsWith("IF") || beforeOpcode == "GOTO" ||
            beforeOpcode.endsWith("SWITCH") || beforeOpcode in setOf("JSR", "RET")
        val originalIndex = before.substringBefore(": ").toIntOrNull()
        return if (branchy && beforeOpcode == afterOpcode && originalIndex !in semanticJumpAnchors) {
            "structural"
        } else {
            "semantic"
        }
    }

    private fun operationChanges(operations: List<BytecodePatchPreviewOperation>) =
        operations.filter { operation ->
            operation.operation !in setOf("patch_local_name", "patch_line_table") &&
                operation.beforeEvidence != operation.afterEvidence
        }.map { operation ->
            BytecodePatchInstructionChange(
                kind = when {
                    operation.afterEvidence == null -> "deleted"
                    operation.beforeEvidence == null -> "inserted"
                    else -> "modified"
                },
                before = operation.beforeEvidence,
                after = operation.afterEvidence,
                category = "semantic",
            )
        }

    /** Instruction text with the leading `index: ` prefix removed. */
    private fun textOf(line: String): String = line.substringAfter(": ")

    /** Debug metadata present on the addressed method, surfaced before a preview id is minted. */
    private fun metadataAvailability(method: MethodNode): PatchMetadataAvailability =
        PatchMetadataAvailability(
            hasLocalVariableTable = method.localVariables.orEmpty().isNotEmpty(),
            localVariableCount = method.localVariables.orEmpty().size,
            hasLineNumberTable = method.instructions.toArray().any { it is LineNumberNode },
            lineNumberCount = method.instructions.toArray().count { it is LineNumberNode },
        )

    private fun describeDebugMetadata(method: MethodNode): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val indexes = method.instructions.toArray().withIndex().associate { it.value to it.index }
        method.instructions.toArray().filterIsInstance<LineNumberNode>().forEachIndexed { index, line ->
            result["line@${indexes[line.start] ?: -1}#$index"] = "line=${line.line}"
        }
        method.localVariables.orEmpty().forEachIndexed { index, local ->
            val start = indexes[local.start] ?: -1
            val end = indexes[local.end] ?: -1
            val key = "local@${local.index}:${local.desc}:$start:$end#$index"
            result[key] = "name=${local.name};signature=${local.signature.orEmpty()}"
        }
        return result
    }

    private fun diffDebugMetadata(
        before: Map<String, String>,
        after: Map<String, String>,
    ): List<BytecodePatchInstructionChange> = (before.keys + after.keys).distinct().sorted().mapNotNull { key ->
        val oldValue = before[key]
        val newValue = after[key]
        if (oldValue == newValue) return@mapNotNull null
        BytecodePatchInstructionChange(
            kind = "metadata-modified",
            before = oldValue?.let { "$key: $it" },
            after = newValue?.let { "$key: $it" },
            category = "debug",
        )
    }

    private data class ArchiveSnapshot(val bytes: ByteArray, val sha256: String)

    private data class ArchiveEntrySnapshot(
        val name: String,
        val size: Long,
        val sha256: String,
        val method: Int,
    )

    private fun stableId(input: String, request: BytecodePatchRequest, methodHash: String): String =
        sha256("$input|${request.className}|${request.methodName}|${request.methodDescriptor}|$methodHash|${request.operations}".toByteArray())

    private fun stablePreviewId(
        projectId: String,
        sourceHash: String,
        methodHash: String,
        debugHash: String,
        patchedClassHash: String,
        canonicalRequestHash: String,
    ): String = sha256(
        listOf(
            BytecodePatchCatalog.ENGINE_VERSION,
            BytecodePatchCatalog.SCHEMA_VERSION.toString(),
            projectId,
            sourceHash,
            methodHash,
            debugHash,
            canonicalRequestHash,
            patchedClassHash,
        ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8),
    )

    private fun canonicalRequest(request: BytecodePatchRequest): String = buildJsonObject {
        put("schemaVersion", request.schemaVersion)
        put("className", request.className)
        put("methodName", request.methodName)
        put("methodDescriptor", request.methodDescriptor)
        put("inputSha256", request.inputSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("methodInstructionSha256", request.methodInstructionSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("debugMetadataSha256", request.debugMetadataSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("idempotencyKey", request.idempotencyKey?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "operations",
            JsonArray(
                request.operations.map { operation ->
                    buildJsonObject {
                        put("operation", operation.operation)
                        put("anchorInstructionIndex", operation.anchorInstructionIndex)
                        put("anchorOpcode", operation.anchorOpcode?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "arguments",
                            buildJsonObject {
                                operation.arguments.toSortedMap().forEach { (key, value) -> put(key, value) }
                            },
                        )
                    }
                },
            ),
        )
    }.toString()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun opcodeValue(name: String): Int =
        Printer.OPCODES.indexOf(name.uppercase()).also {
            require(it >= 0) { "Unsupported opcode: $name" }
        }

    private fun opcodeName(opcode: Int): String =
        if (opcode in Printer.OPCODES.indices) Printer.OPCODES[opcode] else "UNKNOWN"

    companion object {
        private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
        private const val MAX_OUTPUT_ARCHIVE_BYTES = 256L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MAX_DIFF_CELLS = 2_000_000L
        private val DEBUG_OPERATIONS = setOf("patch_local_name", "patch_line_table")
        private val DESTRUCTIVE_INSTRUCTION_OPERATIONS = setOf("delete_insn", "replace_insn")
        private val SIMPLE_OPCODES =
            (Opcodes.NOP..Opcodes.DCONST_1).toSet() +
                (Opcodes.IALOAD..Opcodes.SALOAD).toSet() +
                (Opcodes.IASTORE..Opcodes.SASTORE).toSet() +
                (Opcodes.POP..Opcodes.SWAP).toSet() +
                (Opcodes.IADD..Opcodes.LXOR).toSet() +
                (Opcodes.I2L..Opcodes.DCMPG).toSet() +
                (Opcodes.IRETURN..Opcodes.RETURN).toSet() +
                setOf(Opcodes.ARRAYLENGTH, Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT)
        private val VARIABLE_OPCODES =
            (Opcodes.ILOAD..Opcodes.ALOAD).toSet() + (Opcodes.ISTORE..Opcodes.ASTORE).toSet()
        private val WIDE_VARIABLE_OPCODES =
            setOf(Opcodes.LLOAD, Opcodes.DLOAD, Opcodes.LSTORE, Opcodes.DSTORE)
        private val TYPE_OPCODES = setOf(Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.CHECKCAST, Opcodes.INSTANCEOF)
        private val INVOCATION_OPCODES =
            (Opcodes.INVOKEVIRTUAL..Opcodes.INVOKEINTERFACE).toSet()
        private val JUMP_OPCODES =
            (Opcodes.IFEQ..Opcodes.GOTO).toSet() + setOf(Opcodes.IFNULL, Opcodes.IFNONNULL)
    }
}
