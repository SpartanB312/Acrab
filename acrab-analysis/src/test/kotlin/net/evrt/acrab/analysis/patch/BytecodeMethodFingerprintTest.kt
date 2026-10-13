package net.evrt.acrab.analysis.patch

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.LabelNode
import kotlin.test.Test
import kotlin.test.assertNotEquals

class BytecodeMethodFingerprintTest {
    @Test
    fun `fingerprint includes constant operands`() {
        val method = methodWith(LdcInsnNode("before"), InsnNode(Opcodes.POP), InsnNode(Opcodes.RETURN))
        val before = BytecodeMethodFingerprint.instructions(method)
        (method.instructions.first as LdcInsnNode).cst = "after"

        assertNotEquals(before, BytecodeMethodFingerprint.instructions(method))
    }

    @Test
    fun `fingerprint includes switch keys and destinations`() {
        val default = LabelNode()
        val first = LabelNode()
        val second = LabelNode()
        val method = methodWith(
            InsnNode(Opcodes.ICONST_0),
            TableSwitchInsnNode(0, 1, default, first, second),
            first,
            InsnNode(Opcodes.RETURN),
            second,
            InsnNode(Opcodes.RETURN),
            default,
            InsnNode(Opcodes.RETURN),
        )
        val before = BytecodeMethodFingerprint.instructions(method)
        val switch = method.instructions.toArray().filterIsInstance<TableSwitchInsnNode>().single()
        switch.labels[1] = first

        assertNotEquals(before, BytecodeMethodFingerprint.instructions(method))
    }

    private fun methodWith(vararg nodes: org.objectweb.asm.tree.AbstractInsnNode): MethodNode =
        MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "test", "()V", null, null).also { method ->
            nodes.forEach(method.instructions::add)
        }
}
