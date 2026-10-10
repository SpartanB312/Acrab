package net.evrt.antarelite.analysis.evidence

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.*

internal data class OperandValue(
    val basic: BasicValue,
    val producers: Set<Int> = emptySet(),
    val origins: Set<Int> = emptySet(),
    val parameterLocals: Set<Int> = emptySet(),
    val truncated: Boolean = false,
) : Value {
    override fun getSize(): Int = basic.size
}

internal class OperandInterpreter(private val indexes: Map<AbstractInsnNode, Int>) : Interpreter<OperandValue>(ASM9) {
    private val basic = BasicInterpreter()
    override fun newValue(type: Type?): OperandValue? = basic.newValue(type)?.let { OperandValue(it) }
    override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): OperandValue =
        OperandValue(basic.newValue(type), parameterLocals = setOf(local))

    private fun produced(insn: AbstractInsnNode, type: BasicValue, values: List<OperandValue> = emptyList()): OperandValue {
        val index = indexes.getValue(insn)
        val origins = values.flatMap { it.origins + it.producers }.toSet() + index
        return OperandValue(type, setOf(index), origins.sorted().take(MAX_ORIGINS).toSet(),
            values.flatMap { it.parameterLocals }.toSet(),
            values.any { it.truncated } || origins.size > MAX_ORIGINS)
    }

    override fun newOperation(insn: AbstractInsnNode): OperandValue = produced(insn, basic.newOperation(insn))
    override fun copyOperation(insn: AbstractInsnNode, value: OperandValue): OperandValue {
        basic.copyOperation(insn, value.basic)
        return if (insn.opcode in ILOAD..ALOAD || insn.opcode in ISTORE..ASTORE)
            produced(insn, value.basic, listOf(value)) else value
    }
    override fun unaryOperation(insn: AbstractInsnNode, value: OperandValue): OperandValue? =
        basic.unaryOperation(insn, value.basic)?.let { produced(insn, it, listOf(value)) }
    override fun binaryOperation(insn: AbstractInsnNode, left: OperandValue, right: OperandValue): OperandValue? =
        basic.binaryOperation(insn, left.basic, right.basic)?.let { produced(insn, it, listOf(left, right)) }
    override fun ternaryOperation(insn: AbstractInsnNode, a: OperandValue, b: OperandValue, c: OperandValue): OperandValue? =
        basic.ternaryOperation(insn, a.basic, b.basic, c.basic)?.let { produced(insn, it, listOf(a, b, c)) }
    override fun naryOperation(insn: AbstractInsnNode, values: List<OperandValue>): OperandValue? =
        basic.naryOperation(insn, values.map { it.basic })?.let { produced(insn, it, values) }
    override fun returnOperation(insn: AbstractInsnNode, value: OperandValue, expected: OperandValue) =
        basic.returnOperation(insn, value.basic, expected.basic)
    override fun merge(left: OperandValue, right: OperandValue): OperandValue {
        if (left == right) return left
        val producers = left.producers + right.producers
        val origins = left.origins + right.origins
        return OperandValue(basic.merge(left.basic, right.basic), producers.sorted().take(MAX_ORIGINS).toSet(),
            origins.sorted().take(MAX_ORIGINS).toSet(), left.parameterLocals + right.parameterLocals,
            left.truncated || right.truncated || producers.size > MAX_ORIGINS || origins.size > MAX_ORIGINS)
    }
    companion object { const val MAX_ORIGINS = 128 }
}

internal class OperandProvenance(owner: String, private val method: MethodNode) {
    val warnings = mutableListOf<String>()
    val frames: Array<Frame<OperandValue>?> = if (method.instructions.size() > 10_000 ||
        method.instructions.size().toLong() * (method.maxLocals + method.maxStack) > 2_000_000) {
        warnings += "Operand provenance skipped: analysis budget exceeded."
        arrayOfNulls(method.instructions.size())
    } else try {
        Analyzer(OperandInterpreter(method.instructions.toArray().withIndex().associate { it.value to it.index }))
            .analyze(owner, method)
    } catch (failure: AnalyzerException) {
        warnings += "Operand provenance failed: ${failure.message}"
        arrayOfNulls(method.instructions.size())
    }

    fun operands(index: Int, requestedSlots: Int? = null, numeric: NumericConstantAnalysis? = null): List<StackSliceInfo> {
        val frame = frames.getOrNull(index) ?: return emptyList()
        val insn = method.instructions[index]
        val available = (0 until frame.stackSize).map { frame.getStack(it) }
        val values = if (requestedSlots != null) {
            require(requestedSlots in 0..256) { "argumentSlots must be between 0 and 256." }
            val selected = mutableListOf<OperandValue>()
            var slots = 0
            for (value in available.asReversed()) {
                if (slots >= requestedSlots) break
                selected += value
                slots += value.size
            }
            require(slots == requestedSlots) { "argumentSlots exceeds the stack or splits a two-slot operand." }
            selected.asReversed()
        } else {
            fun width(depth: Int) = available.getOrNull(available.lastIndex - depth)?.size
            val count = when (insn.opcode) {
                DUP -> 1
                SWAP, DUP_X1 -> 2
                POP2, DUP2 -> if (width(0) == 2) 1 else 2
                DUP_X2 -> if (width(1) == 2) 2 else 3
                DUP2_X1 -> if (width(0) == 2) 2 else 3
                DUP2_X2 -> if (width(0) == 2) {
                    if (width(1) == 2) 2 else 3
                } else if (width(2) == 2) 3 else 4
                else -> consumedValues(insn)
            }
            available.takeLast(count)
        }
        val start = frame.stackSize - values.size
        return values.mapIndexed { ordinal, value ->
            val constant = numeric?.frames?.getOrNull(index)?.getStack(start + ordinal)?.constant
            val local = value.producers.singleOrNull()?.let { method.instructions[it] as? VarInsnNode }?.`var`
            StackSliceInfo(
                argumentOrdinal = ordinal,
                producerInstructionIndex = value.producers.singleOrNull(),
                valueKind = constant?.let { "Constant${it.javaClass.simpleName}" } ?: "Unknown",
                value = constant?.toString(),
                slotWidth = value.size,
                producerInstructionIndexes = value.producers.sorted(),
                originInstructionIndexes = value.origins.sorted(),
                parameterLocalIndexes = value.parameterLocals.sorted(),
                localIndex = local,
                expression = constant?.toString() ?: local?.let { "local[$it]" } ?: "operand[$ordinal]",
                provenanceTruncated = value.truncated,
            )
        }
    }
}

internal fun consumedValues(insn: AbstractInsnNode): Int = when (insn) {
    is MethodInsnNode -> Type.getArgumentTypes(insn.desc).size + if (insn.opcode == INVOKESTATIC) 0 else 1
    is InvokeDynamicInsnNode -> Type.getArgumentTypes(insn.desc).size
    is MultiANewArrayInsnNode -> insn.dims
    else -> when (insn.opcode) {
        in IASTORE..SASTORE -> 3
        in IALOAD..SALOAD, in IADD..LXOR, LCMP, FCMPL, FCMPG, DCMPL, DCMPG,
        in IF_ICMPEQ..IF_ACMPNE, PUTFIELD -> when (insn.opcode) {
            INEG, LNEG, FNEG, DNEG -> 1
            else -> 2
        }
        in ISTORE..ASTORE, in I2L..I2S, in IFEQ..IFLE, IFNULL, IFNONNULL,
        in IRETURN..ARETURN, GETFIELD, PUTSTATIC, ARRAYLENGTH, ATHROW, CHECKCAST,
        INSTANCEOF, NEWARRAY, ANEWARRAY, MONITORENTER, MONITOREXIT, TABLESWITCH, LOOKUPSWITCH -> 1
        POP -> 1
        else -> 0
    }
}

internal fun branchCondition(opcode: Int, operands: List<StackSliceInfo>): String {
    val left = operands.firstOrNull()?.expression ?: "operand[0]"
    val right = operands.getOrNull(1)?.expression ?: "operand[1]"
    return when (opcode) {
        GOTO -> "always"
        IFEQ -> "$left == 0"; IFNE -> "$left != 0"; IFLT -> "$left < 0"
        IFGE -> "$left >= 0"; IFGT -> "$left > 0"; IFLE -> "$left <= 0"
        IF_ICMPEQ, IF_ACMPEQ -> "$left == $right"; IF_ICMPNE, IF_ACMPNE -> "$left != $right"
        IF_ICMPLT -> "$left < $right"; IF_ICMPGE -> "$left >= $right"
        IF_ICMPGT -> "$left > $right"; IF_ICMPLE -> "$left <= $right"
        IFNULL -> "$left == null"; IFNONNULL -> "$left != null"
        else -> "unsupported"
    }
}
