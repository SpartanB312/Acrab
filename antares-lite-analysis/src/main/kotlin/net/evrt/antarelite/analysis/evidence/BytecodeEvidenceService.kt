package net.evrt.antarelite.analysis.evidence

import net.evrt.antarelite.analysis.index.JvmIndexService
import net.evrt.antarelite.analysis.index.ProjectRepository
import net.evrt.antarelite.analysis.support.MethodResolutionException
import net.evrt.antarelite.analysis.support.MethodSelector
import net.evrt.antarelite.analysis.support.OpcodeNames
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

private fun opcodeName(opcode: Int): String = OpcodeNames.of(opcode)

class BytecodeEvidenceService(
    private val projects: ProjectRepository,
    private val indexes: JvmIndexService,
) {
    fun methodBytecode(
        projectId: String,
        className: String,
        methodName: String,
        descriptor: String?,
    ): MethodBytecodeResult {
        val target = loadMethod(projectId, className, methodName, descriptor)
        val instructions = target.method.instructions.toArray()
        val mapped = instructions.mapIndexedNotNull { index, instruction ->
            if (isPseudoInstruction(instruction)) null else instructionInfo(target.method, index, instruction)
        }
        return MethodBytecodeResult(
            projectId = projectId,
            inputSha256 = target.inputSha256,
            className = target.className,
            methodName = target.method.name,
            descriptor = target.method.desc,
            requestedDescriptor = target.requestedDescriptor,
            exactMethodMatch = target.resolved.exactDescriptorMatch,
            methodMatchKind = target.resolved.matchKind.name,
            access = target.accessFlags,
            synthetic = target.method.access and Opcodes.ACC_SYNTHETIC != 0,
            // Counts the raw ASM instruction list, matching the index space used by every
            // evidence tool. Pseudo-nodes are excluded from `instructions` but keep their indexes.
            instructionCount = instructions.size,
            truncated = mapped.size >= MAX_INSTRUCTIONS,
            instructions = mapped.take(MAX_INSTRUCTIONS),
            labelIndexes = instructions.mapIndexedNotNull { index, instruction ->
                index.takeIf { instruction is LabelNode }
            },
            warnings = listOf(
                "Instruction indexes are ASM instruction-list positions and therefore skip " +
                    "label/line/frame pseudo-nodes; use them consistently across evidence tools.",
            ),
        )
    }

    fun methodCfg(
        projectId: String,
        className: String,
        methodName: String,
        descriptor: String?,
    ): MethodCfgResult {
        val target = loadMethod(projectId, className, methodName, descriptor)
        val blocks = buildBlocks(target.method)
        val edges = buildEdges(target.method, blocks)
        val reachable = reachable(blocks.firstOrNull()?.id, edges)
        return MethodCfgResult(
            projectId = projectId,
            inputSha256 = target.inputSha256,
            className = target.className,
            methodName = target.method.name,
            descriptor = target.method.desc,
            requestedDescriptor = target.requestedDescriptor,
            exactMethodMatch = target.resolved.exactDescriptorMatch,
            methodMatchKind = target.resolved.matchKind.name,
            entryBlock = blocks.firstOrNull()?.id,
            truncated = blocks.size > MAX_BLOCKS,
            blocks = blocks.take(MAX_BLOCKS),
            edges = edges.filter { it.source in blocks.take(MAX_BLOCKS).map { block -> block.id }.toSet() },
            unreachableBlocks = blocks.drop(MAX_BLOCKS).map { it.id } + blocks.map { it.id }.filterNot { it in reachable },
            warnings = listOf("CFG blocks are computed from bounded static instruction adjacency and exception labels."),
        )
    }

    fun methodStackSlice(
        projectId: String,
        className: String,
        methodName: String,
        descriptor: String?,
        targetInstructionIndex: Int,
        argumentSlots: Int?,
    ): MethodStackSliceResult {
        val target = loadMethod(projectId, className, methodName, descriptor)
        val instructions = target.method.instructions.toArray()
        require(targetInstructionIndex in instructions.indices) {
            "Instruction index out of range: $targetInstructionIndex"
        }
        require(instructions[targetInstructionIndex].opcode >= 0) { "instructionIndex must identify an executable instruction." }
        if (argumentSlots != null) require(argumentSlots in 0..256) { "argumentSlots must be between 0 and 256." }
        val provenance = OperandProvenance(target.classNode.name, target.method)
        val numeric = NumericConstantAnalysis(target.classNode.name, target.method)
        val slices = provenance.operands(targetInstructionIndex, argumentSlots, numeric)
        val requested = argumentSlots ?: slices.sumOf { it.slotWidth }
        return MethodStackSliceResult(
            projectId = projectId,
            inputSha256 = target.inputSha256,
            className = target.className,
            methodName = target.method.name,
            descriptor = target.method.desc,
            requestedDescriptor = target.requestedDescriptor,
            exactMethodMatch = target.resolved.exactDescriptorMatch,
            methodMatchKind = target.resolved.matchKind.name,
            targetInstructionIndex = targetInstructionIndex,
            targetOpcodeName = opcodeName(instructions[targetInstructionIndex].opcode),
            requestedArgumentSlots = requested,
            slices = slices,
            warnings = provenance.warnings + numeric.warnings +
                "Operands are ordered bottom-to-top among consumed values; producer sets retain merged alternatives.",
            analysisStatus = if (provenance.warnings.isNotEmpty()) "unavailable"
                else if (provenance.frames[targetInstructionIndex] == null) "unreachable" else "complete",
            truncated = slices.any { it.provenanceTruncated },
        )
    }

    fun cfgConstantPropagation(
        projectId: String,
        className: String,
        methodName: String,
        descriptor: String?,
        argumentValues: Map<String, String> = emptyMap(),
        fieldValues: Map<String, String> = emptyMap(),
    ): ConstantPropagationResult {
        val target = loadMethod(projectId, className, methodName, descriptor)
        val blocks = buildBlocks(target.method)
        val edges = buildEdges(target.method, blocks)
        val analysis = NumericConstantAnalysis(target.classNode.name, target.method, argumentValues, fieldValues)
        val provenance = OperandProvenance(target.classNode.name, target.method)
        val visibleBlocks = blocks.take(MAX_BLOCKS)
        val instructionFacts = target.method.instructions.toArray().mapIndexedNotNull { index, instruction ->
            if (instruction.opcode < 0 || instruction is JumpInsnNode) return@mapIndexedNotNull null
            val frame = analysis.after(index) ?: return@mapIndexedNotNull null
            val localIndex = when (instruction) {
                is org.objectweb.asm.tree.VarInsnNode -> instruction.`var`.takeIf {
                    instruction.opcode in Opcodes.ISTORE..Opcodes.ASTORE
                }
                is org.objectweb.asm.tree.IincInsnNode -> instruction.`var`
                else -> null
            }
            val value = if (localIndex != null) frame.getLocal(localIndex)
                else if (frame.stackSize > 0) frame.getStack(frame.stackSize - 1) else null
            val constant = value?.constant ?: return@mapIndexedNotNull null
            InstructionConstantFactInfo(index, opcodeName(instruction.opcode),
                value.fact(0).valueKind, constant.toString(), value.size, localIndex)
        }
        val branchFacts = visibleBlocks.flatMap { block ->
            block.instructionIndexes.mapNotNull { index ->
                val instruction = target.method.instructions.get(index)
                if (instruction !is JumpInsnNode) return@mapNotNull null
                val operands = provenance.operands(index, numeric = analysis)
                val outcome = analysis.outcome(index)
                ConstantPropagationBranchFactInfo(
                    instructionIndex = index,
                    blockId = block.id,
                    opcodeName = opcodeName(instruction.opcode),
                    condition = branchCondition(instruction.opcode, operands),
                    knownOutcome = outcome,
                    operands = operands,
                    takenTargetIndex = methodIndexOf(instruction.label, target.method),
                    fallthroughTargetIndex = if (instruction.opcode == Opcodes.GOTO) null else
                        (index + 1 until target.method.instructions.size()).firstOrNull {
                            target.method.instructions[it].opcode >= 0
                        },
                    unknownReason = if (outcome == "unknown") {
                        if (analysis.frames.getOrNull(index) == null) "unreachable-or-analysis-unavailable"
                        else "nonconstant-or-conflicting-operands"
                    } else null,
                )
            }
        }
        return ConstantPropagationResult(
            projectId = projectId,
            inputSha256 = target.inputSha256,
            argumentValues = argumentValues,
            fieldValues = fieldValues,
            scope = if (argumentValues.isEmpty() && fieldValues.isEmpty()) "intraprocedural-normal-flow"
                else "conditional-on-supplied-assumptions",
            className = target.className,
            methodName = target.method.name,
            descriptor = target.method.desc,
            requestedDescriptor = target.requestedDescriptor,
            exactMethodMatch = target.resolved.exactDescriptorMatch,
            methodMatchKind = target.resolved.matchKind.name,
            entryBlock = blocks.firstOrNull()?.id,
            truncated = blocks.size > MAX_BLOCKS || instructionFacts.size > MAX_INSTRUCTIONS ||
                target.method.maxLocals > MAX_INSTRUCTIONS || target.method.maxStack > MAX_INSTRUCTIONS,
            blocks = visibleBlocks.map { block ->
                val frame = analysis.after(block.endInstruction)
                ConstantPropagationBlockInfo(
                    blockId = block.id,
                    startInstruction = block.startInstruction,
                    endInstruction = block.endInstruction,
                    predecessors = edges.filter { it.target == block.id }.map { it.source },
                    successors = edges.filter { it.source == block.id }.map { it.target },
                    stackFacts = frame?.let { state ->
                        (0 until minOf(state.stackSize, MAX_INSTRUCTIONS)).map { state.getStack(it).fact(it) }
                    }.orEmpty(),
                    localFacts = frame?.let { state ->
                        (0 until minOf(state.locals, MAX_INSTRUCTIONS)).map { state.getLocal(it).fact(it) }
                    }.orEmpty(),
                )
            },
            branchFacts = branchFacts,
            instructionFacts = instructionFacts.take(MAX_INSTRUCTIONS),
            warnings = analysis.warnings + provenance.warnings + "Numeric constants are propagated without executing target code; " +
                "unknown inputs and conflicting merges remain unknown. Facts describe normal control flow, " +
                "not runtime linking or proof that throwing instructions complete.",
        )
    }

    internal fun loadMethod(
        projectId: String,
        className: String,
        methodName: String,
        descriptor: String?,
    ): LoadedMethod {
        val project = projects.requireVerified(projectId)
        indexes.ensureIndex(projectId)
        JarFile(Path.of(project.archivePath).toFile()).use { jar ->
            val entry = jar.getJarEntry(className.replace('.', '/') + ".class")
                ?: throw IllegalArgumentException("Class not found in project $projectId: $className")
            val classNode = ClassNode()
            org.objectweb.asm.ClassReader(jar.getInputStream(entry).readBytes())
                .accept(classNode, org.objectweb.asm.ClassReader.SKIP_FRAMES)
            val resolved = MethodSelector.resolve(classNode, methodName, descriptor)
            return LoadedMethod(
                classNode = classNode,
                method = resolved.method,
                matchKind = resolved.matchKind,
                requestedDescriptor = resolved.requestedDescriptor,
                inputSha256 = project.sha256,
            )
        }
    }

    private fun instructionInfo(
        method: MethodNode,
        index: Int,
        instruction: AbstractInsnNode,
    ): BytecodeInstructionInfo? {
        val operand: String? = when (instruction) {
            is LdcInsnNode -> instruction.cst.toString()
            is IntInsnNode -> instruction.operand.toString()
            is org.objectweb.asm.tree.VarInsnNode -> instruction.`var`.toString()
            is org.objectweb.asm.tree.IincInsnNode -> "${instruction.`var`} ${instruction.incr}"
            is org.objectweb.asm.tree.TypeInsnNode -> instruction.desc
            is org.objectweb.asm.tree.FieldInsnNode -> "${instruction.owner}.${instruction.name}:${instruction.desc}"
            is org.objectweb.asm.tree.MethodInsnNode ->
                "${instruction.owner}.${instruction.name}${instruction.desc}"
            is org.objectweb.asm.tree.InvokeDynamicInsnNode -> "${instruction.name}${instruction.desc}"
            else -> null
        }
        val jumpTargetIndex: Int? = (instruction as? JumpInsnNode)?.let { jump -> methodIndexOf(jump.label, method) }
        val switchTargets: List<String> = when (instruction) {
            is TableSwitchInsnNode -> instruction.labels
                .mapIndexed { caseKey, label -> "${instruction.min + caseKey}->@${methodIndexOf(label, method)}" }
                .plus("default->@${methodIndexOf(instruction.dflt, method)}")
            is LookupSwitchInsnNode -> instruction.labels
                .mapIndexed { caseKey, label -> "${instruction.keys[caseKey]}->@${methodIndexOf(label, method)}" }
                .plus("default->@${methodIndexOf(instruction.dflt, method)}")
            else -> emptyList()
        }
        return BytecodeInstructionInfo(
            index = index,
            opcode = instruction.opcode,
            opcodeName = opcodeName(instruction.opcode),
            operand = operand,
            jumpTargetIndex = jumpTargetIndex,
            switchTargets = switchTargets,
        )
    }

    /** Resolve a label node to its instruction index, matching the indexes used across evidence tools. */
    private fun methodIndexOf(label: LabelNode, method: MethodNode): Int = method.instructions.indexOf(label)

    /**
     * Label, line-number, and stack-map-frame entries are ASM bookkeeping rather than JVM
     * instructions; they occupy list positions but must not be reported as opcodes.
     */
    private fun isPseudoInstruction(instruction: AbstractInsnNode): Boolean =
        instruction is LabelNode ||
            instruction is org.objectweb.asm.tree.LineNumberNode ||
            instruction is org.objectweb.asm.tree.FrameNode

    private fun buildBlocks(method: MethodNode): List<BasicBlockInfo> {
        val leaders = mutableSetOf(0)
        method.instructions.toArray().forEachIndexed { index, instruction ->
            if (instruction is LabelNode) leaders += index
            if (instruction is JumpInsnNode) {
                leaders += index + 1
                leaders += instructionOf(instruction.label, method)
            }
            if (instruction is TableSwitchInsnNode || instruction is LookupSwitchInsnNode ||
                instruction.opcode in Opcodes.IRETURN..Opcodes.RETURN || instruction.opcode == Opcodes.ATHROW) {
                leaders += index + 1
            }
        }
        val sortedLeaders = leaders.filter { it < method.instructions.size() }.sorted()
        return sortedLeaders.mapIndexed { position, start ->
            val end = (sortedLeaders.getOrNull(position + 1) ?: method.instructions.size()) - 1
            BasicBlockInfo(
                id = "block_$start",
                startInstruction = start,
                endInstruction = end.coerceAtLeast(start),
                instructionIndexes = (start..end).toList(),
            )
        }
    }

    private fun buildEdges(method: MethodNode, blocks: List<BasicBlockInfo>): List<CfgEdgeInfo> {
        val edges = mutableListOf<CfgEdgeInfo>()
        fun edge(block: BasicBlockInfo, label: LabelNode, kind: String, index: Int, key: String? = null) {
            val targetIndex = instructionOf(label, method)
            blocks.firstOrNull { targetIndex in it.startInstruction..it.endInstruction }?.let { target ->
                edges += CfgEdgeInfo(block.id, target.id, kind, index, key)
            }
        }
        blocks.forEach { block ->
            val next = blocks.firstOrNull { it.startInstruction == block.endInstruction + 1 }
            val lastIndex = block.instructionIndexes.lastOrNull { method.instructions[it].opcode >= 0 }
            val last = lastIndex?.let { method.instructions[it] }
            val fallsThrough = last !is TableSwitchInsnNode && last !is LookupSwitchInsnNode &&
                last?.opcode != Opcodes.GOTO && last?.opcode != Opcodes.ATHROW &&
                last?.opcode !in Opcodes.IRETURN..Opcodes.RETURN
            if (next != null && fallsThrough) edges += CfgEdgeInfo(block.id, next.id, "fallthrough")
            block.instructionIndexes.forEach { index ->
                val instruction = method.instructions.get(index)
                when (instruction) {
                    is JumpInsnNode -> edge(block, instruction.label, "jump", index)
                    is TableSwitchInsnNode -> {
                        instruction.labels.forEachIndexed { offset, label ->
                            edge(block, label, "switch", index, (instruction.min + offset).toString())
                        }
                        edge(block, instruction.dflt, "switch", index, "default")
                    }
                    is LookupSwitchInsnNode -> {
                        instruction.labels.forEachIndexed { offset, label ->
                            edge(block, label, "switch", index, instruction.keys[offset].toString())
                        }
                        edge(block, instruction.dflt, "switch", index, "default")
                    }
                }
            }
            method.tryCatchBlocks.orEmpty().forEach { handler ->
                if (block.instructionIndexes.any { it >= instructionOf(handler.start, method) &&
                        it < instructionOf(handler.end, method) && method.instructions[it].opcode >= 0 }) {
                    edge(block, handler.handler, "exception", block.startInstruction, handler.type ?: "any")
                }
            }
        }
        return edges.distinctBy { listOf(it.source, it.target, it.kind, it.instructionIndex, it.caseKey) }
    }

    private fun reachable(entry: String?, edges: List<CfgEdgeInfo>): Set<String> {
        if (entry == null) return emptySet()
        val adjacency = edges.groupBy { it.source }
        val visited = mutableSetOf(entry)
        val pending = ArrayDeque(listOf(entry))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            adjacency[current].orEmpty().forEach { edge ->
                if (visited.add(edge.target)) pending.addLast(edge.target)
            }
        }
        return visited
    }

    private fun instructionOf(label: LabelNode, method: MethodNode): Int =
        method.instructions.indexOf(label)

    companion object {
        private const val MAX_INSTRUCTIONS = 500
        private const val MAX_BLOCKS = 100
    }
}

internal data class LoadedMethod(
    val classNode: ClassNode,
    val method: MethodNode,
    val matchKind: net.evrt.antarelite.analysis.support.MethodMatchKind,
    val requestedDescriptor: String?,
    val inputSha256: String,
) {
    /** Dot-separated class name, so responses describe the class that was actually addressed. */
    val className: String get() = classNode.name.replace('/', '.')

    val resolved: net.evrt.antarelite.analysis.support.ResolvedMethod
        get() = net.evrt.antarelite.analysis.support.ResolvedMethod(
            className = className,
            method = method,
            matchKind = matchKind,
            requestedDescriptor = requestedDescriptor,
        )

    val accessFlags: List<String>
        get() = buildList {
            val access = method.access
            if (access and Opcodes.ACC_PUBLIC != 0) add("public")
            if (access and Opcodes.ACC_PRIVATE != 0) add("private")
            if (access and Opcodes.ACC_PROTECTED != 0) add("protected")
            if (access and Opcodes.ACC_STATIC != 0) add("static")
            if (access and Opcodes.ACC_FINAL != 0) add("final")
            if (access and Opcodes.ACC_SYNCHRONIZED != 0) add("synchronized")
            if (access and Opcodes.ACC_ABSTRACT != 0) add("abstract")
            if (access and Opcodes.ACC_NATIVE != 0) add("native")
            if (access and Opcodes.ACC_BRIDGE != 0) add("bridge")
            if (access and Opcodes.ACC_SYNTHETIC != 0) add("synthetic")
        }
}
