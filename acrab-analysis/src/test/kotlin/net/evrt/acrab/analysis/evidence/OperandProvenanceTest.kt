package net.evrt.acrab.analysis.evidence

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*
import kotlin.test.*

class OperandProvenanceTest {
    @Test
    fun `consecutive branches consume different previously loaded operands`() {
        val first = LabelNode().label
        val second = LabelNode().label
        val method = method("(II)V") {
            visitVarInsn(ILOAD, 0); visitVarInsn(ILOAD, 1)
            visitJumpInsn(IFNE, first); visitJumpInsn(IFNE, second)
            visitInsn(RETURN)
            visitLabel(first); visitInsn(POP); visitInsn(RETURN)
            visitLabel(second); visitInsn(RETURN)
        }
        val analysis = OperandProvenance("Example", method)
        assertTrue(analysis.warnings.isEmpty(), analysis.warnings.toString())
        val firstOperand = analysis.operands(2).single()
        val secondOperand = analysis.operands(3).single()
        assertEquals(1, firstOperand.producerInstructionIndex)
        assertEquals(0, secondOperand.producerInstructionIndex)
        assertEquals(listOf(1), firstOperand.parameterLocalIndexes)
        assertEquals(listOf(0), secondOperand.parameterLocalIndexes)
        assertEquals("local[0] != 0", branchCondition(IFNE, listOf(secondOperand)))
    }

    @Test
    fun `wide duplicated operands retain width and source`() {
        val method = method("(J)J") {
            visitVarInsn(LLOAD, 0); visitInsn(DUP2); visitInsn(LADD); visitInsn(LRETURN)
        }
        val analysis = OperandProvenance("Example", method)
        val operands = analysis.operands(2)
        assertEquals(listOf(2, 2), operands.map { it.slotWidth })
        assertEquals(listOf(0, 0), operands.map { it.producerInstructionIndex })
        assertEquals(operands, analysis.operands(2, 4))
        assertFailsWith<IllegalArgumentException> { analysis.operands(2, 1) }
        assertFailsWith<IllegalArgumentException> { analysis.operands(2, 257) }
    }

    @Test
    fun `merged alternatives have no invented unique producer`() {
        val other = LabelNode().label
        val join = LabelNode().label
        val method = method("(I)I") {
            visitVarInsn(ILOAD, 0); visitJumpInsn(IFEQ, other)
            visitInsn(ICONST_1); visitJumpInsn(GOTO, join)
            visitLabel(other); visitInsn(ICONST_2)
            visitLabel(join); visitInsn(IRETURN)
        }
        val operand = OperandProvenance("Example", method).operands(7).single()
        assertNull(operand.producerInstructionIndex)
        assertEquals(listOf(2, 5), operand.producerInstructionIndexes)
    }

    @Test
    fun `stores and reloads preserve the dependency chain`() {
        val method = method("(I)I") {
            visitVarInsn(ILOAD, 0); visitInsn(ICONST_1); visitInsn(IADD)
            visitVarInsn(ISTORE, 1); visitVarInsn(ILOAD, 1); visitInsn(IRETURN)
        }
        val operand = OperandProvenance("Example", method).operands(5).single()
        assertEquals(4, operand.producerInstructionIndex)
        assertEquals(listOf(0, 1, 2, 3, 4), operand.originInstructionIndexes)
        assertEquals(listOf(0), operand.parameterLocalIndexes)
    }

    @Test
    fun `exception handler input does not invent a normal flow producer`() {
        val start = LabelNode().label
        val end = LabelNode().label
        val handler = LabelNode().label
        val method = method("()V") {
            visitTryCatchBlock(start, end, handler, "java/lang/Exception")
            visitLabel(start); visitInsn(ACONST_NULL); visitInsn(ATHROW)
            visitLabel(end); visitInsn(RETURN)
            visitLabel(handler); visitVarInsn(ASTORE, 0); visitInsn(RETURN)
        }
        val analysis = OperandProvenance("Example", method)
        assertTrue(analysis.warnings.isEmpty())
        assertTrue(analysis.operands(6).single().producerInstructionIndexes.isEmpty())
    }

    private fun method(descriptor: String, body: MethodNode.() -> Unit): MethodNode =
        MethodNode(ASM9, ACC_PUBLIC or ACC_STATIC, "test", descriptor, null, null).apply {
            body(); maxStack = 8; maxLocals = 4
        }
}
