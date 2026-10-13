package net.evrt.acrab.analysis.android

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction
import com.android.tools.smali.util.ExceptionWithContext
import com.android.tools.smali.dexlib2.util.DexUtil
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.PayloadInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.UnknownInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import net.evrt.acrab.analysis.index.ProjectRecord
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.Adler32
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

internal class DexArchiveReader {
    fun read(project: ProjectRecord): DexArchiveModel {
        val input = Path.of(project.archivePath)
        require(Files.isRegularFile(input)) { "Android input is not a regular file." }
        val inputSize = Files.size(input)
        require(inputSize <= MAX_ARCHIVE_BYTES) {
            "Android input exceeds the ${MAX_ARCHIVE_BYTES / 1024 / 1024} MiB parser limit."
        }
        val dexEntries = if (project.inputType == "dex" || input.fileName.toString().lowercase().endsWith(".dex")) {
            require(inputSize <= MAX_DEX_BYTES) { "Input entry exceeds parser limit." }
            listOf(project.fileName to readBoundedFile(input, MAX_DEX_BYTES))
        } else {
            ZipFile(input.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                require(entries.size <= MAX_ENTRY_COUNT) {
                    "Archive entry count exceeds the $MAX_ENTRY_COUNT limit."
                }
                val totalUncompressed = entries.sumOf { it.size.coerceAtLeast(0L) }
                require(totalUncompressed <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    "Archive uncompressed size exceeds the ${MAX_TOTAL_UNCOMPRESSED_BYTES / 1024 / 1024} MiB limit."
                }
                entries.filter { it.name.substringAfterLast('/').matches(CLASSES_DEX) }
                    .sortedBy { it.name }
                    .map { entry ->
                        requireSafeZipEntry(entry, MAX_DEX_BYTES)
                        entry.name to zip.getInputStream(entry).use { stream ->
                            val data = stream.readNBytes(MAX_DEX_BYTES + 1)
                            require(data.size <= MAX_DEX_BYTES) { "Input entry exceeds parser limit." }
                            data
                        }
                    }
            }
        }
        require(dexEntries.isNotEmpty()) {
            "No classes*.dex entry was found in the Android input."
        }
        return readDexFiles(dexEntries)
    }

    internal fun readDexFiles(dexEntries: List<Pair<String, ByteArray>>): DexArchiveModel {
        require(dexEntries.isNotEmpty() && dexEntries.size <= MAX_ENTRY_COUNT) { "DEX entry count exceeds parser limit or is empty." }
        require(dexEntries.all { it.second.size <= MAX_DEX_BYTES } && dexEntries.sumOf { it.second.size.toLong() } <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
            "DEX entries exceed parser byte limits."
        }
        val budget = ParseBudget()
        return DexArchiveModel(dexEntries.map { (name, data) -> parse(name, data, budget) })
    }

    fun readManifest(project: ProjectRecord): Pair<String?, ByteArray?> {
        val entry = readManifestEntries(project).firstOrNull() ?: return null to null
        return entry.first to entry.second
    }

    fun readManifestEntries(project: ProjectRecord): List<Pair<String, ByteArray>> {
        val input = Path.of(project.archivePath)
        if (project.inputType == "dex") return emptyList()
        if (project.inputType == "xml") {
            return listOf(project.fileName to readBoundedFile(input, MAX_MANIFEST_BYTES))
        }
        require(Files.isRegularFile(input) && Files.size(input) <= MAX_ARCHIVE_BYTES) {
            "Android manifest input is not a regular file within the parser limit."
        }
        return ZipFile(input.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            require(entries.size <= MAX_ENTRY_COUNT) {
                "Archive entry count exceeds the $MAX_ENTRY_COUNT limit."
            }
            require(entries.sumOf { it.size.coerceAtLeast(0L) } <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                "Archive uncompressed size exceeds the ${MAX_TOTAL_UNCOMPRESSED_BYTES / 1024 / 1024} MiB limit."
            }
            entries.filter { it.name == "AndroidManifest.xml" || AAB_MANIFEST_PATH.matches(it.name) }
                .sortedWith(compareBy({ if (it.name.startsWith("base/manifest/")) 0 else 1 }, { it.name }))
                .map { entry ->
                    requireSafeZipEntry(entry, MAX_MANIFEST_BYTES)
                    entry.name to zip.getInputStream(entry).use { stream ->
                        val data = stream.readNBytes(MAX_MANIFEST_BYTES + 1)
                        require(data.size <= MAX_MANIFEST_BYTES) { "Input entry exceeds parser limit." }
                        data
                    }
                }
        }
    }

    private fun parse(name: String, bytes: ByteArray, budget: ParseBudget): DexFileModel {
        return try {
            parseVerifiedDex(name, bytes, budget)
        } catch (error: ExceptionWithContext) {
            throw IllegalArgumentException("Malformed DEX data in $name: ${error.message}", error)
        } catch (error: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Malformed DEX data in $name: ${error.message}", error)
        }
    }

    private fun parseVerifiedDex(name: String, bytes: ByteArray, budget: ParseBudget): DexFileModel {
        require(bytes.isNotEmpty()) { "Invalid empty DEX file $name." }

        val dexVersion = runCatching { DexUtil.verifyDexHeader(ByteArrayInputStream(bytes)) }.getOrElse { error ->
            throw IllegalArgumentException("Malformed or unsupported DEX header in $name: ${error.message}", error)
        }
        require(dexVersion in SUPPORTED_VERSIONS) {
            "Unsupported DEX version " + "%03d".format(dexVersion) +
                " in $name; supported classic versions are 035, 037, 038, 039, 040."
        }

        val dex = DexBackedDexFile.fromInputStream(Opcodes.forDexVersion(dexVersion), ByteArrayInputStream(bytes))

        require(dex.fileSize == bytes.size) {
            "DEX file_size ${dex.fileSize} does not match input size ${bytes.size} in $name."
        }
        requireChecksumAndSignature(name, bytes, dexVersion)
        require(dex.stringSection.size <= MAX_STRINGS) { "DEX string pool exceeds parser limits." }
        budget.strings += dex.stringSection.size
        require(budget.strings <= MAX_STRINGS) { "Android string pools exceed parser limits." }
        budget.classes += dex.classSection.size
        require(budget.classes <= MAX_CLASSES) { "Android class count exceeds parser limits." }
        val allStrings = List(dex.stringSection.size) { index -> dex.stringSection.get(index).orEmpty() }

        val classes = dex.classes.sortedBy { it.type }.map { mapClass(name, it, budget) }
        val methods = classes.flatMap { it.model.methods }
        val fields = classes.flatMap { it.model.fields }
        val stringRefs = classes.flatMap { it.stringReferences }
        val invocations = classes.flatMap { it.invocations }
        require(
            methods.sumOf { method -> method.code?.instructions?.size ?: 0 } <= MAX_INSTRUCTIONS,
        ) { "DEX instruction count exceeds parser limits in $name." }

        return DexFileModel(
            name, bytes, allStrings,
            classes.map { it.model }, fields, methods, stringRefs.sortedWith(compareBy({ it.dexFile }, { it.instructionIndex ?: -1 })),
            invocations.sortedWith(compareBy({ it.dexFile }, { it.instructionIndex })),
        )
    }

    private fun mapClass(dexName: String, classDef: DexBackedClassDef, budget: ParseBudget): MappedClass {
        val className = typeName(classDef.type)
        val strings = mutableListOf<String>()
        val stringRefs = mutableListOf<AndroidStringReference>()
        val invocations = mutableListOf<AndroidInvocationReference>()
        val classFields = mutableListOf<DexFieldModel>()
        val classMethods = mutableListOf<DexMethodModel>()

        classDef.fields.forEach { field ->
            require(++budget.fields <= MAX_MEMBERS) { "Android field count exceeds parser limits." }
            classFields += DexFieldModel(
                ownerClass = className,
                name = field.name,
                descriptor = field.type,
                accessFlags = accessFlags(field.accessFlags),
            )
        }

        classDef.methods.forEach { method ->
            require(++budget.methods <= MAX_MEMBERS) { "Android method count exceeds parser limits." }
            val implementation = method.implementation
            var code: DexCodeModel? = null
            if (implementation != null) {
                val instructions = mutableListOf<AndroidInstructionInfo>()
                var offset = 0
                val declaredCodeUnits = implementation.instructionsSize
                require(declaredCodeUnits >= 0 && declaredCodeUnits.toLong() <= MAX_CODE_UNITS - budget.codeUnits) {
                    "Android code units exceed parser limits."
                }
                budget.codeUnits += declaredCodeUnits
                implementation.instructions.forEach { instruction ->
                    require(++budget.instructions <= MAX_INSTRUCTIONS) { "Android instructions exceed parser limits." }
                    require(instruction.codeUnits > 0 && instruction.codeUnits <= declaredCodeUnits - offset) {
                        "Truncated DEX instruction ${instruction.opcode.name} at code unit $offset in $className->${method.name}."
                    }
                    val model = mapInstruction(
                        dexName, className, method, instruction, instructions.size, offset,
                        strings, stringRefs, invocations,
                    )
                    instructions += model
                    offset += instruction.codeUnits
                }
                require(offset == declaredCodeUnits) { "DEX instruction widths do not cover the declared code item." }
                val byOffset = instructions.associateBy(AndroidInstructionInfo::codeOffset)
                val resolvedInstructions = instructions.map { instruction ->
                    val targetOffset = instruction.branchTargetOffset ?: return@map instruction
                    val target = requireNotNull(byOffset[targetOffset]) {
                        "DEX branch target $targetOffset is not an instruction boundary in $className->${method.name}."
                    }
                    val payloadName = when (instruction.opcodeName) {
                        "packed-switch" -> "packed-switch-payload"
                        "sparse-switch" -> "sparse-switch-payload"
                        "fill-array-data" -> "array-payload"
                        else -> null
                    }
                    if (payloadName != null) {
                        require(target.isPayload && target.opcodeName == payloadName && targetOffset % 2 == 0) {
                            "DEX payload target $targetOffset has an invalid type or alignment."
                        }
                        target.payloadSwitchElements.forEach { element ->
                            val switchTarget = byOffset[instruction.codeOffset + element.targetOffsetDelta]
                            require(switchTarget != null && !switchTarget.isPayload) {
                                "DEX switch key ${element.key} does not target an executable instruction boundary."
                            }
                        }
                    } else {
                        require(!target.isPayload) { "DEX branch targets a data payload." }
                    }
                    instruction.copy(branchTargetIndex = target.index)
                }
                code = DexCodeModel(
                    registerCount = implementation.registerCount,
                    tryBlockCount = implementation.tryBlocks.size,
                    instructions = resolvedInstructions,
                )
            }
            val methodModel = DexMethodModel(
                dexFile = dexName,
                ownerClass = className,
                name = method.name,
                descriptor = method.descriptor(),
                accessFlags = accessFlags(method.accessFlags),
                code = code,
            )
            classMethods += methodModel
        }

        return MappedClass(
            DexClassModel(
                dexFile = dexName,
                className = className,
                type = classType(classDef.accessFlags),
                accessFlags = accessFlags(classDef.accessFlags),
                superClass = classDef.superclass,
                interfaces = classDef.interfaces.toList(),
                sourceFile = classDef.sourceFile,
                fields = classFields,
                methods = classMethods,
            ),
            strings,
            stringRefs,
            invocations,
        )
    }

    private fun mapInstruction(
        dexName: String,
        owner: String,
        method: Method,
        instruction: Instruction,
        index: Int,
        offset: Int,
        strings: MutableList<String>,
        stringRefs: MutableList<AndroidStringReference>,
        invocations: MutableList<AndroidInvocationReference>,
    ): AndroidInstructionInfo {
        val opcode = instruction.opcode
        if (instruction is UnknownInstruction) {
            throw IllegalArgumentException(
                "Unsupported DEX opcode 0x${instruction.originalOpcode.toString(16)} in $owner->${method.name}${method.descriptor()}.",
            )
        }
        require(!opcode.odexOnly()) {
            "Optimized DEX opcode ${opcode.name} is not supported in classic DEX: $owner->${method.name}${method.descriptor()}."
        }
        var referenceType: String? = null
        var reference: String? = null
        var literal: String? = null
        var branchTargetOffset: Int? = null

        if (instruction is WideLiteralInstruction) {
            literal = instruction.wideLiteral.toString()
        }
        if (instruction is OffsetInstruction) {
            branchTargetOffset = offset + instruction.codeOffset
        }
        if (instruction is ReferenceInstruction) {
            when (instruction.referenceType) {
                ReferenceType.STRING -> {
                    val value = (instruction.reference as StringReference).string
                    referenceType = "string"
                    reference = value
                    strings += value
                    stringRefs += AndroidStringReference(
                        dexFile = dexName,
                        value = value,
                        ownerClass = owner,
                        methodName = method.name,
                        methodDescriptor = method.descriptor(),
                        instructionIndex = index,
                    )
                }
                ReferenceType.METHOD -> {
                    val target = instruction.reference as MethodReference
                    referenceType = "method"
                    reference = target.render()
                    invocations += AndroidInvocationReference(
                        dexFile = dexName,
                        ownerClass = owner,
                        sourceMethod = method.name,
                        sourceDescriptor = method.descriptor(),
                        targetOwner = typeName(target.definingClass),
                        targetName = target.name,
                        targetDescriptor = "${target.parameterTypes.joinToString("", "(", ")")}${target.returnType}",
                        opcode = opcode.name.lowercase(),
                        instructionIndex = index,
                    )
                }
                ReferenceType.FIELD -> {
                    val target = instruction.reference as FieldReference
                    referenceType = "field"
                    reference = "${target.definingClass}->${target.name}:${target.type}"
                }
                ReferenceType.TYPE -> {
                    referenceType = "type"
                    reference = (instruction.reference as TypeReference).type
                }
                else -> {
                    referenceType = "unsupported-reference"
                    reference = instruction.reference.toString()
                }
            }
        }

        return AndroidInstructionInfo(
            index = index,
            codeOffset = offset,
            opcodeName = opcode.name.lowercase().replace('_', '-'),
            codeUnits = instruction.codeUnits,
            registers = registers(instruction),
            literal = literal,
            referenceType = referenceType,
            reference = reference,
            branchTargetOffset = branchTargetOffset,
            rawCodeUnits = (instruction as DexBackedInstruction).let { backed ->
                List(instruction.codeUnits) { unit ->
                    backed.dexFile.dataBuffer.readUshort(backed.instructionStart + unit * 2)
                }
            },
            isPayload = instruction is PayloadInstruction,
            payloadElementWidth = (instruction as? ArrayPayload)?.elementWidth,
            payloadElements = (instruction as? ArrayPayload)?.arrayElements?.map(Number::toString).orEmpty(),
            payloadSwitchElements = (instruction as? SwitchPayload)?.switchElements?.map { element ->
                AndroidSwitchPayloadElement(element.key, element.offset)
            }.orEmpty(),
        )
    }

    private fun registers(instruction: Instruction): List<Int> {
        val model = when (instruction) {
            is FiveRegisterInstruction -> {
                val count = instruction.registerCount
                listOfNotNull(
                    instruction.registerC.takeIf { count > 0 },
                    instruction.registerD.takeIf { count > 1 },
                    instruction.registerE.takeIf { count > 2 },
                    instruction.registerF.takeIf { count > 3 },
                    instruction.registerG.takeIf { count > 4 },
                )
            }
            is ThreeRegisterInstruction -> listOf(instruction.registerA, instruction.registerB, instruction.registerC)
            is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
            is OneRegisterInstruction -> listOf(instruction.registerA)
            is RegisterRangeInstruction -> {
                if (instruction.registerCount == 0) emptyList()
                else List(instruction.registerCount) { instruction.startRegister + it }
            }
            else -> emptyList()
        }
        require(model.size <= MAX_VISIBLE_REGISTERS) { "Instruction expands beyond the register reporting budget." }
        return model
    }

    private fun Method.descriptor(): String {
        val parameters = parameterTypes.joinToString("", "(", ")") { it }
        return "$parameters$returnType"
    }

    private fun MethodReference.render(): String {
        val parameters = parameterTypes.joinToString("", "(", ")") { it }
        return "${typeName(definingClass)}->$name$parameters$returnType"
    }

    private fun typeName(descriptor: String): String = when (descriptor.firstOrNull()) {
        '[' -> descriptor
        'L' -> descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
        else -> descriptor
    }

    private fun classType(flags: Int): String = when {
        flags and AccessFlags.ANNOTATION.value != 0 -> "annotation"
        flags and AccessFlags.INTERFACE.value != 0 -> "interface"
        flags and AccessFlags.ENUM.value != 0 -> "enum"
        else -> "class"
    }

    private fun accessFlags(flags: Int): List<String> {
        val values = AccessFlags.values()
        return values.filter { flags and it.value != 0 }.map { it.name.lowercase().replace('_', '-') }
    }

    private fun readBoundedFile(input: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(input) && Files.size(input) <= limit) { "Input entry exceeds parser limit." }
        return Files.newInputStream(input).use { stream ->
            stream.readNBytes(limit + 1).also { data ->
                require(data.size <= limit) { "Input entry exceeds parser limit." }
            }
        }
    }

    private fun requireSafeZipEntry(entry: ZipEntry, limit: Int) {
        require(entry.size >= 0 && entry.size <= limit) { "Archive entry exceeds parser limit: ${entry.name}" }
        val compressed = entry.compressedSize.takeIf { it > 0 } ?: entry.size
        require(compressed <= 0 || entry.size.toDouble() / compressed.toDouble() <= MAX_COMPRESSION_RATIO) {
            "Archive entry compression ratio exceeds parser limit: ${entry.name}"
        }
    }

    private fun requireChecksumAndSignature(name: String, bytes: ByteArray, dexVersion: Int) {
        if (dexVersion >= 41) {
            // Classic DEX checksum/signature contracts are not applied to DEX containers here.
            return
        }
        require(bytes.size >= SIGNATURE_DATA_OFFSET) {
            "Invalid DEX header size in $name."
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(SIGNATURE_DATA_OFFSET, bytes.size))
        val embeddedSignature = bytes.copyOfRange(SIGNATURE_OFFSET, SIGNATURE_OFFSET + SIGNATURE_SIZE)
        require(digest.contentEquals(embeddedSignature)) { "DEX signature mismatch in $name." }

        val checksum = Adler32()
        checksum.update(bytes, CHECKSUM_DATA_OFFSET, bytes.size - CHECKSUM_DATA_OFFSET)
        val embeddedChecksum = ((bytes[11].toInt() and 0xff) shl 24) or
            ((bytes[10].toInt() and 0xff) shl 16) or
            ((bytes[9].toInt() and 0xff) shl 8) or
            (bytes[8].toInt() and 0xff)
        require(checksum.value.toInt() == embeddedChecksum) { "DEX checksum mismatch in $name." }
    }

    private class MappedClass(
        val model: DexClassModel,
        val strings: List<String>,
        val stringReferences: List<AndroidStringReference>,
        val invocations: List<AndroidInvocationReference>,
    )

    private class ParseBudget {
        var strings = 0L
        var classes = 0L
        var fields = 0L
        var methods = 0L
        var instructions = 0L
        var codeUnits = 0L
    }

    companion object {
        const val PARSER_VERSION = "acrab-android-dex-dexlib2-3.0.10-v2"
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val MAX_DEX_BYTES = 128 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
        const val MAX_ENTRY_COUNT = 10_000
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
        private const val MAX_STRINGS = 1_000_000
        private const val MAX_INSTRUCTIONS = 2_000_000
        private const val MAX_CODE_UNITS = 8_000_000L
        private const val MAX_CLASSES = 100_000
        private const val MAX_MEMBERS = 1_000_000
        private const val MAX_COMPRESSION_RATIO = 200.0
        private const val MAX_VISIBLE_REGISTERS = 256
        private val SUPPORTED_VERSIONS = setOf(35, 37, 38, 39, 40)
        private val CLASSES_DEX = Regex("classes(\\d*)\\.dex")
        private val AAB_MANIFEST_PATH = Regex("^(?:[^/]+)/manifest/AndroidManifest.xml$")
        private const val CHECKSUM_OFFSET = 8
        private const val CHECKSUM_DATA_OFFSET = 12
        private const val SIGNATURE_OFFSET = 12
        private const val SIGNATURE_SIZE = 20
        private const val SIGNATURE_DATA_OFFSET = 32
    }
}
