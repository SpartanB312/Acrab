package net.evrt.antarelite.analysis.patch

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.SimpleVerifier
import org.objectweb.asm.util.CheckClassAdapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.zip.ZipInputStream

internal data class BytecodeVerificationResult(
    val verified: Boolean,
    val scope: String = "asm-static",
    val checksPassed: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)

/** Verifies and writes class files using only class headers, never loading or initializing targets. */
internal class BytecodePatchVerifier(sourceArchive: ByteArray) {
    private val hierarchy = OfflineClassHierarchy(sourceArchive)

    fun writeClass(classNode: ClassNode): ByteArray {
        val writer = object : ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS) {
            override fun getCommonSuperClass(type1: String, type2: String): String =
                hierarchy.commonSuperClass(type1, type2)
        }
        classNode.accept(writer)
        return writer.toByteArray()
    }

    fun verify(classBytes: ByteArray): BytecodeVerificationResult {
        val diagnostics = mutableListOf<String>()
        try {
            ClassReader(classBytes).accept(CheckClassAdapter(ClassWriter(0), false), 0)
        } catch (failure: Exception) {
            diagnostics += "Class structure: ${failure.message ?: failure.javaClass.simpleName}"
        }

        if (diagnostics.isEmpty()) {
            val classNode = try {
                ClassNode().also { ClassReader(classBytes).accept(it, 0) }
            } catch (failure: Exception) {
                diagnostics += "Class parsing: ${failure.message ?: failure.javaClass.simpleName}"
                null
            }
            if (classNode != null) {
                val currentType = Type.getObjectType(classNode.name)
                val parentType = classNode.superName?.let(Type::getObjectType)
                val interfaceTypes = classNode.interfaces.map(Type::getObjectType)
                classNode.methods.forEach { method ->
                    if (method.instructions.size() == 0) return@forEach
                    try {
                        val verifier = OfflineSimpleVerifier(
                            hierarchy,
                            currentType,
                            parentType,
                            interfaceTypes,
                            classNode.access and Opcodes.ACC_INTERFACE != 0,
                        )
                        Analyzer<BasicValue>(verifier).analyze(classNode.name, method)
                    } catch (failure: AnalyzerException) {
                        diagnostics += "${method.name}${method.desc}: ${failure.message}"
                    } catch (failure: IllegalArgumentException) {
                        diagnostics += "${method.name}${method.desc}: ${failure.message}"
                    }
                }
            }
        }

        return BytecodeVerificationResult(
            verified = diagnostics.isEmpty(),
            checksPassed = if (diagnostics.isEmpty()) {
                listOf("ASM class structure", "typed operand-stack dataflow", "offline reference hierarchy")
            } else {
                emptyList()
            },
            diagnostics = diagnostics,
        )
    }
}

private class OfflineSimpleVerifier(
    private val hierarchy: OfflineClassHierarchy,
    currentClass: Type,
    superClass: Type?,
    interfaces: List<Type>,
    isInterface: Boolean,
) : SimpleVerifier(Opcodes.ASM9, currentClass, superClass, interfaces, isInterface) {
    override fun isInterface(type: Type): Boolean = hierarchy.isInterface(type)

    override fun getSuperClass(type: Type): Type? = hierarchy.superClass(type)

    override fun isAssignableFrom(expected: Type, actual: Type): Boolean =
        hierarchy.isAssignableFrom(expected, actual)
}

private class OfflineClassHierarchy(sourceArchive: ByteArray) {
    private val headers = mutableMapOf<String, ClassHeader>()

    init {
        ZipInputStream(ByteArrayInputStream(sourceArchive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.endsWith(".class") && !entry.isDirectory) {
                    val bytes = zip.readBytes()
                    val reader = ClassReader(bytes)
                    reader.accept(
                        object : ClassVisitor(Opcodes.ASM9) {
                            override fun visit(
                                version: Int,
                                access: Int,
                                name: String,
                                signature: String?,
                                superName: String?,
                                interfaces: Array<out String>?,
                            ) {
                                headers[name] = ClassHeader(name, access, superName, interfaces?.toList().orEmpty())
                            }
                        },
                        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                    )
                }
                zip.closeEntry()
            }
        }
    }

    fun commonSuperClass(firstName: String, secondName: String): String {
        val common = commonType(typeOf(firstName), typeOf(secondName))
        return if (common.sort == Type.ARRAY) common.descriptor else common.internalName
    }

    fun isInterface(type: Type): Boolean =
        type.sort == Type.OBJECT && header(type.internalName).access and Opcodes.ACC_INTERFACE != 0

    fun superClass(type: Type): Type? = when (type.sort) {
        Type.ARRAY -> Type.getObjectType("java/lang/Object")
        Type.OBJECT -> header(type.internalName).superName?.let(Type::getObjectType)
        else -> null
    }

    fun isAssignableFrom(expected: Type, actual: Type): Boolean {
        if (expected == actual) return true
        if (expected.sort == Type.ARRAY) {
            if (actual.sort != Type.ARRAY) return false
            val expectedComponent = Type.getType(expected.descriptor.substring(1))
            val actualComponent = Type.getType(actual.descriptor.substring(1))
            if (!expectedComponent.isReference || !actualComponent.isReference) {
                return expectedComponent == actualComponent
            }
            return isAssignableFrom(expectedComponent, actualComponent)
        }
        if (expected.sort != Type.OBJECT) return false
        if (actual.sort == Type.ARRAY) {
            return expected.internalName in ARRAY_SUPERTYPES
        }
        if (actual.sort != Type.OBJECT) return false

        val pending = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        pending.add(actual.internalName)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!seen.add(name)) continue
            if (name == expected.internalName) return true
            val current = header(name)
            current.superName?.let(pending::addLast)
            current.interfaces.forEach(pending::addLast)
        }
        return false
    }

    private fun commonType(first: Type, second: Type): Type {
        if (first == second) return first
        if (isAssignableFrom(first, second)) return first
        if (isAssignableFrom(second, first)) return second
        if (first.sort == Type.ARRAY && second.sort == Type.ARRAY) {
            val firstComponent = Type.getType(first.descriptor.substring(1))
            val secondComponent = Type.getType(second.descriptor.substring(1))
            if (firstComponent.isReference && secondComponent.isReference) {
                return Type.getType("[${commonType(firstComponent, secondComponent).descriptor}")
            }
            return OBJECT_TYPE
        }
        if (first.sort == Type.ARRAY || second.sort == Type.ARRAY) return OBJECT_TYPE
        if (isInterface(first) || isInterface(second)) return OBJECT_TYPE

        var candidate = header(first.internalName).superName
        while (candidate != null) {
            val candidateType = Type.getObjectType(candidate)
            if (isAssignableFrom(candidateType, second)) return candidateType
            candidate = header(candidate).superName
        }
        return OBJECT_TYPE
    }

    private fun typeOf(name: String): Type = if (name.startsWith("[")) Type.getType(name) else Type.getObjectType(name)

    private fun header(name: String): ClassHeader = headers[name] ?: readHeader(name)?.also { headers[name] = it }
        ?: throw IllegalArgumentException("Cannot verify missing class header $name.")

    private fun readHeader(name: String): ClassHeader? {
        val bytes = ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() } ?: return null
        var result: ClassHeader? = null
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    internalName: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    result = ClassHeader(internalName, access, superName, interfaces?.toList().orEmpty())
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return result
    }

    private data class ClassHeader(
        val name: String,
        val access: Int,
        val superName: String?,
        val interfaces: List<String>,
    )

    private val Type.isReference: Boolean
        get() = sort == Type.OBJECT || sort == Type.ARRAY

    companion object {
        private val OBJECT_TYPE = Type.getObjectType("java/lang/Object")
        private val ARRAY_SUPERTYPES = setOf("java/lang/Object", "java/lang/Cloneable", "java/io/Serializable")
    }
}
