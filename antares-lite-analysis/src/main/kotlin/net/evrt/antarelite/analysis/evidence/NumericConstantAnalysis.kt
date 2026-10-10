package net.evrt.antarelite.analysis.evidence

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.*

internal data class NumericValue(val basic: BasicValue, val constant: Number? = null) : Value {
    override fun getSize(): Int = basic.size
}

/** A flat constant lattice: conflicting values merge to unknown, never to a guessed value. */
internal class NumericInterpreter(
    private val parameters: Map<Int, Number> = emptyMap(),
    private val fields: Map<String, Number> = emptyMap(),
) : Interpreter<NumericValue>(ASM9) {
    private val basic = BasicInterpreter()

    override fun newValue(type: Type?): NumericValue? = basic.newValue(type)?.let { NumericValue(it) }
    override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): NumericValue =
        NumericValue(basic.newValue(type), parameters[local])

    override fun newOperation(insn: AbstractInsnNode): NumericValue {
        val constant: Number? = when (insn.opcode) {
            ICONST_M1 -> -1
            in ICONST_0..ICONST_5 -> insn.opcode - ICONST_0
            LCONST_0, LCONST_1 -> (insn.opcode - LCONST_0).toLong()
            in FCONST_0..FCONST_2 -> (insn.opcode - FCONST_0).toFloat()
            DCONST_0, DCONST_1 -> (insn.opcode - DCONST_0).toDouble()
            BIPUSH, SIPUSH -> (insn as IntInsnNode).operand
            LDC -> (insn as LdcInsnNode).cst as? Number
            GETSTATIC -> (insn as FieldInsnNode).let { fields["${it.owner}.${it.name}:${it.desc}"] }
            else -> null
        }
        return NumericValue(basic.newOperation(insn), constant)
    }

    override fun copyOperation(insn: AbstractInsnNode, value: NumericValue): NumericValue {
        basic.copyOperation(insn, value.basic)
        return value
    }

    override fun unaryOperation(insn: AbstractInsnNode, value: NumericValue): NumericValue? {
        val result = basic.unaryOperation(insn, value.basic) ?: return null
        val a = value.constant ?: return NumericValue(result)
        val constant: Number? = when (insn.opcode) {
            INEG -> -a.toInt(); LNEG -> -a.toLong(); FNEG -> -a.toFloat(); DNEG -> -a.toDouble()
            IINC -> a.toInt() + (insn as IincInsnNode).incr
            I2L, F2L, D2L -> a.toLong()
            I2F, L2F, D2F -> a.toFloat()
            I2D, L2D, F2D -> a.toDouble()
            L2I, F2I, D2I -> a.toInt()
            I2B -> a.toByte().toInt(); I2C -> a.toInt() and 0xffff; I2S -> a.toShort().toInt()
            else -> null
        }
        return NumericValue(result, constant)
    }

    override fun binaryOperation(insn: AbstractInsnNode, left: NumericValue, right: NumericValue): NumericValue? {
        val result = basic.binaryOperation(insn, left.basic, right.basic) ?: return null
        val a = left.constant ?: return NumericValue(result)
        val b = right.constant ?: return NumericValue(result)
        val constant: Number? = try {
            when (insn.opcode) {
                IADD -> a.toInt() + b.toInt(); ISUB -> a.toInt() - b.toInt()
                IMUL -> a.toInt() * b.toInt(); IDIV -> a.toInt() / b.toInt(); IREM -> a.toInt() % b.toInt()
                IAND -> a.toInt() and b.toInt(); IOR -> a.toInt() or b.toInt(); IXOR -> a.toInt() xor b.toInt()
                ISHL -> a.toInt() shl b.toInt(); ISHR -> a.toInt() shr b.toInt(); IUSHR -> a.toInt() ushr b.toInt()
                LADD -> a.toLong() + b.toLong(); LSUB -> a.toLong() - b.toLong()
                LMUL -> a.toLong() * b.toLong(); LDIV -> a.toLong() / b.toLong(); LREM -> a.toLong() % b.toLong()
                LAND -> a.toLong() and b.toLong(); LOR -> a.toLong() or b.toLong(); LXOR -> a.toLong() xor b.toLong()
                LSHL -> a.toLong() shl b.toInt(); LSHR -> a.toLong() shr b.toInt(); LUSHR -> a.toLong() ushr b.toInt()
                FADD -> a.toFloat() + b.toFloat(); FSUB -> a.toFloat() - b.toFloat()
                FMUL -> a.toFloat() * b.toFloat(); FDIV -> a.toFloat() / b.toFloat(); FREM -> a.toFloat() % b.toFloat()
                DADD -> a.toDouble() + b.toDouble(); DSUB -> a.toDouble() - b.toDouble()
                DMUL -> a.toDouble() * b.toDouble(); DDIV -> a.toDouble() / b.toDouble(); DREM -> a.toDouble() % b.toDouble()
                LCMP -> a.toLong().compareTo(b.toLong())
                FCMPL, FCMPG, DCMPL, DCMPG -> {
                    val x = a.toDouble(); val y = b.toDouble()
                    if (x.isNaN() || y.isNaN()) {
                        if (insn.opcode == FCMPL || insn.opcode == DCMPL) -1 else 1
                    } else if (x < y) -1 else if (x > y) 1 else 0
                }
                else -> null
            }
        } catch (_: ArithmeticException) {
            null // A possible throwing operation is not evidence of a normal constant result.
        }
        return NumericValue(result, constant)
    }

    override fun ternaryOperation(insn: AbstractInsnNode, a: NumericValue, b: NumericValue, c: NumericValue): NumericValue? =
        basic.ternaryOperation(insn, a.basic, b.basic, c.basic)?.let { NumericValue(it) }

    override fun naryOperation(insn: AbstractInsnNode, values: List<NumericValue>): NumericValue? =
        basic.naryOperation(insn, values.map { it.basic })?.let { NumericValue(it) }

    override fun returnOperation(insn: AbstractInsnNode, value: NumericValue, expected: NumericValue) {
        basic.returnOperation(insn, value.basic, expected.basic)
    }

    override fun merge(left: NumericValue, right: NumericValue): NumericValue =
        if (left == right) left else NumericValue(basic.merge(left.basic, right.basic))
}

internal class NumericConstantAnalysis(owner: String, private val method: MethodNode,
    arguments: Map<String, String> = emptyMap(), fields: Map<String, String> = emptyMap()) {
    val warnings = mutableListOf<String>()
    private val interpreter = NumericInterpreter(parameterConstants(method, arguments), fields.mapValues { (key, value) ->
        require(key.contains(':')) { "fieldValues keys must be owner/name.field:descriptor." }
        numericLiteral(Type.getType(key.substringAfterLast(':')), value)
    })
    val frames: Array<Frame<NumericValue>?> = if (method.instructions.size() > 10_000 ||
        method.instructions.size().toLong() * (method.maxLocals + method.maxStack) > 2_000_000) {
        warnings += "Numeric analysis skipped: the method exceeds the instruction or frame analysis budget."
        arrayOfNulls(method.instructions.size())
    } else try {
        Analyzer(interpreter).analyze(owner, method)
    } catch (failure: AnalyzerException) {
        warnings += "Numeric analysis failed: ${failure.message}"
        arrayOfNulls(method.instructions.size())
    }

    fun after(index: Int): Frame<NumericValue>? {
        val frame = frames.getOrNull(index) ?: return null
        return Frame(frame).also {
            if (method.instructions[index].opcode >= 0) it.execute(method.instructions[index], interpreter)
        }
    }

    fun outcome(index: Int): String {
        val insn = method.instructions[index]
        if (insn.opcode == GOTO) return "taken"
        val frame = frames.getOrNull(index) ?: return "unknown"
        fun number(depth: Int): Int? = if (frame.stackSize > depth) {
            frame.getStack(frame.stackSize - 1 - depth).constant?.toInt()
        } else null
        val a = number(0) ?: return "unknown"
        val value = when (insn.opcode) {
            IFEQ -> a == 0; IFNE -> a != 0; IFLT -> a < 0; IFGE -> a >= 0; IFGT -> a > 0; IFLE -> a <= 0
            in IF_ICMPEQ..IF_ICMPLE -> {
                val b = number(1) ?: return "unknown"
                when (insn.opcode) {
                    IF_ICMPEQ -> b == a; IF_ICMPNE -> b != a; IF_ICMPLT -> b < a
                    IF_ICMPGE -> b >= a; IF_ICMPGT -> b > a; else -> b <= a
                }
            }
            else -> return "unknown"
        }
        return if (value) "taken" else "not-taken"
    }
}

internal fun numericLiteral(type: Type, value: String): Number = when (type.sort) {
    Type.BOOLEAN -> value.toInt().also { require(it in 0..1) { "Boolean assumptions must be 0 or 1." } }
    Type.BYTE -> value.toByte().toInt()
    Type.CHAR -> value.toInt().also { require(it in 0..65535) { "Char assumption out of range." } }
    Type.SHORT -> value.toShort().toInt()
    Type.INT -> value.toInt()
    Type.LONG -> value.toLong()
    Type.FLOAT -> value.toFloat().also { require(it.isFinite()) { "Assumptions must be finite." } }
    Type.DOUBLE -> value.toDouble().also { require(it.isFinite()) { "Assumptions must be finite." } }
    else -> throw IllegalArgumentException("Only primitive numeric argument and static-field assumptions are supported.")
}

private fun parameterConstants(method: MethodNode, arguments: Map<String, String>): Map<Int, Number> {
    val types = Type.getArgumentTypes(method.desc)
    require(arguments.size <= types.size) { "Too many argumentValues." }
    var slot = if (method.access and ACC_STATIC != 0) 0 else 1
    val slots = types.map { type -> slot.also { slot += type.size } }
    return arguments.map { (key, value) ->
        val ordinal = key.toIntOrNull()
        require(ordinal != null && ordinal in types.indices && key == ordinal.toString()) {
            "argumentValues keys must be zero-based formal argument ordinals."
        }
        slots[ordinal] to numericLiteral(types[ordinal], value)
    }.toMap()
}

internal fun NumericValue.fact(ordinal: Int): StackSliceInfo = StackSliceInfo(
    argumentOrdinal = ordinal,
    valueKind = constant?.let { "Constant${it.javaClass.simpleName}" } ?: "Unknown",
    value = constant?.toString(),
    slotWidth = size,
)
