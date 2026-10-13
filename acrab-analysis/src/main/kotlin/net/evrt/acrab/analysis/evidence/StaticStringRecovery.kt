package net.evrt.acrab.analysis.evidence

import kotlinx.serialization.Serializable
import net.evrt.acrab.analysis.index.JvmIndexService
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*

@Serializable
data class RecoveredString(val fieldName: String, val arrayIndex: Int?, val value: String)

@Serializable
data class StringRecoveryResult(
    val projectId: String,
    val inputSha256: String,
    val className: String,
    val methodDescriptor: String = "()V",
    val status: String,
    val strings: List<RecoveredString> = emptyList(),
    val cyclicKeys: List<Int> = emptyList(),
    val sourceInstructionIndexes: List<Int> = emptyList(),
    val steps: Int = 0,
    val scope: String = "bounded-static-string-initializer",
    val diagnostics: List<String> = emptyList(),
)

/** Models a deliberately restricted string initializer as data; it never loads target classes. */
class StaticStringRecovery(private val evidence: BytecodeEvidenceService, private val indexes: JvmIndexService) {
    private class StringBox(var value: String? = null)

    fun recover(projectId: String, className: String): StringRecoveryResult {
        val target = evidence.loadMethod(projectId, className, "<clinit>", "()V")
        val insns = target.method.instructions.toArray()
        var steps = 0
        return try {
            require(insns.size <= 10_000) { "Initializer exceeds analysis budget." }
            val switch = insns.filterIsInstance<TableSwitchInsnNode>().singleOrNull()
                ?: throw IllegalArgumentException("Only one cyclic XOR key switch is supported.")
            val executable = insns.filter { it.opcode >= 0 }
            val switchPosition = executable.indexOf(switch)
            require(switchPosition >= 2 && executable[switchPosition - 1].opcode == IREM) { "Switch is not a cyclic key selector." }
            val period = integer(executable[switchPosition - 2])
            require(period in 1..256 && switch.min == 0 && switch.max == period - 2) { "Unsupported cyclic key range." }
            require(insns.any { it.opcode == CALOAD } && insns.any { it.opcode == CASTORE } && insns.any { it.opcode == IXOR }) {
                "No supported char-array XOR loop."
            }
            fun nextExecutable(start: Int): Int = (start until insns.size).first { insns[it].opcode >= 0 }
            val keyLabels = switch.labels + switch.dflt
            val keys = keyLabels.map { label ->
                val i = nextExecutable(target.method.instructions.indexOf(label))
                val value = integer(insns[i])
                val next = insns[nextExecutable(i + 1)]
                val xor = if (next is JumpInsnNode && next.opcode == GOTO)
                    insns[nextExecutable(target.method.instructions.indexOf(next.label))] else next
                require(xor.opcode == IXOR) { "A key case does not feed XOR directly." }
                value
            }
            val stack = mutableListOf<Any>()
            val locals = mutableMapOf<Int, Any>()
            val assigned = linkedMapOf<String, Any>()
            fun pop(): Any = stack.removeAt(stack.lastIndex)
            fun int(): Int = pop() as Int
            fun text(value: Any): String = when (value) {
                is String -> value
                is StringBox -> requireNotNull(value.value) { "Uninitialized modeled String." }
                else -> throw IllegalArgumentException("Unsupported string input.")
            }
            var pc = 0
            var returned = false
            var allocatedCharacters = 0L
            while (pc in insns.indices) {
                require(++steps <= 100_000) { "Static recovery step budget exceeded." }
                val insn = insns[pc]
                var next = pc + 1
                when (insn.opcode) {
                    -1, NOP -> Unit
                    in ICONST_M1..ICONST_5 -> stack += insn.opcode - ICONST_0
                    BIPUSH, SIPUSH -> stack += (insn as IntInsnNode).operand
                    LDC -> {
                        val value = (insn as LdcInsnNode).cst
                        require(value is Int || value is String) { "Only int and string constants are supported." }
                        if (value is String) require(value.length <= 65_536) { "String exceeds recovery budget." }
                        stack += value
                    }
                    ILOAD, ALOAD -> stack += requireNotNull(locals[(insn as VarInsnNode).`var`])
                    ISTORE, ASTORE -> locals[(insn as VarInsnNode).`var`] = pop()
                    IINC -> (insn as IincInsnNode).let { locals[it.`var`] = (locals[it.`var`] as Int) + it.incr }
                    POP -> pop()
                    SWAP -> { val b = pop(); val a = pop(); stack += b; stack += a }
                    DUP -> stack += stack.last()
                    DUP_X1 -> { val b = pop(); val a = pop(); stack += b; stack += a; stack += b }
                    DUP2 -> { val a = stack[stack.size - 2]; val b = stack.last(); stack += a; stack += b }
                    IADD, ISUB, IMUL, IDIV, IREM, IXOR, IAND, IOR, ISHL, ISHR, IUSHR -> {
                        val b = int(); val a = int()
                        stack += when (insn.opcode) {
                            IADD -> a + b; ISUB -> a - b; IMUL -> a * b; IDIV -> a / b; IREM -> a % b
                            IXOR -> a xor b; IAND -> a and b; IOR -> a or b
                            ISHL -> a shl b; ISHR -> a shr b; else -> a ushr b
                        }
                    }
                    I2C -> stack += int() and 0xffff
                    ARRAYLENGTH -> stack += when (val array = pop()) {
                        is CharArray -> array.size
                        is Array<*> -> array.size
                        else -> throw IllegalArgumentException("Unsupported array.")
                    }
                    ANEWARRAY -> {
                        require((insn as TypeInsnNode).desc == "java/lang/String") { "Only String arrays are supported." }
                        val size = int(); require(size in 0..1024) { "String table exceeds recovery budget." }
                        stack.add(arrayOfNulls<String>(size))
                    }
                    CALOAD -> { val i = int(); stack += (pop() as CharArray)[i].code }
                    CASTORE -> { val v = int(); val i = int(); (pop() as CharArray)[i] = v.toChar() }
                    AASTORE -> {
                        val v = text(pop()); val i = int()
                        @Suppress("UNCHECKED_CAST")
                        val array = pop() as Array<String?>
                        array[i] = v
                    }
                    NEW -> {
                        require((insn as TypeInsnNode).desc == "java/lang/String") { "Target objects cannot be instantiated." }
                        stack += StringBox()
                    }
                    INVOKEVIRTUAL, INVOKESPECIAL -> {
                        val call = insn as MethodInsnNode
                        require(call.owner == "java/lang/String") { "Target or external calls are unsupported." }
                        when (call.name + call.desc) {
                            "toCharArray()[C" -> {
                                val value = text(pop())
                                allocatedCharacters += value.length
                                require(allocatedCharacters <= 4_000_000) { "Character allocation budget exceeded." }
                                stack.add(value.toCharArray())
                            }
                            "length()I" -> stack += text(pop()).length
                            "substring(II)Ljava/lang/String;" -> {
                                val end = int(); val start = int(); stack += text(pop()).substring(start, end)
                            }
                            "charAt(I)C" -> { val i = int(); stack += text(pop())[i].code }
                            "<init>([C)V" -> { val chars = pop() as CharArray; (pop() as StringBox).value = String(chars) }
                            "intern()Ljava/lang/String;" -> stack += text(pop())
                            else -> throw IllegalArgumentException("Unsupported String operation.")
                        }
                    }
                    PUTSTATIC -> {
                        val field = insn as FieldInsnNode
                        require(field.owner == target.classNode.name && field.desc in setOf("Ljava/lang/String;", "[Ljava/lang/String;")) {
                            "Only local string table assignments are supported."
                        }
                        assigned[field.name] = pop()
                    }
                    GOTO -> next = target.method.instructions.indexOf((insn as JumpInsnNode).label)
                    in IFEQ..IFLE, in IF_ICMPEQ..IF_ICMPLE -> {
                        val b = int()
                        val a = if (insn.opcode in IF_ICMPEQ..IF_ICMPLE) int() else b
                        val right = if (insn.opcode in IF_ICMPEQ..IF_ICMPLE) b else 0
                        val taken = when (insn.opcode) {
                            IFEQ, IF_ICMPEQ -> a == right; IFNE, IF_ICMPNE -> a != right
                            IFLT, IF_ICMPLT -> a < right; IFGE, IF_ICMPGE -> a >= right
                            IFGT, IF_ICMPGT -> a > right; else -> a <= right
                        }
                        if (taken) next = target.method.instructions.indexOf((insn as JumpInsnNode).label)
                    }
                    TABLESWITCH -> {
                        require(insn === switch) { "Unsupported additional switch." }
                        val key = int()
                        val label = if (key in switch.min..switch.max) switch.labels[key - switch.min] else switch.dflt
                        next = target.method.instructions.indexOf(label)
                    }
                    RETURN -> { returned = true; break }
                    else -> throw IllegalArgumentException("Unsupported opcode ${insn.opcode} at $pc.")
                }
                require(stack.size <= 256 && locals.size <= 256) { "Static recovery state budget exceeded." }
                pc = next
            }
            require(returned && assigned.isNotEmpty()) { "Initializer did not finish a supported table assignment." }
            val strings = assigned.flatMap { (field, value) ->
                if (value is Array<*>) value.mapIndexed { i, item -> RecoveredString(field, i, text(requireNotNull(item))) }
                else listOf(RecoveredString(field, null, text(value)))
            }
            require(strings.sumOf { it.value.length.toLong() } <= 1_000_000) { "Recovered payload budget exceeded." }
            indexes.ensureIndex(projectId)
            StringRecoveryResult(projectId, target.inputSha256, target.className, status = "recovered", strings = strings,
                cyclicKeys = keys, sourceInstructionIndexes = insns.mapIndexedNotNull { i, insn ->
                    i.takeIf { insn is LdcInsnNode || insn === switch || insn.opcode in setOf(IXOR, CALOAD, CASTORE, PUTSTATIC) }
                }, steps = steps)
        } catch (failure: Exception) {
            StringRecoveryResult(projectId, target.inputSha256, target.className, status = "unsupported",
                steps = steps, diagnostics = listOf(failure.message ?: failure.javaClass.simpleName))
        }
    }

    private fun integer(insn: AbstractInsnNode): Int = when (insn) {
        is IntInsnNode -> insn.operand
        is LdcInsnNode -> insn.cst as? Int ?: throw IllegalArgumentException("Expected int key.")
        else -> if (insn.opcode in ICONST_M1..ICONST_5) insn.opcode - ICONST_0
            else throw IllegalArgumentException("Expected constant key.")
    }
}
