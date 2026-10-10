package net.evrt.antarelite.analysis.evidence

import kotlinx.serialization.Serializable
import net.evrt.antarelite.analysis.index.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodInsnNode

@Serializable
data class ReferenceResult(
    val projectId: String,
    val inputSha256: String,
    val totalCount: Int,
    val offset: Int,
    val nextOffset: Int?,
    val truncated: Boolean,
    val scanComplete: Boolean = true,
    val calls: List<InvocationReference> = emptyList(),
    val fields: List<FieldReference> = emptyList(),
    val warnings: List<String> = listOf("References describe indexed instructions; virtual dispatch, reflection and external callers are unresolved."),
)

@Serializable
data class CallContextResult(
    val projectId: String,
    val inputSha256: String,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val instructionIndex: Int,
    val targetOwner: String,
    val targetName: String,
    val targetDescriptor: String,
    val arguments: List<StackSliceInfo>,
    val receiver: StackSliceInfo?,
    val calleeArgumentValues: Map<String, String>,
    val callerArgumentValues: Map<String, String>,
    val fieldValues: Map<String, String>,
    val scope: String = "one-observed-callsite-normal-flow",
    val warnings: List<String>,
)

class ReferenceService(private val indexes: JvmIndexService, private val evidence: BytecodeEvidenceService) {
    fun methodReferences(projectId: String, className: String, methodName: String, descriptor: String?,
        direction: String, offset: Int, limit: Int): ReferenceResult {
        require(direction in setOf("callers", "callees")) { "direction must be callers or callees." }
        val target = evidence.loadMethod(projectId, className, methodName, descriptor)
        val index = indexes.ensureIndex(projectId)
        val values = index.invocations.filter {
            if (direction == "callers") it.targetOwner == target.className && it.targetName == target.method.name &&
                it.targetDescriptor == target.method.desc
            else it.ownerClass == target.className && it.sourceMethod == target.method.name &&
                it.sourceDescriptor == target.method.desc
        }
        val page = page(values, offset, limit)
        return ReferenceResult(projectId, index.summary.archiveSha256, values.size, offset,
            next(values.size, offset, page.size), offset + page.size < values.size, calls = page)
    }

    fun fieldReferences(projectId: String, className: String, fieldName: String, descriptor: String?,
        access: String, offset: Int, limit: Int): ReferenceResult {
        require(access in setOf("all", "reads", "writes")) { "access must be all, reads or writes." }
        val fields = indexes.classSummary(projectId, className).fields.filter {
            it.name == fieldName && (descriptor == null || it.descriptor == descriptor)
        }
        require(fields.size == 1) { "Field selector must resolve exactly; supply fieldDescriptor when ambiguous." }
        val index = indexes.ensureIndex(projectId)
        val values = index.fieldReferences.filter {
            it.targetOwner == className && it.targetName == fieldName && it.targetDescriptor == fields.single().descriptor &&
                (access == "all" || if (access == "writes") it.opcode.startsWith("PUT") else it.opcode.startsWith("GET"))
        }
        val page = page(values, offset, limit)
        return ReferenceResult(projectId, index.summary.archiveSha256, values.size, offset,
            next(values.size, offset, page.size), offset + page.size < values.size, fields = page)
    }

    fun callContext(projectId: String, className: String, methodName: String, descriptor: String?, instructionIndex: Int,
        argumentValues: Map<String, String>, fieldValues: Map<String, String>): CallContextResult {
        val target = evidence.loadMethod(projectId, className, methodName, descriptor)
        require(instructionIndex in 0 until target.method.instructions.size()) { "instructionIndex out of range." }
        val call = target.method.instructions[instructionIndex] as? MethodInsnNode
            ?: throw IllegalArgumentException("instructionIndex must identify a direct JVM method invocation.")
        val numeric = NumericConstantAnalysis(target.classNode.name, target.method, argumentValues, fieldValues)
        val provenance = OperandProvenance(target.classNode.name, target.method)
        val operands = provenance.operands(instructionIndex, numeric = numeric)
        val hasReceiver = call.opcode != Opcodes.INVOKESTATIC
        val arguments = if (hasReceiver) operands.drop(1) else operands
        return CallContextResult(projectId, target.inputSha256, target.className, target.method.name, target.method.desc,
            instructionIndex, call.owner.replace('/', '.'), call.name, call.desc, arguments,
            if (hasReceiver) operands.firstOrNull() else null,
            arguments.mapIndexedNotNull { i, operand -> operand.value?.let { i.toString() to it } }.toMap(),
            argumentValues, fieldValues, warnings = numeric.warnings + provenance.warnings +
                "This context describes one callsite under supplied assumptions; it does not resolve dynamic dispatch or establish all callers.")
    }

    private fun <T> page(values: List<T>, offset: Int, limit: Int): List<T> {
        require(offset >= 0 && offset <= values.size) { "offset must be within the result range." }
        require(limit in 1..1000) { "limit must be between 1 and 1000." }
        return values.drop(offset).take(limit)
    }
    private fun next(total: Int, offset: Int, count: Int): Int? = (offset + count).takeIf { it < total }
}
