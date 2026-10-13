package net.evrt.acrab.analysis.obfuscation

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*
import kotlin.test.*

class ControlFlowPatternsTest {
    @Test
    fun `ordinary argument switch is not a loop dispatcher`() {
        val a = LabelNode(); val b = LabelNode(); val end = LabelNode()
        val instructions = listOf(VarInsnNode(ILOAD, 0), TableSwitchInsnNode(1, 2, end, a, b),
            a, InsnNode(RETURN), b, InsnNode(RETURN), end, InsnNode(RETURN))
        assertFalse(hasLoopDispatcher(instructions))
    }

    @Test
    fun `mutable local state returning to dispatch is detected`() {
        val loop = LabelNode(); val a = LabelNode(); val b = LabelNode(); val end = LabelNode()
        val instructions = listOf(InsnNode(ICONST_1), VarInsnNode(ISTORE, 0), loop,
            VarInsnNode(ILOAD, 0), TableSwitchInsnNode(1, 2, end, a, b), a,
            InsnNode(ICONST_2), VarInsnNode(ISTORE, 0), JumpInsnNode(GOTO, loop),
            b, InsnNode(RETURN), end, InsnNode(RETURN))
        assertTrue(hasLoopDispatcher(instructions))
    }

    @Test
    fun `switch loop without selector writes is not flattened state dispatch`() {
        val loop = LabelNode(); val a = LabelNode(); val end = LabelNode()
        assertFalse(hasLoopDispatcher(listOf(loop, VarInsnNode(ILOAD, 0),
            LookupSwitchInsnNode(end, intArrayOf(37), arrayOf(a)), a, JumpInsnNode(GOTO, loop), end, InsnNode(RETURN))))
    }

    @Test
    fun `same boolean field copied through locals feeds repeated guards`() {
        val method = MethodNode(ACC_STATIC, "guards", "()V", null, null)
        method.instructions.add(FieldInsnNode(GETSTATIC, "Flags", "enabled", "Z"))
        method.instructions.add(VarInsnNode(ISTORE, 0))
        method.instructions.add(VarInsnNode(ILOAD, 0))
        method.instructions.add(VarInsnNode(ISTORE, 1))
        repeat(3) {
            val next = LabelNode()
            method.instructions.add(VarInsnNode(ILOAD, 1))
            method.instructions.add(JumpInsnNode(IFEQ, next))
            method.instructions.add(InsnNode(NOP))
            method.instructions.add(next)
        }
        method.instructions.add(InsnNode(RETURN))
        method.maxLocals = 2
        method.maxStack = 1
        assertTrue(hasRepeatedStaticBooleanGuard(method))
    }

    @Test
    fun `unrelated boolean field does not turn argument branches into guards`() {
        val method = MethodNode(ACC_STATIC, "branches", "(I)V", null, null)
        method.instructions.add(FieldInsnNode(GETSTATIC, "Flags", "enabled", "Z"))
        method.instructions.add(InsnNode(POP))
        repeat(3) {
            val next = LabelNode()
            method.instructions.add(VarInsnNode(ILOAD, 0))
            method.instructions.add(JumpInsnNode(IFNE, next))
            method.instructions.add(InsnNode(NOP))
            method.instructions.add(next)
        }
        method.instructions.add(InsnNode(RETURN))
        method.maxLocals = 1
        method.maxStack = 1
        assertFalse(hasRepeatedStaticBooleanGuard(method))
    }

    @Test
    fun `independent boolean fields do not count as one repeated guard`() {
        val method = MethodNode(ACC_STATIC, "guards", "()V", null, null)
        repeat(3) { index ->
            val next = LabelNode()
            method.instructions.add(FieldInsnNode(GETSTATIC, "Flags", "flag$index", "Z"))
            method.instructions.add(JumpInsnNode(IFEQ, next))
            method.instructions.add(InsnNode(NOP))
            method.instructions.add(next)
        }
        method.instructions.add(InsnNode(RETURN))
        method.maxLocals = 0
        method.maxStack = 1
        assertFalse(hasRepeatedStaticBooleanGuard(method))
    }
}
