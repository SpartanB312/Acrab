package net.evrt.acrab.analysis.index

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.InputStream
import java.security.MessageDigest
import java.util.jar.JarFile
import java.nio.file.Path
import java.io.ByteArrayOutputStream

class JarIndexer {
    fun index(projectId: String, archivePath: Path, archiveSha256: String? = null): ProjectIndex {
        val resolvedSha = sha256File(archivePath)
        require(archiveSha256 == null || archiveSha256 == resolvedSha) { "Source archive checksum changed before indexing." }
        val classes = mutableListOf<ClassInfo>()
        val strings = mutableListOf<StringReference>()
        val invocations = mutableListOf<InvocationReference>()
        val fieldReferences = mutableListOf<FieldReference>()

        var manifestMainClass: String? = null
        JarFile(archivePath.toFile()).use { jar ->
            manifestMainClass = jar.manifest?.mainAttributes?.getValue("Main-Class")
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val bytes = readBounded(jar.getInputStream(entry), entry.name)
                val parsed = parseClass(bytes)
                classes += parsed.first
                strings += parsed.second
                invocations += parsed.third
                fieldReferences += parsed.fourth
            }
        }

        val summary = buildSummary(
            projectId = projectId,
            archiveSha256 = resolvedSha,
            classes = classes,
            manifestMainClass = manifestMainClass(archivePath),
        )
        return ProjectIndex(
            summary = summary,
            classes = classes.sortedBy { it.name },
            strings = strings,
            invocations = invocations,
            fieldReferences = fieldReferences,
        )
    }

    private fun buildSummary(
        projectId: String,
        archiveSha256: String,
        classes: List<ClassInfo>,
        manifestMainClass: String?,
    ): JarSummary = JarSummary(
        projectId = projectId,
        archiveSha256 = archiveSha256,
        classCount = classes.size,
        packageCount = classes.map { it.packageName }.toSet().size,
        methodCount = classes.sumOf { it.methods.size },
        fieldCount = classes.sumOf { it.fields.size },
        kotlinClassCount = classes.count { it.isKotlin },
        manifestMainClass = manifestMainClass,
        topPackages = classes
            .groupingBy { it.packageName.ifBlank { "<default>" } }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(20)
            .map { PackageSummary(it.key, it.value) },
    )

    private fun normalizeInternalName(name: String): String = name.replace('/', '.')

    /** Shannon entropy in bits per character; high values on non-printable data suggest ciphertext. */
    private fun entropy(value: String): Double {
        if (value.isEmpty()) return 0.0
        val counts = value.groupingBy { it }.eachCount()
        return counts.values.sumOf { count ->
            val probability = count.toDouble() / value.length
            -probability * kotlin.math.log2(probability)
        }
    }

    private fun printableRatio(value: String): Double {
        if (value.isEmpty()) return 1.0
        return value.count { character -> character.code in 0x20..0x7e }.toDouble() / value.length
    }

    /**
     * Rank a constant so a caller can tell "looks like a decoded literal" from "looks like
     * ciphertext" without writing its own heuristic.
     */
    private fun classifyConstant(value: String): String {
        if (value.isEmpty()) return "plain"
        val ratio = printableRatio(value)
        val bits = entropy(value)
        return when {
            ratio < 0.75 && value.length >= 4 -> "likely-encrypted"
            bits >= 4.0 && ratio < 0.95 && value.length >= 8 -> "likely-encrypted"
            ratio < 0.95 || bits >= 3.5 -> "suspicious"
            else -> "plain"
        }
    }

    private fun accessFlags(access: Int): List<String> = buildList {
        addFlag(access, Opcodes.ACC_PUBLIC, "public")
        addFlag(access, Opcodes.ACC_PRIVATE, "private")
        addFlag(access, Opcodes.ACC_PROTECTED, "protected")
        addFlag(access, Opcodes.ACC_STATIC, "static")
        addFlag(access, Opcodes.ACC_FINAL, "final")
        addFlag(access, Opcodes.ACC_SYNCHRONIZED, "synchronized")
        if (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) add("synthetic")
        addFlag(access, Opcodes.ACC_ABSTRACT, "abstract")
        addFlag(access, Opcodes.ACC_NATIVE, "native")
        addFlag(access, Opcodes.ACC_INTERFACE, "interface")
        addFlag(access, Opcodes.ACC_ANNOTATION, "annotation")
        addFlag(access, Opcodes.ACC_ENUM, "enum")
    }

    private fun MutableList<String>.addFlag(access: Int, mask: Int, label: String) {
        if (access and mask != 0) add(label)
    }

    private fun opcodeName(opcode: Int): String = when (opcode) {
        Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL"
        Opcodes.INVOKESPECIAL -> "INVOKESPECIAL"
        Opcodes.INVOKESTATIC -> "INVOKESTATIC"
        Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE"
        else -> "OPCODE_$opcode"
    }

    private data class ParsedClass(val first: ClassInfo, val second: List<StringReference>,
        val third: List<InvocationReference>, val fourth: List<FieldReference>)

    private fun parseClass(bytes: ByteArray): ParsedClass {
        val classNode = ClassNode()
        ClassReader(bytes).accept(classNode, ClassReader.SKIP_FRAMES)
        val strings = mutableListOf<StringReference>()
        val invocations = mutableListOf<InvocationReference>()
        val fieldReferences = mutableListOf<FieldReference>()

        classNode.methods.forEach { method ->
            method.instructions.toArray().forEachIndexed { index, instruction ->
                when (instruction) {
                    is org.objectweb.asm.tree.FieldInsnNode -> fieldReferences += FieldReference(
                        normalizeInternalName(classNode.name), method.name, method.desc,
                        normalizeInternalName(instruction.owner), instruction.name, instruction.desc,
                        net.evrt.acrab.analysis.support.OpcodeNames.of(instruction.opcode), index,
                    )
                    is LdcInsnNode -> if (instruction.cst is String) {
                        val value = instruction.cst as String
                        strings += StringReference(
                            value = value,
                            ownerClass = normalizeInternalName(classNode.name),
                            methodName = method.name,
                            methodDescriptor = method.desc,
                            instructionIndex = index,
                            entropyBitsPerChar = entropy(value),
                            printableRatio = printableRatio(value),
                            classification = classifyConstant(value),
                        )
                    }
                    is MethodInsnNode -> invocations += InvocationReference(
                        ownerClass = normalizeInternalName(classNode.name),
                        sourceMethod = method.name,
                        sourceDescriptor = method.desc,
                        targetOwner = normalizeInternalName(instruction.owner),
                        targetName = instruction.name,
                        targetDescriptor = instruction.desc,
                        opcode = opcodeName(instruction.opcode),
                        instructionIndex = index,
                    )
                    is InvokeDynamicInsnNode -> invocations += InvocationReference(
                        ownerClass = normalizeInternalName(classNode.name),
                        sourceMethod = method.name,
                        sourceDescriptor = method.desc,
                        targetOwner = "invokedynamic",
                        targetName = instruction.name,
                        targetDescriptor = instruction.desc,
                        bootstrapOwner = instruction.bsm.owner,
                        bootstrapName = instruction.bsm.name,
                        bootstrapDescriptor = instruction.bsm.desc,
                        bootstrapArguments = instruction.bsmArgs.map { it.toString() },
                        opcode = "INVOKEDYNAMIC",
                        instructionIndex = index,
                    )
                }
            }
        }

        val classInfo = ClassInfo(
            name = normalizeInternalName(classNode.name),
            internalName = classNode.name,
            packageName = classNode.name.substringBeforeLast('/', ""),
            accessFlags = accessFlags(classNode.access),
            superName = classNode.superName?.let(::normalizeInternalName),
            interfaces = classNode.interfaces.map(::normalizeInternalName),
            annotations = (classNode.visibleAnnotations.orEmpty() + classNode.invisibleAnnotations.orEmpty())
                .mapNotNull { it.desc },
            sourceFile = classNode.sourceFile,
            isKotlin = (classNode.visibleAnnotations.orEmpty() + classNode.invisibleAnnotations.orEmpty())
                .any { it.desc == "Lkotlin/Metadata;" },
            fields = classNode.fields.map { field ->
                FieldInfo(
                    name = field.name,
                    descriptor = field.desc,
                    accessFlags = accessFlags(field.access),
                    constantValue = field.value?.toString(),
                )
            },
            methods = classNode.methods.map { method ->
                MethodInfo(
                    id = "${normalizeInternalName(classNode.name)}#${method.name}${method.desc}",
                    name = method.name,
                    descriptor = method.desc,
                    accessFlags = accessFlags(method.access),
                    instructionCount = method.instructions.size(),
                    stringConstantCount = strings.count {
                        it.ownerClass == normalizeInternalName(classNode.name) &&
                            it.methodName == method.name &&
                            it.methodDescriptor == method.desc
                    },
                    invocationCount = invocations.count {
                        it.ownerClass == normalizeInternalName(classNode.name) &&
                            it.sourceMethod == method.name &&
                            it.sourceDescriptor == method.desc
                    },
                    metrics = methodMetrics(method),
                )
            },
        )
        return ParsedClass(classInfo, strings, invocations, fieldReferences)
    }

    private fun methodMetrics(method: MethodNode): MethodMetrics {
        val branchIndexes = mutableListOf<Int>()
        val gotoIndexes = mutableListOf<Int>()
        val switchIndexes = mutableListOf<Int>()
        val arithmeticIndexes = mutableListOf<Int>()
        val bitwiseIndexes = mutableListOf<Int>()
        val numericConstantIndexes = mutableListOf<Int>()
        var arrayOperations = 0

        method.instructions.toArray().forEachIndexed { index, instruction ->
            when (instruction.opcode) {
                in branchOpcodes -> branchIndexes += index
                Opcodes.GOTO, Opcodes.JSR -> gotoIndexes += index
                Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> switchIndexes += index
                in arithmeticOpcodes -> arithmeticIndexes += index
                in bitwiseOpcodes -> bitwiseIndexes += index
                in arrayOpcodes -> arrayOperations += 1
                in numericConstantOpcodes -> numericConstantIndexes += index
                Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.NEWARRAY -> if (instruction is org.objectweb.asm.tree.IntInsnNode) {
                    numericConstantIndexes += index
                }
            }
        }
        return MethodMetrics(
            branchCount = branchIndexes.size,
            gotoCount = gotoIndexes.size,
            switchCount = switchIndexes.size,
            arithmeticCount = arithmeticIndexes.size,
            bitwiseCount = bitwiseIndexes.size,
            arrayOperationCount = arrayOperations,
            numericConstantCount = numericConstantIndexes.size,
            branchIndexes = branchIndexes,
            gotoIndexes = gotoIndexes,
            switchIndexes = switchIndexes,
            numericConstantIndexes = numericConstantIndexes,
            arithmeticIndexes = arithmeticIndexes,
            bitwiseIndexes = bitwiseIndexes,
        )
    }

    private fun readBounded(input: InputStream, entryName: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var read = input.read(chunk)
        var total = 0L
        while (read >= 0) {
            total += read
            require(total <= MAX_ENTRY_BYTES) { "Class entry is too large: $entryName" }
            buffer.write(chunk, 0, read)
            read = input.read(chunk)
        }
        return buffer.toByteArray()
    }

    private fun sha256File(path: Path): String =
        java.nio.file.Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun manifestMainClass(archivePath: Path): String? =
        JarFile(archivePath.toFile()).use { jar -> jar.manifest?.mainAttributes?.getValue("Main-Class") }

    companion object {
        private const val MAX_ENTRY_BYTES: Long = 128L * 1024 * 1024

        private val branchOpcodes = setOf(
            Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
            Opcodes.IFNULL, Opcodes.IFNONNULL,
        )

        private val arithmeticOpcodes = setOf(
            Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD,
            Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
            Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL,
            Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV,
            Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM,
            Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.IINC,
        )

        private val bitwiseOpcodes = setOf(
            Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR, Opcodes.IUSHR, Opcodes.LUSHR,
            Opcodes.IAND, Opcodes.LAND, Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR, Opcodes.LXOR,
        )

        private val arrayOpcodes = setOf(
            Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
            Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.IASTORE, Opcodes.LASTORE,
            Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE,
            Opcodes.SASTORE, Opcodes.ARRAYLENGTH, Opcodes.NEWARRAY, Opcodes.ANEWARRAY,
            Opcodes.MULTIANEWARRAY,
        )

        private val numericConstantOpcodes = setOf(
            Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
            Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0,
            Opcodes.LCONST_1, Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
            Opcodes.DCONST_0, Opcodes.DCONST_1,
        )
    }
}
