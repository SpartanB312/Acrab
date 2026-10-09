package net.evrt.antarelite.analysis.evidence

import kotlinx.serialization.Serializable

@Serializable
data class MethodBytecodeResult(
    val projectId: String,
    val className: String,
    val methodName: String,
    val descriptor: String,
    val requestedDescriptor: String? = null,
    val exactMethodMatch: Boolean,
    val methodMatchKind: String,
    val access: List<String> = emptyList(),
    val synthetic: Boolean = false,
    val instructionCount: Int,
    val truncated: Boolean,
    val instructions: List<BytecodeInstructionInfo>,
    val labelIndexes: List<Int> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class BytecodeInstructionInfo(
    val index: Int,
    val opcode: Int,
    val opcodeName: String,
    val operand: String? = null,
    val jumpTargetIndex: Int? = null,
    val switchTargets: List<String> = emptyList(),
)

@Serializable
data class MethodCfgResult(
    val projectId: String,
    val className: String,
    val methodName: String,
    val descriptor: String,
    val requestedDescriptor: String? = null,
    val exactMethodMatch: Boolean,
    val methodMatchKind: String,
    val entryBlock: String?,
    val truncated: Boolean,
    val blocks: List<BasicBlockInfo>,
    val edges: List<CfgEdgeInfo>,
    val unreachableBlocks: List<String>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class BasicBlockInfo(
    val id: String,
    val startInstruction: Int,
    val endInstruction: Int,
    val instructionIndexes: List<Int>,
)

@Serializable
data class CfgEdgeInfo(
    val source: String,
    val target: String,
    val kind: String,
    val instructionIndex: Int? = null,
    val caseKey: String? = null,
)

@Serializable
data class MethodStackSliceResult(
    val projectId: String,
    val className: String,
    val methodName: String,
    val descriptor: String,
    val requestedDescriptor: String? = null,
    val exactMethodMatch: Boolean,
    val methodMatchKind: String,
    val targetInstructionIndex: Int,
    val targetOpcodeName: String,
    val requestedArgumentSlots: Int,
    val slices: List<StackSliceInfo>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class StackSliceInfo(
    val argumentOrdinal: Int,
    val producerInstructionIndex: Int? = null,
    val valueKind: String,
    val value: String? = null,
    val slotWidth: Int,
)

@Serializable
data class ConstantPropagationResult(
    val projectId: String,
    val className: String,
    val methodName: String,
    val descriptor: String,
    val requestedDescriptor: String? = null,
    val exactMethodMatch: Boolean,
    val methodMatchKind: String,
    val entryBlock: String?,
    val truncated: Boolean,
    val blocks: List<ConstantPropagationBlockInfo>,
    val branchFacts: List<ConstantPropagationBranchFactInfo>,
    val instructionFacts: List<InstructionConstantFactInfo> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ConstantPropagationBlockInfo(
    val blockId: String,
    val startInstruction: Int,
    val endInstruction: Int,
    val predecessors: List<String>,
    val successors: List<String>,
    val stackFacts: List<StackSliceInfo>,
    val localFacts: List<StackSliceInfo> = emptyList(),
)

@Serializable
data class InstructionConstantFactInfo(
    val instructionIndex: Int,
    val opcodeName: String,
    val valueKind: String,
    val value: String,
    val slotWidth: Int,
    val localIndex: Int? = null,
)

@Serializable
data class ConstantPropagationBranchFactInfo(
    val instructionIndex: Int,
    val blockId: String,
    val opcodeName: String,
    val condition: String,
    val knownOutcome: String,
)
