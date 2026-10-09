package net.evrt.antarelite.analysis.obfuscation

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue

internal fun hasLoopDispatcher(instructions: List<AbstractInsnNode>): Boolean {
    if (instructions.size > 10_000) return false
    val positions = instructions.withIndex().associate { it.value to it.index }
    fun successors(index: Int): List<Int> {
        val insn = instructions[index]
        val next = (index + 1).takeIf { it < instructions.size }
        return when (insn) {
            is JumpInsnNode -> listOfNotNull(positions[insn.label], next.takeUnless { insn.opcode == Opcodes.GOTO })
            is TableSwitchInsnNode -> (insn.labels + insn.dflt).mapNotNull { positions[it] }
            is LookupSwitchInsnNode -> (insn.labels + insn.dflt).mapNotNull { positions[it] }
            else -> if (insn.opcode in Opcodes.IRETURN..Opcodes.RETURN || insn.opcode == Opcodes.ATHROW) {
                emptyList()
            } else listOfNotNull(next)
        }
    }
    return instructions.indices.any { switchIndex ->
        if (instructions[switchIndex] !is TableSwitchInsnNode && instructions[switchIndex] !is LookupSwitchInsnNode) {
            return@any false
        }
        val selector = instructions.take(switchIndex).lastOrNull { it.opcode >= 0 } as? VarInsnNode
            ?: return@any false
        if (selector.opcode != Opcodes.ILOAD) return@any false
        val pending = ArrayDeque(successors(switchIndex).map { it to false })
        val visited = mutableSetOf<Pair<Int, Boolean>>()
        while (pending.isNotEmpty()) {
            val (index, updated) = pending.removeFirst()
            if (!visited.add(index to updated)) continue
            if (index == switchIndex) {
                if (updated) return@any true
                continue
            }
            val insn = instructions[index]
            val writesSelector = insn is VarInsnNode && insn.opcode == Opcodes.ISTORE && insn.`var` == selector.`var` ||
                insn is IincInsnNode && insn.`var` == selector.`var`
            successors(index).forEach { pending.addLast(it to (updated || writesSelector)) }
        }
        false
    }
}

/** Repeated boolean-field guard signal; it does not prove that any branch is dead. */
internal fun hasRepeatedStaticBooleanGuard(method: MethodNode): Boolean {
    val instructions = method.instructions.toArray()
    if (instructions.size > 10_000 || instructions.size.toLong() * (method.maxLocals + method.maxStack) > 2_000_000) {
        return false
    }
    if (instructions.none { it is FieldInsnNode && it.opcode == Opcodes.GETSTATIC && it.desc == "Z" }) return false
    val frames = try {
        Analyzer(SourceInterpreter()).analyze("GuardAnalysis", method)
    } catch (_: AnalyzerException) {
        return false
    }
    val positions = instructions.withIndex().associate { it.value to it.index }
    fun fields(value: SourceValue, visited: MutableSet<AbstractInsnNode>): Set<String> {
        return value.insns.flatMap { origin ->
            if (!visited.add(origin)) return@flatMap emptySet<String>()
            val index = positions[origin] ?: return@flatMap emptySet<String>()
            val frame = frames[index] ?: return@flatMap emptySet<String>()
            when {
                origin is FieldInsnNode && origin.opcode == Opcodes.GETSTATIC && origin.desc == "Z" ->
                    setOf("${origin.owner}.${origin.name}:${origin.desc}")
                origin is VarInsnNode && origin.opcode == Opcodes.ILOAD -> fields(frame.getLocal(origin.`var`), visited)
                origin is VarInsnNode && origin.opcode == Opcodes.ISTORE && frame.stackSize > 0 ->
                    fields(frame.getStack(frame.stackSize - 1), visited)
                else -> emptySet()
            }
        }.toSet()
    }
    val uses = instructions.indices.mapNotNull { index ->
        if (instructions[index].opcode != Opcodes.IFEQ && instructions[index].opcode != Opcodes.IFNE) return@mapNotNull null
        val frame = frames[index] ?: return@mapNotNull null
        if (frame.stackSize == 0) return@mapNotNull null
        fields(frame.getStack(frame.stackSize - 1), mutableSetOf()).singleOrNull()
    }
    return uses.groupingBy { it }.eachCount().values.any { it >= 3 }
}
