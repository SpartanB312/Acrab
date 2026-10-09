package net.evrt.antarelite.analysis.patch

import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object BytecodeMethodFingerprint {
    fun instructions(method: MethodNode): String = fingerprint { out ->
        out.writeUTF("antares-method-instructions-v2")
        out.writeUTF(method.name)
        out.writeUTF(method.desc)
        out.writeInt(method.access)
        val nodes = method.instructions.toArray()
        val indexes = nodes.withIndex().associate { it.value to it.index }
        out.writeInt(nodes.size)
        nodes.forEach { node -> writeInstruction(out, node, indexes) }
        out.writeInt(method.tryCatchBlocks.size)
        method.tryCatchBlocks.forEach { block ->
            out.writeInt(indexes[block.start] ?: -1)
            out.writeInt(indexes[block.end] ?: -1)
            out.writeInt(indexes[block.handler] ?: -1)
            out.writeNullable(block.type)
        }
    }

    fun debugMetadata(method: MethodNode): String = fingerprint { out ->
        out.writeUTF("antares-method-debug-v1")
        val nodes = method.instructions.toArray()
        val indexes = nodes.withIndex().associate { it.value to it.index }
        val lines = nodes.filterIsInstance<LineNumberNode>()
        out.writeInt(lines.size)
        lines.forEach { line ->
            out.writeInt(line.line)
            out.writeInt(indexes[line.start] ?: -1)
        }
        val locals = method.localVariables.orEmpty()
        out.writeInt(locals.size)
        locals.forEach { local ->
            out.writeUTF(local.name)
            out.writeUTF(local.desc)
            out.writeNullable(local.signature)
            out.writeInt(indexes[local.start] ?: -1)
            out.writeInt(indexes[local.end] ?: -1)
            out.writeInt(local.index)
        }
    }

    private fun writeInstruction(
        out: DataOutputStream,
        node: AbstractInsnNode,
        labels: Map<AbstractInsnNode, Int>,
    ) {
        out.writeUTF(node.javaClass.simpleName)
        out.writeInt(node.opcode)
        when (node) {
            is LabelNode, is FrameNode -> Unit
            is LineNumberNode -> {
                out.writeInt(node.line)
                out.writeInt(labels[node.start] ?: -1)
            }
            is IntInsnNode -> out.writeInt(node.operand)
            is VarInsnNode -> out.writeInt(node.`var`)
            is TypeInsnNode -> out.writeUTF(node.desc)
            is FieldInsnNode -> {
                out.writeUTF(node.owner)
                out.writeUTF(node.name)
                out.writeUTF(node.desc)
            }
            is MethodInsnNode -> {
                out.writeUTF(node.owner)
                out.writeUTF(node.name)
                out.writeUTF(node.desc)
                out.writeBoolean(node.itf)
            }
            is InvokeDynamicInsnNode -> {
                out.writeUTF(node.name)
                out.writeUTF(node.desc)
                out.writeConstant(node.bsm, labels)
                out.writeInt(node.bsmArgs.size)
                node.bsmArgs.forEach { out.writeConstant(it, labels) }
            }
            is JumpInsnNode -> out.writeInt(labels[node.label] ?: -1)
            is LdcInsnNode -> out.writeConstant(node.cst, labels)
            is IincInsnNode -> {
                out.writeInt(node.`var`)
                out.writeInt(node.incr)
            }
            is TableSwitchInsnNode -> {
                out.writeInt(node.min)
                out.writeInt(node.max)
                out.writeInt(labels[node.dflt] ?: -1)
                out.writeInt(node.labels.size)
                node.labels.forEach { out.writeInt(labels[it] ?: -1) }
            }
            is LookupSwitchInsnNode -> {
                out.writeInt(labels[node.dflt] ?: -1)
                out.writeInt(node.keys.size)
                node.keys.forEachIndexed { index, key ->
                    out.writeInt(key)
                    out.writeInt(labels[node.labels[index]] ?: -1)
                }
            }
            is MultiANewArrayInsnNode -> {
                out.writeUTF(node.desc)
                out.writeInt(node.dims)
            }
            else -> Unit
        }
    }

    private fun DataOutputStream.writeConstant(value: Any?, labels: Map<AbstractInsnNode, Int>) {
        when (value) {
            null -> writeByte(0)
            is String -> { writeByte(1); writeUtf8(value) }
            is Int -> { writeByte(2); writeInt(value) }
            is Float -> { writeByte(3); writeInt(value.toRawBits()) }
            is Long -> { writeByte(4); writeLong(value) }
            is Double -> { writeByte(5); writeLong(value.toRawBits()) }
            is Type -> { writeByte(6); writeUTF(value.descriptor) }
            is Handle -> {
                writeByte(7)
                writeInt(value.tag)
                writeUTF(value.owner)
                writeUTF(value.name)
                writeUTF(value.desc)
                writeBoolean(value.isInterface)
            }
            is ConstantDynamic -> {
                writeByte(8)
                writeUTF(value.name)
                writeUTF(value.descriptor)
                writeConstant(value.bootstrapMethod, labels)
                writeInt(value.bootstrapMethodArgumentCount)
                repeat(value.bootstrapMethodArgumentCount) { index ->
                    writeConstant(value.getBootstrapMethodArgument(index), labels)
                }
            }
            is LabelNode -> { writeByte(9); writeInt(labels[value] ?: -1) }
            else -> error("Unsupported bytecode constant: ${value.javaClass.name}")
        }
    }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun fingerprint(write: (DataOutputStream) -> Unit): String {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(write)
        }.toByteArray()
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
