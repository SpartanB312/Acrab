package net.evrt.antarelite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import net.evrt.antarelite.core.ToolInputException
import net.evrt.antarelite.analysis.index.ProjectRepository
import net.evrt.antarelite.analysis.index.JvmIndexService
import net.evrt.antarelite.analysis.patch.BytecodePatchApplyResult
import net.evrt.antarelite.analysis.patch.BytecodePatchApplyRequest
import net.evrt.antarelite.analysis.patch.BytecodePatchRequest
import net.evrt.antarelite.analysis.patch.BytecodePatchService
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the finder and patch findings: a real constant-table XOR string cipher
 * must be detected, `limit` must not change the reported total, and patch previews must report
 * stable instruction-index targets with a counter that matches the change array.
 */
class EvidenceAndPatchToolTest {
    @Test
    fun `detects a constant-table xor string cipher`() {
        withProject { context, projectId ->
            val result = json(context, "find_string_transforms", """{"projectId":"$projectId"}""")
            val findings = result["findings"]!!.jsonArray
            assertTrue(findings.isNotEmpty(), "constant-table XOR decoder was not detected")
            val cipher = findings.map { it.jsonObject }
                .single { it["subpattern"]!!.jsonPrimitive.content == "constant-table-xor-decoder" }
            assertEquals("string-transform", cipher["category"]!!.jsonPrimitive.content)
            assertTrue(
                cipher["evidence"]!!.jsonArray.any { it.jsonPrimitive.content.startsWith("xorKeys=") },
                cipher["evidence"].toString(),
            )
        }
    }

    @Test
    fun `limit is a result cap and candidate count stays the true total`() {
        withProject { context, projectId ->
            val capped = json(context, "evidence_summary", """{"projectId":"$projectId","limit":1}""")
            val full = json(context, "evidence_summary", """{"projectId":"$projectId","limit":500}""")

            assertEquals(full["candidateCount"]!!.jsonPrimitive.content, capped["candidateCount"]!!.jsonPrimitive.content)
            assertEquals(1, capped["findings"]!!.jsonArray.size)
            assertTrue(capped["truncated"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(capped["scanComplete"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(full["findings"]!!.jsonArray.size, full["candidateCount"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `non-positive limit is rejected`() {
        withProject { context, projectId ->
            for (tool in listOf("find_string_transforms", "evidence_summary")) {
                assertFailsWith<ToolInputException> {
                    call(context, tool, """{"projectId":"$projectId","limit":0}""")
                }
            }
        }
    }

    @Test
    fun `finder catalog publishes the category vocabulary and subpatterns`() {
        withProject { context, projectId ->
            val catalog = json(context, "finder_catalog", "{}")
            val categories = catalog["categories"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                listOf("string-transform", "number", "opaque-guard", "flattening", "invokedynamic"),
                categories,
            )
            val subpatterns = catalog["subpatterns"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(subpatterns.contains("constant-table-xor-decoder"))
            assertTrue(subpatterns.contains("dead-branch-guard"))
        }
    }

    @Test
    fun `category filter maps to summary categories`() {
        withProject { context, projectId ->
            val filtered = json(
                context,
                "evidence_summary",
                """{"projectId":"$projectId","categoryFilter":["string-transform"]}""",
            )
            val categories = filtered["categorySummary"]!!.jsonObject.keys
            assertEquals(setOf("string-transform"), categories)
        }
    }

    @Test
    fun `search strings ranks ciphertext against readable literals`() {
        withProject { context, projectId ->
            val strings = Json.parseToJsonElement(
                call(context, "search_strings", """{"projectId":"$projectId"}"""),
            ).jsonArray
                .map { it.jsonObject }
            assertTrue(strings.isNotEmpty())
            // Every constant carries a ranking hint so callers need no heuristic of their own.
            assertTrue(strings.all { it["classification"] != null }, "missing classification: $strings")
            assertTrue(strings.all { it["entropyBitsPerChar"] != null })
            assertTrue(strings.all { it["printableRatio"] != null })

            val staged = strings.first { it["value"]!!.jsonPrimitive.content.contains("visible-marker") }
            assertEquals("plain", staged["classification"]!!.jsonPrimitive.content)

            val ciphertext = strings.first { it["classification"]!!.jsonPrimitive.content == "likely-encrypted" }
            assertTrue(ciphertext["printableRatio"]!!.jsonPrimitive.content.toDouble() < 0.95)
        }
    }

    @Test
    fun `patch preview reports stable instruction index targets and a matching counter`() {
        withProject { context, projectId ->
            // Anchor on the first real LDC in the method so the replacement is applicable.
            val ldcIndex = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }["index"]!!
                .jsonPrimitive.content
            val request = """
                {"className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "operations":[{"operation":"replace_constant","anchorInstructionIndex":$ldcIndex,
                                "arguments":{"value":"replaced"}}]}
            """.trimIndent()
            val first = preview(context, projectId, request)
            val second = preview(context, projectId, request)

            assertEquals(
                first["instructionChanges"].toString(),
                second["instructionChanges"].toString(),
                "patch diff is not stable across identical calls",
            )
            val changes = first["instructionChanges"]!!.jsonArray
            assertEquals(
                changes.size,
                first["changedInstructionCount"]!!.jsonPrimitive.content.toInt(),
                "changedInstructionCount must describe instructionChanges",
            )
            assertEquals(
                first["semanticChangeCount"]!!.jsonPrimitive.content.toInt() +
                    first["structuralChangeCount"]!!.jsonPrimitive.content.toInt(),
                changes.size,
            )
            // Targets must be instruction indexes, never identity-hash labels.
            val branchLines = first["afterInstructions"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .filter { it.contains(Regex(":\\s+(IF|GOTO)")) }
            assertTrue(branchLines.isNotEmpty(), "fixture has no branches")
            assertTrue(
                branchLines.none { it.contains("@") },
                "branch targets still use opaque labels: ${branchLines.take(3)}",
            )
            assertTrue(first.containsKey("patchMetadataAvailability"))
            assertFalse(first.containsKey("metadataAvailability"))
        }
    }

    @Test
    fun `schema v1 patch request reports the v2 migration path`() {
        withProject { context, projectId ->
            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "method_patch_draft",
                    """{"projectId":"$projectId","request":{"schemaVersion":1,"className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;","operations":[{"operation":"delete_insn","anchorInstructionIndex":0}]}}""",
                )
            }
            assertTrue(failure.message!!.contains("requires v2"), failure.message)
            assertTrue(failure.message!!.contains("method_patch_draft"), failure.message)
        }
    }

    @Test
    fun `preview requires all v2 request fingerprints`() {
        withProject { context, projectId ->
            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "method_patch_preview",
                    """{"projectId":"$projectId","request":{"className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;","operations":[{"operation":"delete_insn","anchorInstructionIndex":0,"anchorOpcode":"NOP"}]}}""",
                )
            }
            assertTrue(failure.message!!.contains("inputSha256"), failure.message)
        }
    }

    @Test
    fun `a single constant replacement does not cascade into branch-target noise`() {
        withProject { context, projectId ->
            // The reference run reported one semantic change against an array of fourteen entries,
            // thirteen of which were identical mnemonics differing only in synthetic labels.
            val ldcIndex = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }["index"]!!
                .jsonPrimitive.content

            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"run",
                 "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "operations":[{"operation":"replace_constant","anchorInstructionIndex":$ldcIndex,
                                "arguments":{"value":"replaced"}}]}
                """.trimIndent(),
            )
            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(0, preview["instructionCountDelta"]!!.jsonPrimitive.content.toInt())
            assertTrue(preview["verificationChecksPassed"]!!.jsonArray.any {
                it.jsonPrimitive.content == "typed operand-stack dataflow"
            })
            assertEquals(
                1,
                preview["instructionChanges"]!!.jsonArray.size,
                "one constant replacement cascaded into unrelated entries",
            )
            assertEquals(1, preview["changedInstructionCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, preview["semanticChangeCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, preview["structuralChangeCount"]!!.jsonPrimitive.content.toInt())
            assertTrue(
                preview["afterInstructions"]!!.jsonArray.none { it.jsonPrimitive.content.contains("UNKNOWN") },
                "pseudo-node leaked into the instruction list",
            )
        }
    }

    @Test
    fun `patch indexes match evidence after stack map frames`() {
        withProject { context, projectId ->
            val bytecode = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val anchor = bytecode.last { instruction ->
                instruction["opcodeName"]!!.jsonPrimitive.content == "LDC"
            }
            val request = """
                {"className":"Cipher","methodName":"run",
                 "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "operations":[{"operation":"replace_constant",
                   "anchorInstructionIndex":${anchor["index"]!!.jsonPrimitive.content},
                   "arguments":{"value":"after-frame"}}]}
            """.trimIndent()
            val preview = preview(context, projectId, request)

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(preview["afterInstructions"]!!.jsonArray.any {
                it.jsonPrimitive.content.contains("after-frame")
            })
        }
    }

    @Test
    fun `batch operations retain anchors from the original method`() {
        withProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }
            val store = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ASTORE" }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V",
                 "operations":[
                   {"operation":"delete_insn","anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content}},
                   {"operation":"delete_insn","anchorInstructionIndex":${store["index"]!!.jsonPrimitive.content}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertEquals(1, preview["afterInstructions"]!!.jsonArray.size)
            assertTrue(preview["afterInstructions"]!!.jsonArray.single().jsonPrimitive.content.endsWith("RETURN"))
        }
    }

    @Test
    fun `patch local name follows the variable slot and active debug scope`() {
        withProject(debug = true) { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val load = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ALOAD" }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V",
                 "operations":[{"operation":"patch_local_name",
                   "anchorInstructionIndex":${load["index"]!!.jsonPrimitive.content},
                   "arguments":{"name":"renamed","descriptor":"Ljava/lang/String;"}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(preview["operations"]!!.jsonArray.single().jsonObject["beforeEvidence"]!!
                .jsonPrimitive.content.contains("name=unused"), preview.toString())
            assertTrue(preview["operations"]!!.jsonArray.single().jsonObject["afterEvidence"]!!
                .jsonPrimitive.content.contains("name=renamed"), preview.toString())
            assertTrue(preview["debugChanges"]!!.jsonArray.any {
                it.jsonObject["before"]?.jsonPrimitive?.content?.contains("name=unused") == true &&
                    it.jsonObject["after"]?.jsonPrimitive?.content?.contains("name=renamed") == true
            }, preview.toString())
            assertTrue(
                preview["sourceDebugMetadataSha256"]!!.jsonPrimitive.content !=
                    preview["patchedDebugMetadataSha256"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `patch line table updates the entry covering its anchor`() {
        withProject(debug = true) { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val store = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ASTORE" }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V",
                 "operations":[{"operation":"patch_line_table",
                   "anchorInstructionIndex":${store["index"]!!.jsonPrimitive.content},
                   "arguments":{"line":"99"}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(preview["debugChanges"]!!.jsonArray.any {
                it.jsonObject["after"]?.jsonPrimitive?.content?.contains("line=99") == true
            }, preview.toString())
        }
    }

    @Test
    fun `jump replacement uses an explicit evidence label target`() {
        withProject { context, projectId ->
            val bytecode = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"branch","methodDescriptor":"(I)I"}""",
            )
            val branch = bytecode["instructions"]!!.jsonArray.map { it.jsonObject }
                .first { it["opcodeName"]!!.jsonPrimitive.content.startsWith("IF") }
            val target = branch["jumpTargetIndex"]!!.jsonPrimitive.content
            assertTrue(bytecode["labelIndexes"]!!.jsonArray.any { it.jsonPrimitive.content == target })
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"branch","methodDescriptor":"(I)I",
                 "operations":[{"operation":"replace_insn","anchorInstructionIndex":${branch["index"]!!.jsonPrimitive.content},
                   "arguments":{"kind":"jump","opcode":"${branch["opcodeName"]!!.jsonPrimitive.content}",
                                "targetInstructionIndex":"$target"}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
        }
    }

    @Test
    fun `explicit jump destination change is semantic`() {
        withProject { context, projectId ->
            val bytecode = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"branch","methodDescriptor":"(I)I"}""",
            )
            val branch = bytecode["instructions"]!!.jsonArray.map { it.jsonObject }
                .first { it["opcodeName"]!!.jsonPrimitive.content.startsWith("IF") }
            val oldTarget = branch["jumpTargetIndex"]!!.jsonPrimitive.content
            val newTarget = bytecode["labelIndexes"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .first { it != oldTarget }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"branch","methodDescriptor":"(I)I",
                 "operations":[{"operation":"replace_insn","anchorInstructionIndex":${branch["index"]!!.jsonPrimitive.content},
                   "arguments":{"kind":"jump","opcode":"${branch["opcodeName"]!!.jsonPrimitive.content}",
                                "targetInstructionIndex":"$newTarget"}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(preview["semanticChanges"]!!.jsonArray.any {
                it.jsonObject["before"]?.jsonPrimitive?.content?.contains(": IF") == true &&
                    it.jsonObject["category"]?.jsonPrimitive?.content == "semantic"
            }, preview.toString())
        }
    }

    @Test
    fun `draft rejects opcode kind mismatch and out of range variable slots`() {
        withProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"branch","methodDescriptor":"(I)I"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ICONST_3" }
            val load = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ILOAD" }
            val draft = json(
                context,
                "method_patch_draft",
                """
                {"projectId":"$projectId","request":{
                 "className":"Cipher","methodName":"branch","methodDescriptor":"(I)I",
                 "operations":[
                   {"operation":"replace_insn","anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
                    "arguments":{"kind":"simple","opcode":"INVOKESTATIC"}},
                   {"operation":"replace_insn","anchorInstructionIndex":${load["index"]!!.jsonPrimitive.content},
                    "arguments":{"kind":"var","opcode":"ILOAD","operand":"65535"}}]}}
                """.trimIndent(),
            )

            assertFalse(draft["supported"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(draft["operations"]!!.jsonArray.all {
                it.jsonObject["status"]?.jsonPrimitive?.content == "invalid"
            }, draft.toString())
        }
    }

    @Test
    fun `debug metadata operation conflicts with destructive instruction edit`() {
        withProject(debug = true) { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val load = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ALOAD" }
            val index = load["index"]!!.jsonPrimitive.content
            val draft = json(
                context,
                "method_patch_draft",
                """
                {"projectId":"$projectId","request":{
                 "className":"Cipher","methodName":"eraseUnused","methodDescriptor":"()V",
                 "operations":[
                   {"operation":"replace_insn","anchorInstructionIndex":$index,
                    "arguments":{"kind":"var","opcode":"ALOAD","operand":"0"}},
                   {"operation":"patch_local_name","anchorInstructionIndex":$index,
                    "arguments":{"name":"renamed","descriptor":"Ljava/lang/String;"}}]}}
                """.trimIndent(),
            )

            assertFalse(draft["supported"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(draft["operations"]!!.jsonArray.last().jsonObject["failureReason"]!!
                .jsonPrimitive.content.contains("conflicts"), draft.toString())
        }
    }

    @Test
    fun `preview switch evidence includes every case target`() {
        withProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"decode","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val operand = instructions.first {
                it["opcodeName"]!!.jsonPrimitive.content == "BIPUSH" &&
                    it["operand"]?.jsonPrimitive?.content == "7"
            }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"decode","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "operations":[{"operation":"replace_insn","anchorInstructionIndex":${operand["index"]!!.jsonPrimitive.content},
                   "arguments":{"kind":"int","opcode":"BIPUSH","operand":"7"}}]}
                """.trimIndent(),
            )
            val switch = preview["beforeInstructions"]!!.jsonArray.map { it.jsonPrimitive.content }
                .first { it.contains("SWITCH") }

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(switch.contains("cases=["), switch)
            assertTrue(switch.contains("0->") && switch.contains("1->") && switch.contains("default->"), switch)
        }
    }

    @Test
    fun `deleting instructions before a branch reports target renumbering as structural`() {
        withProject { context, projectId ->
            val bytecode = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"branch","methodDescriptor":"(I)I"}""",
            )
            val instructions = bytecode["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ICONST_3" }
            val store = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "ISTORE" }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"branch","methodDescriptor":"(I)I",
                 "operations":[
                   {"operation":"delete_insn","anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content}},
                   {"operation":"delete_insn","anchorInstructionIndex":${store["index"]!!.jsonPrimitive.content}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(preview["structuralChanges"]!!.jsonArray.any {
                it.jsonObject["before"]?.jsonPrimitive?.content?.contains(": IF") == true
            }, preview.toString())
            assertFalse(preview["semanticChanges"]!!.jsonArray.any {
                it.jsonObject["before"]?.jsonPrimitive?.content?.contains(": IF") == true
            }, preview.toString())
        }
    }

    @Test
    fun `uppercase ldc replacement accepts an empty string and reports exact operation evidence`() {
        withProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.first {
                it["opcodeName"]!!.jsonPrimitive.content == "LDC" &&
                    it["operand"]?.jsonPrimitive?.content == "visible-marker"
            }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "operations":[{"operation":"replace_insn","anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
                   "arguments":{"kind":"LDC","opcode":"LDC","operand":""}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            val operation = preview["operations"]!!.jsonArray.single().jsonObject
            assertTrue(operation["beforeEvidence"]!!.jsonPrimitive.content.contains("visible-marker"))
            assertTrue(operation["afterEvidence"]!!.jsonPrimitive.content.contains("\"\""))
        }
    }

    @Test
    fun `large method diff truncates while preserving operation evidence`() {
        withLargeProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"large","methodDescriptor":"()V"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.first { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"large","methodDescriptor":"()V",
                 "operations":[{"operation":"replace_constant","anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
                   "arguments":{"value":"changed"}}]}
                """.trimIndent(),
            )

            assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(preview["diffTruncated"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
            assertTrue(preview["operations"]!!.jsonArray.single().jsonObject["beforeEvidence"]!!
                .jsonPrimitive.content.contains("large-0"), preview.toString())
            assertTrue(preview["operations"]!!.jsonArray.single().jsonObject["afterEvidence"]!!
                .jsonPrimitive.content.contains("changed"), preview.toString())
        }
    }

    @Test
    fun `preview rejects a patch with an invalid operand stack`() {
        withProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"longValue","methodDescriptor":"()J"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.first { it["opcodeName"]!!.jsonPrimitive.content.startsWith("LDC") }
            val preview = preview(
                context,
                projectId,
                """
                {"className":"Cipher","methodName":"longValue","methodDescriptor":"()J",
                "operations":[{"operation":"delete_insn",
                   "anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content}}]}
                """.trimIndent(),
            )

            assertEquals("verification-failed", preview["status"]!!.jsonPrimitive.content)
            assertEquals("false", preview["supported"]!!.jsonPrimitive.content)
            assertEquals("", preview["previewId"]!!.jsonPrimitive.content)
            assertTrue(preview["verifierDiagnostics"]!!.jsonArray.isNotEmpty())
        }
    }

    @Test
    fun `apply publishes the class bytes verified by preview`() {
        withProject { context, projectId ->
            assertArchivePatchPublished(context, projectId, hasManifest = true)
        }
    }

    @Test
    fun `apply preserves archives without a manifest`() {
        withFixture(manifest = false) { context, projectId ->
            assertArchivePatchPublished(context, projectId, hasManifest = false)
        }
    }

    @Test
    fun `concurrent service instances consume a preview once`() {
        withProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.last { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }
            val request = """
                {"className":"Cipher","methodName":"run",
                 "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "operations":[{"operation":"replace_constant",
                   "anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
                   "arguments":{"value":"concurrent-value"}}]}
            """.trimIndent()
            val prepared = preparedRequest(context, projectId, request)
            val preview = json(context, "method_patch_preview", """{"projectId":"$projectId","request":$prepared}""")
            val patchRequest = Json.decodeFromJsonElement(
                serializer<BytecodePatchRequest>(),
                Json.parseToJsonElement(prepared),
            )
            val applyRequest = BytecodePatchApplyRequest(
                projectId = projectId,
                previewId = preview["previewId"]!!.jsonPrimitive.content,
                inputSha256 = preview["inputSha256"]!!.jsonPrimitive.content,
                request = patchRequest,
            )
            val projects = context.resource(ProjectRepository::class.java)!!
            val serviceA = BytecodePatchService(projects, JvmIndexService(projects))
            val serviceB = BytecodePatchService(projects, JvmIndexService(projects))
            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val outcomes = listOf(serviceA, serviceB).map { service ->
                    executor.submit<Result<BytecodePatchApplyResult>> {
                        check(start.await(10, TimeUnit.SECONDS))
                        runCatching { service.applyMethod(projectId, applyRequest) }
                    }
                }
                start.countDown()
                val results = outcomes.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.isSuccess })
                assertTrue(results.single { it.isFailure }.exceptionOrNull()!!.message!!.contains("preview-consumed"))
                assertEquals(2, projects.list().size)
            } finally {
                executor.shutdownNow()
                serviceA.close()
                serviceB.close()
            }
        }
    }

    @Test
    fun `apply rejects a request changed after preview`() {
        withProject { context, projectId ->
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.last { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }
            val request = """
                {"className":"Cipher","methodName":"run",
                 "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "operations":[{"operation":"replace_constant",
                   "anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
                   "arguments":{"value":"original-value"}}]}
            """.trimIndent()
            val prepared = preparedRequest(context, projectId, request)
            val preview = json(context, "method_patch_preview", """{"projectId":"$projectId","request":$prepared}""")
            val failure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "apply_method_patch",
                    """{"projectId":"$projectId","previewId":"${preview["previewId"]!!.jsonPrimitive.content}","request":${prepared.replace("original-value", "changed-value")}}""",
                )
            }
            assertTrue(failure.message!!.contains("does not match the request bound to the preview"))
            assertEquals(1, context.resource(ProjectRepository::class.java)!!.list().size)
        }
    }

    @Test
    fun `restarted service rejects lost previews and reused idempotency keys`() {
        val workspace = tempWorkspace()
        val config = LiteConfig(workspacePath = workspace)
        val archiveBytes = Files.readAllBytes(fixtureJar(false, false))
        val encoded = java.util.Base64.getEncoder().encodeToString(archiveBytes)
        var projectId = ""
        var prepared = ""
        var previewId = ""
        LiteContext(config).use { context ->
            registerLiteTools(context)
            projectId = Json.parseToJsonElement(
                call(context, "create_project", """{"fileName":"cipher.jar","archiveBase64":"$encoded"}"""),
            ).jsonObject["projectId"]!!.jsonPrimitive.content
            call(context, "index_archive", """{"projectId":"$projectId"}""")
            val instructions = json(
                context,
                "method_bytecode",
                """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
            )["instructions"]!!.jsonArray.map { it.jsonObject }
            val constant = instructions.last { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }
            val request = """
                {"className":"Cipher","methodName":"run",
                 "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                 "idempotencyKey":"restart-key",
                 "operations":[{"operation":"replace_constant",
                   "anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
                   "arguments":{"value":"restart-value"}}]}
            """.trimIndent()
            prepared = preparedRequest(context, projectId, request)
            val preview = json(context, "method_patch_preview", """{"projectId":"$projectId","request":$prepared}""")
            previewId = preview["previewId"]!!.jsonPrimitive.content
        }

        LiteContext(config).use { context ->
            registerLiteTools(context)
            val projects = context.resource(ProjectRepository::class.java)!!
            val applyFailure = assertFailsWith<ToolInputException> {
                call(
                    context,
                    "apply_method_patch",
                    """{"projectId":"$projectId","previewId":"$previewId","request":$prepared}""",
                )
            }
            assertTrue(applyFailure.message!!.contains("lost after restart"), applyFailure.message)
            assertEquals(1, projects.list().size)

            val changedRequest = prepared.replace("restart-value", "different-value")
            val keyFailure = assertFailsWith<ToolInputException> {
                call(context, "method_patch_preview", """{"projectId":"$projectId","request":$changedRequest}""")
            }
            assertTrue(keyFailure.message!!.contains("idempotencyKey"), keyFailure.message)
            assertEquals(1, projects.list().size)
        }
    }

    private fun assertArchivePatchPublished(context: LiteContext, projectId: String, hasManifest: Boolean) {
        val instructions = json(
            context,
            "method_bytecode",
            """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
        )["instructions"]!!.jsonArray.map { it.jsonObject }
        val constant = instructions.last { it["opcodeName"]!!.jsonPrimitive.content == "LDC" }
        val request = """
            {"className":"Cipher","methodName":"run",
             "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
             "operations":[{"operation":"replace_constant",
               "anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
               "arguments":{"value":"applied-value"}}]}
        """.trimIndent()
        val preparedRequest = preparedRequest(context, projectId, request)
        val preview = json(
            context,
            "method_patch_preview",
            """{"projectId":"$projectId","request":$preparedRequest}""",
        )
        val applied = json(
            context,
            "apply_method_patch",
            """{"projectId":"$projectId","previewId":"${preview["previewId"]!!.jsonPrimitive.content}","request":$preparedRequest}""",
        )
        val artifactPath = java.nio.file.Path.of(applied["artifactPath"]!!.jsonPrimitive.content)
        val artifactBytes = Files.readAllBytes(artifactPath)
        ZipInputStream(artifactBytes.inputStream()).use { zip ->
            if (hasManifest) assertEquals("META-INF/MANIFEST.MF", zip.nextEntry.name)
            else assertFalse(zip.nextEntry.name == "META-INF/MANIFEST.MF")
        }
        val classBytes = JarFile(artifactPath.toFile()).use { jar ->
            if (hasManifest) {
                assertEquals("preserve-me", jar.manifest.mainAttributes.getValue("X-Antares-Fixture"))
            } else {
                assertEquals(null, jar.manifest)
            }
            val stored = jar.getJarEntry("assets/stored.bin")
            assertEquals(ZipEntry.STORED, stored.method)
            assertEquals("preserved-entry-comment", stored.comment)
            assertTrue(
                stored.extra.contentEquals(byteArrayOf(0xFE.toByte(), 0xCA.toByte(), 2, 0, 0x11, 0x22)),
                "STORED entry extra field changed",
            )
            assertEquals(
                "stored-resource",
                jar.getInputStream(stored).use { it.readBytes().decodeToString() },
            )
            val unicodeResource = jar.entries().asSequence().single {
                it.name.startsWith("assets/") && it.name != "assets/stored.bin"
            }
            assertEquals(
                "unicode-resource",
                jar.getInputStream(unicodeResource).use { it.readBytes().decodeToString() },
            )
            jar.getInputStream(jar.getJarEntry("Cipher.class")).use { it.readBytes() }
        }
        val classHash = java.security.MessageDigest.getInstance("SHA-256").digest(classBytes)
            .joinToString("") { "%02x".format(it) }

        assertEquals("true", applied["verified"]!!.jsonPrimitive.content)
        assertEquals("asm-static", applied["verificationScope"]!!.jsonPrimitive.content)
        assertEquals(preview["patchedClassSha256"]!!.jsonPrimitive.content, classHash)
        val replayFailure = assertFailsWith<ToolInputException> {
            call(
                context,
                "apply_method_patch",
                """{"projectId":"$projectId","previewId":"${preview["previewId"]!!.jsonPrimitive.content}","request":$preparedRequest}""",
            )
        }
        assertTrue(replayFailure.message!!.contains("preview-consumed"), replayFailure.message)
        val repreviewFailure = assertFailsWith<ToolInputException> {
            call(context, "method_patch_preview", """{"projectId":"$projectId","request":$preparedRequest}""")
        }
        assertTrue(repreviewFailure.message!!.contains("already been consumed"), repreviewFailure.message)
        assertEquals(2, context.resource(ProjectRepository::class.java)!!.list().size)
    }

    @Test
    fun `apply rejects signed archives before publishing an output`() {
        withFixture(signed = true) { context, projectId ->
            assertApplyRejected(context, projectId, "Signed archives are unsupported")
        }
    }

    @Test
    fun `apply rejects multi release class overrides`() {
        withFixture(multiRelease = true) { context, projectId ->
            assertApplyRejected(context, projectId, "Multi-release override")
        }
    }

    @Test
    fun `apply rejects duplicate target entries`() {
        val fixture = Files.readAllBytes(fixtureJar(false, false))
        withArchive(duplicateCentralEntry(fixture, "Cipher.class")) { context, projectId ->
            assertApplyRejected(context, projectId, "Duplicate archive entry")
        }
    }

    @Test
    fun `debug metadata operations fail instead of reporting a no-op as applied`() {
        withProject { context, projectId ->
            // The fixture is compiled without debug information, so no local-variable table exists.
            val draft = json(
                context,
                "method_patch_draft",
                """
                {"projectId":"$projectId","request":{
                  "className":"Cipher","methodName":"decode",
                  "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                  "operations":[{"operation":"patch_local_name","anchorInstructionIndex":2,
                                 "arguments":{"name":"renamed"}}]}}
                """.trimIndent(),
            )
            assertEquals("unsupported", draft["status"]!!.jsonPrimitive.content)
            assertTrue(draft["failureReason"]!!.jsonPrimitive.content.contains("local-variable"))
            assertTrue(
                draft["patchMetadataAvailability"]!!.jsonObject["hasLocalVariableTable"]!!
                    .jsonPrimitive.content.toBoolean().not(),
            )
        }
    }

    @Test
    fun `draft rejects an archive changed after project registration`() {
        withProject { context, projectId ->
            val project = context.resource(ProjectRepository::class.java)!!.require(projectId)
            Files.write(java.nio.file.Path.of(project.archivePath), byteArrayOf(1, 2, 3, 4))

            val failure = assertFailsWith<ToolInputException> {
                context.tools.call(
                    "method_patch_draft",
                    Json.parseToJsonElement(
                        """
                        {"projectId":"$projectId","request":{
                          "className":"Cipher","methodName":"run",
                          "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                          "operations":[{"operation":"delete_insn","anchorInstructionIndex":0}]}}
                        """.trimIndent(),
                    ).jsonObject,
                )
            }

            assertTrue(failure.message!!.contains("Project archive checksum mismatch"))
        }
    }

    @Test
    fun `patch request rejects conflicting outer and nested archive hashes`() {
        withProject { context, projectId ->
            val failure = assertFailsWith<ToolInputException> {
                context.tools.call(
                    "method_patch_draft",
                    Json.parseToJsonElement(
                        """
                        {"projectId":"$projectId","inputSha256":"outer",
                         "request":{"inputSha256":"inner","className":"Cipher",
                           "methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
                           "operations":[{"operation":"delete_insn","anchorInstructionIndex":0}]}}
                        """.trimIndent(),
                    ).jsonObject,
                )
            }

            assertTrue(failure.message!!.contains("Outer inputSha256 does not match request.inputSha256"))
        }
    }

    private fun withProject(block: (LiteContext, String) -> Unit) = withFixture(block = block)

    private fun withProject(debug: Boolean, block: (LiteContext, String) -> Unit) =
        withFixture(debug = debug, block = block)

    private fun withLargeProject(block: (LiteContext, String) -> Unit) =
        withFixture(large = true, block = block)

    private fun withFixture(
        debug: Boolean = false,
        large: Boolean = false,
        signed: Boolean = false,
        multiRelease: Boolean = false,
        manifest: Boolean = true,
        block: (LiteContext, String) -> Unit,
    ) {
        val archive = fixtureJar(debug, large, signed, multiRelease, manifest)
        withArchive(Files.readAllBytes(archive), block)
    }

    private fun withArchive(archiveBytes: ByteArray, block: (LiteContext, String) -> Unit) {
        LiteContext(LiteConfig(workspacePath = tempWorkspace())).use { context ->
            registerLiteTools(context)
            val encoded = java.util.Base64.getEncoder().encodeToString(archiveBytes)
            val created = call(
                context,
                "create_project",
                """{"fileName":"cipher.jar","archiveBase64":"$encoded"}""",
            )
            val projectId = Json.parseToJsonElement(created).jsonObject["projectId"]!!.jsonPrimitive.content
            call(context, "index_archive", """{"projectId":"$projectId"}""")
            block(context, projectId)
        }
    }

    private fun preview(context: LiteContext, projectId: String, request: String) : kotlinx.serialization.json.JsonObject {
        val prepared = preparedRequest(context, projectId, request)
        return json(
            context,
            "method_patch_preview",
            """{"projectId":"$projectId","request":$prepared}""",
        )
    }

    private fun preparedRequest(context: LiteContext, projectId: String, request: String): String {
        val draft = json(
            context,
            "method_patch_draft",
            """{"projectId":"$projectId","request":$request}""",
        )
        val requestObject = Json.parseToJsonElement(request).jsonObject.toMutableMap()
        requestObject["inputSha256"] = draft["inputSha256"]!!
        requestObject["methodInstructionSha256"] = draft["methodInstructionSha256"]!!
        requestObject["debugMetadataSha256"] = draft["debugMetadataSha256"]!!
        val requestedOperations = requestObject["operations"]!!.jsonArray
        val draftedOperations = draft["operations"]!!.jsonArray
        requestObject["operations"] = kotlinx.serialization.json.JsonArray(
            requestedOperations.mapIndexed { index, operation ->
                kotlinx.serialization.json.JsonObject(
                    operation.jsonObject + ("anchorOpcode" to draftedOperations[index].jsonObject["anchorOpcode"]!!),
                )
            },
        )
        return kotlinx.serialization.json.JsonObject(requestObject).toString()
    }

    private fun assertApplyRejected(context: LiteContext, projectId: String, expectedFailure: String) {
        val instructions = json(
            context,
            "method_bytecode",
            """{"projectId":"$projectId","className":"Cipher","methodName":"run","methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;"}""",
        )["instructions"]!!.jsonArray.map { it.jsonObject }
        val constant = instructions.first {
            it["opcodeName"]!!.jsonPrimitive.content == "LDC" &&
                it["operand"]?.jsonPrimitive?.content == "visible-marker"
        }
        val request = """
            {"className":"Cipher","methodName":"run",
             "methodDescriptor":"(Ljava/lang/String;)Ljava/lang/String;",
             "operations":[{"operation":"replace_constant",
               "anchorInstructionIndex":${constant["index"]!!.jsonPrimitive.content},
               "arguments":{"value":"safe-output"}}]}
        """.trimIndent()
        val prepared = preparedRequest(context, projectId, request)
        val preview = json(context, "method_patch_preview", """{"projectId":"$projectId","request":$prepared}""")
        assertTrue(preview["supported"]!!.jsonPrimitive.content.toBoolean(), preview.toString())
        val failure = assertFailsWith<ToolInputException> {
            call(
                context,
                "apply_method_patch",
                """{"projectId":"$projectId","previewId":"${preview["previewId"]!!.jsonPrimitive.content}","request":$prepared}""",
            )
        }
        assertTrue(failure.message!!.contains(expectedFailure), failure.message)
    }

    private fun json(context: LiteContext, tool: String, arguments: String) =
        Json.parseToJsonElement(call(context, tool, arguments)).jsonObject

    private fun call(context: LiteContext, tool: String, arguments: String): String =
        context.tools.call(tool, Json.parseToJsonElement(arguments).jsonObject).content.single().text

    private fun fixtureJar(
        debug: Boolean,
        large: Boolean,
        signed: Boolean = false,
        multiRelease: Boolean = false,
        manifestPresent: Boolean = true,
    ): java.nio.file.Path {
        val archive = Files.createTempFile("antares-lite-cipher", ".jar")
        val manifest = Manifest().apply {
            mainAttributes.putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0")
            mainAttributes.putValue("X-Antares-Fixture", "preserve-me")
            if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
        }
        val classBytes = compileFixture(debug, large)
        val outputStream = archive.outputStream()
        val jarOutput = if (manifestPresent) JarOutputStream(outputStream, manifest) else JarOutputStream(outputStream)
        jarOutput.use { output ->
            val storedBytes = "stored-resource".toByteArray()
            val storedEntry = ZipEntry("assets/stored.bin").apply {
                method = ZipEntry.STORED
                size = storedBytes.size.toLong()
                compressedSize = storedBytes.size.toLong()
                crc = CRC32().apply { update(storedBytes) }.value
                comment = "preserved-entry-comment"
                extra = byteArrayOf(0xFE.toByte(), 0xCA.toByte(), 2, 0, 0x11, 0x22)
            }
            output.putNextEntry(storedEntry)
            output.write(storedBytes)
            output.closeEntry()
            output.putNextEntry(ZipEntry("assets/配置.txt"))
            output.write("unicode-resource".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("Cipher.class"))
            output.write(classBytes)
            output.closeEntry()
            if (multiRelease) {
                output.putNextEntry(ZipEntry("META-INF/versions/9/Cipher.class"))
                output.write(classBytes)
                output.closeEntry()
            }
            if (signed) {
                output.putNextEntry(ZipEntry("META-INF/TEST.SF"))
                output.write("signature".toByteArray())
                output.closeEntry()
            }
        }
        return archive
    }

    private fun duplicateCentralEntry(archive: ByteArray, targetName: String): ByteArray {
        val endRecordOffset = (archive.size - 22 downTo maxOf(0, archive.size - 65_557))
            .firstOrNull { readUInt32(archive, it) == 0x06054b50L }
            ?: error("ZIP end-of-central-directory record was not found.")
        val centralOffset = readUInt32(archive, endRecordOffset + 16).toInt()
        val centralSize = readUInt32(archive, endRecordOffset + 12).toInt()
        val centralEnd = centralOffset + centralSize
        var cursor = centralOffset
        var duplicateRecord: ByteArray? = null
        while (cursor < centralEnd) {
            require(readUInt32(archive, cursor) == 0x02014b50L) { "Invalid ZIP central-directory record." }
            val nameLength = readUInt16(archive, cursor + 28)
            val extraLength = readUInt16(archive, cursor + 30)
            val commentLength = readUInt16(archive, cursor + 32)
            val recordLength = 46 + nameLength + extraLength + commentLength
            val name = String(archive, cursor + 46, nameLength, Charsets.UTF_8)
            if (name == targetName) duplicateRecord = archive.copyOfRange(cursor, cursor + recordLength)
            cursor += recordLength
        }
        val record = requireNotNull(duplicateRecord) { "Target central-directory entry was not found." }
        require(cursor == centralEnd && centralEnd == endRecordOffset) {
            "ZIP central directory has an unsupported layout."
        }

        val endRecord = archive.copyOfRange(endRecordOffset, archive.size)
        writeUInt16(endRecord, 8, readUInt16(endRecord, 8) + 1)
        writeUInt16(endRecord, 10, readUInt16(endRecord, 10) + 1)
        writeUInt32(endRecord, 12, (centralSize + record.size).toLong())
        return java.io.ByteArrayOutputStream(archive.size + record.size).apply {
            write(archive, 0, centralOffset)
            write(archive, centralOffset, centralSize)
            write(record)
            write(endRecord)
        }.toByteArray()
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (readUInt16(bytes, offset).toLong()) or (readUInt16(bytes, offset + 2).toLong() shl 16)

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        writeUInt16(bytes, offset, value.toInt() and 0xffff)
        writeUInt16(bytes, offset + 2, (value ushr 16).toInt() and 0xffff)
    }

    /**
     * Produce the detector's target shape: a `TABLESWITCH` selecting integer XOR keys, feeding
     * `IXOR`/`I2C`/`CASTORE` and a `String.<init>([C)` rebuild.
     *
     * Compiled without `-g` so the class has no debug metadata, which also exercises the
     * patch-metadata precondition.
     */
    private fun compileFixture(debug: Boolean, large: Boolean): ByteArray {
        val largeMethodBody = if (large) buildString {
            repeat(1200) { index -> append("String large$index = \"large-$index\"; ") }
        } else ""
        val source = """
            public class Cipher {
                private static final int[] KEYS = { 17, 91, 4, 63, 28, 55, 12 };
                // A high-entropy, largely non-printable constant, mirroring the reference archive
                // where ciphertext was previously reported as an ordinary plain constant.
                public static final String STAGE = "\u0015l<\u0019\u0002\u0007";
                public static long longValue() { return 9000000000L; }
                public static void eraseUnused() {
                    String unused = "unused";
                    ${if (debug) "if (unused.isEmpty()) { return; }" else ""}
                }
                public static int branch(int value) { int ignored = 3; if (value == 0) return 0; return 1; }
                ${if (large) "public static void large() { $largeMethodBody }" else ""}
                public static String decode(String encoded) {
                    char[] buffer = encoded.toCharArray();
                    for (int i = 0; i < buffer.length; i++) {
                        int key;
                        switch (i % 7) {
                            case 0: key = 17; break;
                            case 1: key = 91; break;
                            case 2: key = 4; break;
                            case 3: key = 63; break;
                            case 4: key = 28; break;
                            case 5: key = 55; break;
                            default: key = 12; break;
                        }
                        buffer[i] = (char) (buffer[i] ^ key);
                    }
                    return new String(buffer).intern();
                }
                public String run(String value) {
                    if (value == null) { return STAGE; }
                    if (value.equals("visible-marker")) { return "visible-marker"; }
                    String result = decode(value);
                    if (result.length() > 3) { return result; }
                    if (result.isEmpty()) { return decode(value); }
                    while (result.length() < 8) { result = result + value; }
                    return result;
                }
            }
        """.trimIndent()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        requireNotNull(compiler) { "JDK compiler is required." }
        val root = Files.createTempDirectory("antares-lite-cipher-src")
        val sourceFile = root.resolve("Cipher.java")
        Files.writeString(sourceFile, source)
        val classes = root.resolve("classes")
        Files.createDirectories(classes)
        val compilerArguments = buildList {
            add(sourceFile.toString())
            if (debug) add("-g")
            add("-d")
            add(classes.toString())
        }
        val task = compiler.run(null, null, null, *compilerArguments.toTypedArray())
        require(task == 0) { "Fixture compilation failed." }
        return Files.readAllBytes(classes.resolve("Cipher.class"))
    }

    private fun tempWorkspace(): String =
        Files.createTempDirectory("antares-lite-cipher-workspace").toString()
}
