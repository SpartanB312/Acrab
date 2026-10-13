package net.evrt.acrab.app

import kotlinx.serialization.json.*
import net.evrt.acrab.core.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.nio.file.Files
import java.util.Base64
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.*

class AnalysisExtensionToolTest {
    @Test
    fun `changed stored bytes fail cached analysis and identity compares actual bytes`() = withContext { context ->
        val project = import(context, fixture())
        val id = project.getValue("projectId").jsonPrimitive.content
        call(context, "archive_summary", buildJsonObject { put("projectId", id) })
        val expected = project.getValue("sha256").jsonPrimitive.content
        val identity = call(context, "verify_archive_identity", buildJsonObject {
            put("projectId", id); put("expectedSha256", expected.uppercase())
        })
        assertTrue(identity.getValue("matches").jsonPrimitive.boolean)
        assertTrue(identity.getValue("expectedMatches").jsonPrimitive.boolean)
        Files.write(java.nio.file.Path.of(project.getValue("archivePath").jsonPrimitive.content), fixture(final = true))
        val changed = call(context, "verify_archive_identity", buildJsonObject { put("projectId", id) })
        assertFalse(changed.getValue("matches").jsonPrimitive.boolean)
        assertFailsWith<ToolInputException> {
            call(context, "archive_summary", buildJsonObject { put("projectId", id) })
        }
    }

    @Test
    fun `references page exact callsites and expose field reads and writes`() = withContext { context ->
        val id = import(context, fixture()).getValue("projectId").jsonPrimitive.content
        val callers = call(context, "method_references", buildJsonObject {
            put("projectId", id); put("className", "Control"); put("methodName", "choose")
            put("methodDescriptor", "(I)I"); put("limit", 1)
        })
        assertEquals(2, callers.getValue("totalCount").jsonPrimitive.int)
        assertEquals(1, callers.getValue("nextOffset").jsonPrimitive.int)
        assertTrue(callers.getValue("truncated").jsonPrimitive.boolean)
        val fields = call(context, "field_references", buildJsonObject {
            put("projectId", id); put("className", "Control"); put("fieldName", "flag"); put("access", "writes")
        }).getValue("fields").jsonArray
        assertEquals(1, fields.size)
        assertEquals("PUTSTATIC", fields.single().jsonObject.getValue("opcode").jsonPrimitive.content)
        val caller = callers.getValue("calls").jsonArray.single().jsonObject
        val callsite = call(context, "method_call_context", buildJsonObject {
            put("projectId", id); put("className", "Control"); put("methodName", "caller")
            put("methodDescriptor", "()V"); put("instructionIndex", caller.getValue("instructionIndex"))
        })
        assertEquals("1", callsite.getValue("calleeArgumentValues").jsonObject.getValue("0").jsonPrimitive.content)
    }

    @Test
    fun `argument and field assumptions are explicit and do not become global facts`() = withContext { context ->
        val project = import(context, fixture())
        val id = project.getValue("projectId").jsonPrimitive.content
        fun analyze(method: String, arguments: JsonObject = JsonObject(emptyMap()), fields: JsonObject = JsonObject(emptyMap())) =
            call(context, "cfg_constant_propagation", buildJsonObject {
                put("projectId", id); put("className", "Control"); put("methodName", method)
                put("argumentValues", arguments); put("fieldValues", fields)
            })
        assertEquals("unknown", analyze("choose").getValue("branchFacts").jsonArray.first().jsonObject
            .getValue("knownOutcome").jsonPrimitive.content)
        val assumed = analyze("choose", buildJsonObject { put("0", "0") })
        assertEquals("conditional-on-supplied-assumptions", assumed.getValue("scope").jsonPrimitive.content)
        assertEquals("taken", assumed.getValue("branchFacts").jsonArray.first().jsonObject
            .getValue("knownOutcome").jsonPrimitive.content)
        assertEquals(project.getValue("sha256"), assumed.getValue("inputSha256"))
        val field = analyze("guard", fields = buildJsonObject { put("Control.flag:Z", "0") })
        assertEquals("taken", field.getValue("branchFacts").jsonArray.first().jsonObject.getValue("knownOutcome").jsonPrimitive.content)
        assertFailsWith<ToolInputException> { analyze("choose", buildJsonObject { put("9", "0") }) }
        assertEquals("unknown", analyze("guard").getValue("branchFacts").jsonArray.first().jsonObject
            .getValue("knownOutcome").jsonPrimitive.content)
    }

    @Test
    fun `structure comparison detects class and constructor shape after explicit renaming`() = withContext { context ->
        val original = import(context, fixture()).getValue("projectId").jsonPrimitive.content
        val rebuilt = import(context, fixture("Rebuilt", final = true, privateConstructor = true))
            .getValue("projectId").jsonPrimitive.content
        val comparison = call(context, "compare_structure", buildJsonObject {
            put("sourceProjectId", original); put("outputProjectId", rebuilt)
            put("classNames", buildJsonObject { put("Control", "Rebuilt") })
        })
        val locations = comparison.getValue("differences").jsonArray.map { it.jsonObject.getValue("location").jsonPrimitive.content }
        assertTrue("Control.access" in locations)
        assertTrue("Control.method:<init>()V" in locations)
        assertEquals(2, comparison.getValue("differenceCount").jsonPrimitive.int)
        val same = call(context, "compare_structure", buildJsonObject {
            put("sourceProjectId", original); put("outputProjectId", original)
        })
        assertEquals(0, same.getValue("differenceCount").jsonPrimitive.int)
    }

    @Test
    fun `static string recovery rejects unsupported initializers`() = withContext { context ->
        val id = import(context, fixture()).getValue("projectId").jsonPrimitive.content
        val result = call(context, "recover_strings", buildJsonObject { put("projectId", id); put("className", "Control") })
        assertEquals("unsupported", result.getValue("status").jsonPrimitive.content)
        assertTrue(result.getValue("strings").jsonArray.isEmpty())
    }

    private fun withContext(block: (AcrabContext) -> Unit) {
        AcrabContext(AcrabConfig(workspacePath = Files.createTempDirectory("acrab-extensions").toString())).use {
            registerAcrabTools(it); block(it)
        }
    }
    private fun call(context: AcrabContext, name: String, args: JsonObject): JsonObject {
        val result = context.tools.call(name, args)
        assertFalse(result.isError)
        return Json.parseToJsonElement(result.content.single().text).jsonObject
    }
    private fun import(context: AcrabContext, bytes: ByteArray): JsonObject = call(context, "create_project", buildJsonObject {
        put("fileName", "fixture.jar"); put("archiveBase64", Base64.getEncoder().encodeToString(bytes))
    })
    private fun fixture(name: String = "Control", final: Boolean = false, privateConstructor: Boolean = false): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(V21, ACC_PUBLIC or (if (final) ACC_FINAL else 0), name, null, "java/lang/Object", null)
        writer.visitField(ACC_PUBLIC or ACC_STATIC, "flag", "Z", null, null).visitEnd()
        fun method(access: Int, method: String, desc: String, body: org.objectweb.asm.MethodVisitor.() -> Unit) {
            writer.visitMethod(access, method, desc, null, null).apply { visitCode(); body(); visitMaxs(0, 0); visitEnd() }
        }
        method(if (privateConstructor) ACC_PRIVATE else ACC_PUBLIC, "<init>", "()V") {
            visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); visitInsn(RETURN)
        }
        method(ACC_STATIC, "<clinit>", "()V") { visitInsn(ICONST_0); visitFieldInsn(PUTSTATIC, name, "flag", "Z"); visitInsn(RETURN) }
        method(ACC_PUBLIC or ACC_STATIC, "choose", "(I)I") {
            val label = org.objectweb.asm.Label()
            visitVarInsn(ILOAD, 0); visitJumpInsn(IFEQ, label); visitInsn(ICONST_1); visitInsn(IRETURN)
            visitLabel(label); visitInsn(ICONST_0); visitInsn(IRETURN)
        }
        method(ACC_PUBLIC or ACC_STATIC, "guard", "()I") {
            val label = org.objectweb.asm.Label()
            visitFieldInsn(GETSTATIC, name, "flag", "Z"); visitJumpInsn(IFEQ, label); visitInsn(ICONST_1); visitInsn(IRETURN)
            visitLabel(label); visitInsn(ICONST_0); visitInsn(IRETURN)
        }
        method(ACC_PUBLIC or ACC_STATIC, "caller", "()V") {
            repeat(2) { visitInsn(ICONST_1); visitMethodInsn(INVOKESTATIC, name, "choose", "(I)I", false); visitInsn(POP) }
            visitInsn(RETURN)
        }
        writer.visitEnd()
        val output = java.io.ByteArrayOutputStream()
        JarOutputStream(output).use { it.putNextEntry(ZipEntry("$name.class")); it.write(writer.toByteArray()); it.closeEntry() }
        return output.toByteArray()
    }
}
