package net.evrt.acrab.analysis.evidence

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*
import kotlin.test.*

class NumericConstantAnalysisTest {
    @Test
    fun `integer overflow and shift distances follow JVM semantics`() {
        val overflow = method("()I") {
            visitLdcInsn(Int.MAX_VALUE); visitInsn(ICONST_1); visitInsn(IADD)
            visitIntInsn(BIPUSH, 32); visitInsn(ISHL); visitInsn(IRETURN)
        }
        assertEquals(Int.MIN_VALUE, NumericConstantAnalysis("Example", overflow).frames[5]!!.getStack(0).constant)
    }

    @Test
    fun `known comparisons and unconditional jumps have outcomes`() {
        val taken = LabelNode().label
        val method = method("()I") {
            visitInsn(ICONST_2); visitInsn(ICONST_1); visitJumpInsn(IF_ICMPGT, taken)
            visitInsn(ICONST_0); visitInsn(IRETURN)
            visitLabel(taken); visitInsn(ICONST_1); visitInsn(IRETURN)
        }
        assertEquals("taken", NumericConstantAnalysis("Example", method).outcome(2))
        val loop = LabelNode().label
        val unconditional = method("()V") { visitLabel(loop); visitJumpInsn(GOTO, loop) }
        assertEquals("taken", NumericConstantAnalysis("Example", unconditional).outcome(1))
    }

    @Test
    fun `unknown arguments and conflicting merge values remain unknown`() {
        val other = LabelNode().label
        val join = LabelNode().label
        val method = method("(I)I") {
            visitVarInsn(ILOAD, 0); visitJumpInsn(IFEQ, other)
            visitInsn(ICONST_1); visitJumpInsn(GOTO, join)
            visitLabel(other); visitInsn(ICONST_2)
            visitLabel(join); visitInsn(IRETURN)
        }
        val analysis = NumericConstantAnalysis("Example", method)
        assertEquals("unknown", analysis.outcome(1))
        assertNull(analysis.frames[7]!!.getStack(0).constant)
    }

    @Test
    fun `division by zero does not produce a constant normal result`() {
        val method = method("()I") { visitInsn(ICONST_1); visitInsn(ICONST_0); visitInsn(IDIV); visitInsn(IRETURN) }
        assertNull(NumericConstantAnalysis("Example", method).frames[3]!!.getStack(0).constant)
    }

    private fun method(descriptor: String, body: MethodNode.() -> Unit): MethodNode =
        MethodNode(ASM9, ACC_PUBLIC or ACC_STATIC, "test", descriptor, null, null).apply {
            body(); maxStack = 8; maxLocals = 4
        }
}
