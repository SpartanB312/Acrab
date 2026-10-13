package net.evrt.acrab.analysis.obfuscation

import net.evrt.acrab.analysis.index.ClassInfo
import net.evrt.acrab.analysis.index.InvocationReference
import net.evrt.acrab.analysis.index.JvmIndexService
import net.evrt.acrab.analysis.index.MethodInfo
import net.evrt.acrab.analysis.index.ProjectIndex
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import java.security.MessageDigest
import java.util.jar.JarFile

class EvidenceFinderService(
    private val indexes: JvmIndexService,
) {
    /**
     * Bounded per-method structural cache keyed by archive checksum. The detector needs the
     * instruction stream, which the index does not retain.
     */
    fun find(
        projectId: String,
        categoryScope: String,
        limit: Int = DEFAULT_LIMIT,
        initialEvidenceId: String? = null,
        categoryFilter: List<String> = emptyList(),
    ): FinderResult {
        val index = indexes.ensureIndex(projectId)
        val scope = normalizeScope(categoryScope)
        val filter = categoryFilter.map(::normalizeCategory).toSet()
        val scopeApplies: (String) -> Boolean = { category ->
            (scope == SCOPE_ALL || scope == category) && (filter.isEmpty() || category in filter)
        }
        require(limit > 0) { "limit must be greater than zero, received $limit." }
        val boundedLimit = limit.coerceAtMost(MAX_LIMIT)
        val findings = mutableListOf<EvidenceFinding>()

        index.classes.forEach { clazz ->
            clazz.methods.forEach { method ->
                val metrics = method.metrics
                val controlMethod = if (scopeApplies(CATEGORY_OPAQUE) || scopeApplies(CATEGORY_FLATTENING)) {
                    methodNode(index, clazz, method)
                } else null
                val controlInstructions = controlMethod?.instructions?.toArray()?.toList().orEmpty()
                if (scopeApplies(CATEGORY_NUMBER)) {
                    if (metrics.numericConstantCount >= 8 && metrics.bitwiseCount >= 2) {
                        findings += finding(
                            category = CATEGORY_NUMBER,
                            subpattern = "dense-numeric-decoding",
                            className = clazz.name,
                            methodName = method.name,
                            descriptor = method.descriptor,
                            instructionIndexes = metrics.numericConstantIndexes + metrics.bitwiseIndexes,
                            evidence = listOf(
                                "numericConstants=${metrics.numericConstantCount}",
                                "bitwiseOperations=${metrics.bitwiseCount}",
                            ),
                            weight = 72,
                            confidence = "medium",
                        )
                    }
                }
                if (scopeApplies(CATEGORY_STRING)) {
                    if (method.stringConstantCount >= 6 && metrics.numericConstantCount >= 4) {
                        findings += finding(
                            category = CATEGORY_STRING,
                            subpattern = "fragmented-string-decoder",
                            className = clazz.name,
                            methodName = method.name,
                            descriptor = method.descriptor,
                            instructionIndexes = metrics.numericConstantIndexes,
                            evidence = listOf(
                                "stringConstants=${method.stringConstantCount}",
                                "numericConstants=${metrics.numericConstantCount}",
                            ),
                            weight = 68,
                            confidence = "medium",
                        )
                    }
                    val cipher = stringCipherFinding(index, clazz, method)
                    if (cipher != null) findings += cipher
                }
                if (scopeApplies(CATEGORY_OPAQUE)) {
                    if (isOpaquePredicateCandidate(metrics)) {
                        findings += finding(
                            category = CATEGORY_OPAQUE,
                            subpattern = "arithmetic-guard",
                            className = clazz.name,
                            methodName = method.name,
                            descriptor = method.descriptor,
                            instructionIndexes = metrics.branchIndexes,
                            evidence = listOf(
                                "branches=${metrics.branchCount}",
                                "bitwiseOperations=${metrics.bitwiseCount}",
                            ),
                            weight = 65,
                            confidence = "low",
                        )
                    }
                    if (controlMethod != null && hasRepeatedStaticBooleanGuard(controlMethod)) {
                        findings += finding(
                            category = CATEGORY_OPAQUE,
                            subpattern = "static-boolean-guard",
                            className = clazz.name,
                            methodName = method.name,
                            descriptor = method.descriptor,
                            instructionIndexes = metrics.branchIndexes,
                            evidence = listOf("repeatedStaticBooleanGuard=true", "branchDeadness=unproven"),
                            weight = 58,
                            confidence = "low",
                        )
                    }
                }
                if (scopeApplies(CATEGORY_FLATTENING)) {
                    if (hasLoopDispatcher(controlInstructions)) {
                        findings += finding(
                            category = CATEGORY_FLATTENING,
                            subpattern = "switch-dispatcher",
                            className = clazz.name,
                            methodName = method.name,
                            descriptor = method.descriptor,
                            instructionIndexes = metrics.switchIndexes + metrics.branchIndexes,
                            evidence = listOf(
                                "switches=${metrics.switchCount}",
                                "branches=${metrics.branchCount}",
                                "instructions=${method.instructionCount}",
                                "loopDispatcher=true",
                                "selectorUpdatedOnReturnPath=true",
                            ),
                            weight = 75,
                            confidence = "medium",
                        )
                    }
                }
            }
        }

        if (scopeApplies(CATEGORY_INVOKEDYNAMIC)) {
            index.invocations
                .filter { it.opcode == "INVOKEDYNAMIC" }
                .forEach { invocation ->
                    findings += invokedynamicFinding(projectId, index.summary.archiveSha256, invocation)
                }
        }

        val stable = findings
            .sortedWith(compareByDescending<EvidenceFinding> { it.weight }.thenBy { it.id })
            .let { all ->
                val cursor = initialEvidenceId?.let { cursor -> all.indexOfFirst { it.id == cursor } } ?: -1
                require(initialEvidenceId.isNullOrBlank() || cursor >= 0) {
                    "initialEvidenceId does not belong to this result."
                }
                if (cursor < 0) all else all.drop(cursor + 1)
            }
        val selected = stable.take(boundedLimit)
        return FinderResult(
            projectId = projectId,
            inputSha256 = index.summary.archiveSha256,
            categoryScope = if (filter.isEmpty()) scope else filter.sorted().joinToString(","),
            // Always the true total for the scope; `truncated` marks a bounded payload instead of
            // silently changing the reported total with the result cap.
            candidateCount = stable.size,
            truncated = stable.size > selected.size,
            limit = boundedLimit,
            scanComplete = true,
            findings = selected,
            categorySummary = stable.groupingBy { it.category }.eachCount(),
            subpatternSummary = stable.groupingBy { it.subpattern }.eachCount(),
        )
    }

    fun summary(
        projectId: String,
        limit: Int = DEFAULT_LIMIT,
        categoryFilter: List<String> = emptyList(),
    ): FinderResult = find(projectId, SCOPE_ALL, limit, categoryFilter = categoryFilter)

    /**
     * Detect a local string cipher: a `TABLESWITCH` selecting integer constant keys that feed
     * `IXOR`, in a method that also rebuilds a string through `String.<init>([C)` or `intern()`.
     * Detect the decoding structure rather than relying on ciphertext appearance.
     */
    private fun stringCipherFinding(
        index: ProjectIndex,
        clazz: ClassInfo,
        method: MethodInfo,
    ): EvidenceFinding? {
        val instructions = methodInstructions(index, clazz, method) ?: return null
        val tableSwitchIndexes = instructions.mapIndexedNotNull { position, instruction ->
            (instruction as? TableSwitchInsnNode)?.let { position }
        }
        if (tableSwitchIndexes.isEmpty()) return null

        val buildsString = instructions.any { instruction ->
            instruction is MethodInsnNode &&
                instruction.owner == "java/lang/String" &&
                (instruction.name == "intern" || instruction.name == "<init>")
        }
        if (!buildsString) return null

        val xorIndexes = instructions.mapIndexedNotNull { position, instruction ->
            if (instruction.opcode == Opcodes.IXOR) position else null
        }
        if (xorIndexes.isEmpty()) return null

        val xorKeys = tableSwitchIndexes.flatMap { switchIndex ->
            // Integer constants immediately following the dispatch table are the candidate keys.
            instructions.drop(switchIndex + 1)
                .take(KEY_SCAN_WINDOW)
                .mapNotNull { instruction -> integerConstant(instruction) }
        }.distinct()
        if (xorKeys.isEmpty()) return null

        val xorWindow = switchIndexesWithinWindow(tableSwitchIndexes, xorIndexes)
        if (!xorWindow) return null

        val evidence = buildList {
            add("tableSwitches=${tableSwitchIndexes.size}")
            add("xorKeys=${xorKeys.joinToString()}")
            add("xorOperations=${xorIndexes.size}")
            add("buildsString=String.<init>([C)/intern")
        }
        return finding(
            category = CATEGORY_STRING,
            subpattern = "constant-table-xor-decoder",
            className = clazz.name,
            methodName = method.name,
            descriptor = method.descriptor,
            instructionIndexes = (tableSwitchIndexes + xorIndexes).sorted(),
            evidence = evidence,
            weight = 84,
            confidence = "high",
            nextTools = listOf("method_bytecode", "cfg_constant_propagation"),
        )
    }

    private fun switchIndexesWithinWindow(switches: List<Int>, xorIndexes: List<Int>): Boolean =
        switches.any { switchIndex ->
            xorIndexes.any { xorIndex -> xorIndex in switchIndex..(switchIndex + KEY_SCAN_WINDOW) }
        }

    private fun integerConstant(instruction: AbstractInsnNode): Int? = when (instruction) {
        is IntInsnNode -> instruction.operand.takeIf { instruction.opcode == Opcodes.BIPUSH || instruction.opcode == Opcodes.SIPUSH }
        is LdcInsnNode -> (instruction.cst as? Int)
        else -> when (instruction.opcode) {
            Opcodes.ICONST_M1 -> -1
            in Opcodes.ICONST_0..Opcodes.ICONST_5 -> instruction.opcode - Opcodes.ICONST_0
            else -> null
        }
    }

    /** Read one method's instructions from the archive, bounded to the indexed entry. */
    private fun methodInstructions(
        index: ProjectIndex,
        clazz: ClassInfo,
        method: MethodInfo,
    ): List<AbstractInsnNode>? = methodNode(index, clazz, method)?.instructions?.toArray()?.toList()

    private fun methodNode(index: ProjectIndex, clazz: ClassInfo, method: MethodInfo): MethodNode? {
        val archive = indexes.archivePathFor(index.summary.projectId) ?: return null
        return runCatching {
            JarFile(archive.toFile()).use { jar ->
                val entry = jar.getJarEntry(clazz.internalName + ".class") ?: return null
                val classNode = ClassNode()
                org.objectweb.asm.ClassReader(jar.getInputStream(entry).readBytes())
                    .accept(classNode, org.objectweb.asm.ClassReader.SKIP_FRAMES)
                classNode.methods
                    .firstOrNull { it.name == method.name && it.desc == method.descriptor }
            }
        }.getOrNull()
    }

    /** Publish the finder/category vocabulary so clients can build a category filter. */
    fun catalog(): FinderCatalogResult = FinderCatalogResult(
        categories = CATEGORY_ORDER,
        subpatterns = SUBPATTERNS,
        finders = listOf(
            FinderCatalogEntry(
                tool = "find_number_secrets",
                categories = listOf(CATEGORY_NUMBER),
                subpatterns = listOf("dense-numeric-decoding"),
                description = "Dense numeric constants combined with bitwise arithmetic.",
            ),
            FinderCatalogEntry(
                tool = "find_string_transforms",
                categories = listOf(CATEGORY_STRING),
                subpatterns = listOf("fragmented-string-decoder", "constant-table-xor-decoder"),
                description = "Fragmented string building and constant-table XOR decoders.",
            ),
            FinderCatalogEntry(
                tool = "find_opaque_predicates",
                categories = listOf(CATEGORY_OPAQUE),
                subpatterns = listOf("arithmetic-guard", "static-boolean-guard"),
                description = "Arithmetic and repeated static boolean guard candidates; branch deadness is unproven.",
            ),
            FinderCatalogEntry(
                tool = "find_control_flow_flattening",
                categories = listOf(CATEGORY_FLATTENING),
                subpatterns = listOf("switch-dispatcher"),
                description = "Loop dispatchers whose local selector is updated on a path back to the switch.",
            ),
            FinderCatalogEntry(
                tool = "find_invokedynamic_dispatch",
                categories = listOf(CATEGORY_INVOKEDYNAMIC),
                subpatterns = listOf("jdk-known-bootstrap", "custom-bootstrap"),
                description = "Invokedynamic call sites split by bootstrap owner.",
            ),
        ),
        warnings = listOf(
            "Finder output is static advisory evidence and never a maliciousness or deobfuscation verdict.",
            "Use categoryFilter to restrict a summary; every accepted value is listed in `categories`.",
        ),
    )

    private fun invokedynamicFinding(
        projectId: String,
        inputSha256: String,
        invocation: InvocationReference,
    ): EvidenceFinding {
        val knownBootstrap = invocation.bootstrapOwner in setOf(
            "java/lang/invoke/StringConcatFactory",
            "java/lang/invoke/LambdaMetafactory",
        )
        return finding(
            category = CATEGORY_INVOKEDYNAMIC,
            subpattern = if (knownBootstrap) "jdk-known-bootstrap" else "custom-bootstrap",
            className = invocation.ownerClass,
            methodName = invocation.sourceMethod,
            descriptor = invocation.sourceDescriptor,
            instructionIndexes = listOf(invocation.instructionIndex),
            evidence = listOf(
                "site=${invocation.targetName}${invocation.targetDescriptor}",
                "bootstrapOwner=${invocation.bootstrapOwner.orEmpty()}",
            ),
            weight = if (knownBootstrap) 32 else 78,
            confidence = if (knownBootstrap) "low" else "medium",
            nextTools = listOf("method_bytecode", "method_stack_slice"),
        )
    }

    private fun isOpaquePredicateCandidate(metrics: net.evrt.acrab.analysis.index.MethodMetrics): Boolean =
        metrics.bitwiseCount >= 2 && metrics.branchCount >= 3

    private fun finding(
        category: String,
        subpattern: String,
        className: String,
        methodName: String? = null,
        descriptor: String? = null,
        instructionIndexes: List<Int> = emptyList(),
        evidence: List<String>,
        weight: Int,
        confidence: String,
        nextTools: List<String> = listOf("method_bytecode", "method_cfg"),
    ): EvidenceFinding = EvidenceFinding(
        id = stableId(category, subpattern, className, methodName.orEmpty(), instructionIndexes),
        category = category,
        subpattern = subpattern,
        className = className,
        methodName = methodName,
        descriptor = descriptor,
        instructionIndexes = instructionIndexes.distinct().sorted(),
        evidence = evidence,
        weight = weight,
        confidence = confidence,
        nextTools = nextTools,
    )

    private fun stableId(
        category: String,
        subpattern: String,
        className: String,
        methodName: String,
        indexes: List<Int>,
    ): String {
        val input = listOf(category, subpattern, className, methodName, indexes.sorted().joinToString(","))
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
        const val SCOPE_ALL = "all"
        const val CATEGORY_NUMBER = "number"
        const val CATEGORY_STRING = "string-transform"
        const val CATEGORY_OPAQUE = "opaque-guard"
        const val CATEGORY_FLATTENING = "flattening"
        const val CATEGORY_INVOKEDYNAMIC = "invokedynamic"

        /** Instruction window after a dispatch table that may hold the XOR key constants. */
        private const val KEY_SCAN_WINDOW = 64

        val CATEGORY_ORDER = listOf(
            CATEGORY_STRING,
            CATEGORY_NUMBER,
            CATEGORY_OPAQUE,
            CATEGORY_FLATTENING,
            CATEGORY_INVOKEDYNAMIC,
        )

        val SUBPATTERNS = listOf(
            "fragmented-string-decoder",
            "constant-table-xor-decoder",
            "dense-numeric-decoding",
            "arithmetic-guard",
            "switch-dispatcher",
            "dead-branch-guard",
            "static-boolean-guard",
            "jdk-known-bootstrap",
            "custom-bootstrap",
        )
    }
}

private fun normalizeScope(value: String): String = when (value.trim().lowercase()) {
    "", "all" -> "all"
    "number", "number-secrets" -> "number"
    "string", "string-transform", "string-transforms" -> "string-transform"
    "opaque", "opaque-guard", "opaque-predicates" -> "opaque-guard"
    "flattening", "control-flow-flattening" -> "flattening"
    "invokedynamic", "invokedynamic-dispatch" -> "invokedynamic"
    else -> throw IllegalArgumentException("Unknown category scope: $value")
}

/** Normalize one published category value, rejecting unknown vocabulary. */
private fun normalizeCategory(value: String): String = when (val normalized = normalizeScope(value)) {
    "all" -> throw IllegalArgumentException(
        "categoryFilter accepts indicator categories, not 'all'. Accepted values: " +
            EvidenceFinderService.CATEGORY_ORDER.joinToString(),
    )
    else -> normalized
}
