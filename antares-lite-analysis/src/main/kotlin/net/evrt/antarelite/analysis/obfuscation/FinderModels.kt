package net.evrt.antarelite.analysis.obfuscation

import kotlinx.serialization.Serializable

@Serializable
data class EvidenceFinding(
    val id: String,
    val category: String,
    val subpattern: String,
    val className: String,
    val methodName: String? = null,
    val descriptor: String? = null,
    val instructionIndexes: List<Int> = emptyList(),
    val evidence: List<String>,
    val weight: Int,
    val confidence: String,
    val limitations: List<String> = listOf("Static index-only signal; not a maliciousness verdict."),
    val nextTools: List<String> = listOf("method_bytecode", "method_cfg"),
)

@Serializable
data class FinderResult(
    val projectId: String,
    val inputSha256: String,
    val categoryScope: String,
    /** True total for the requested scope, independent of `limit`. */
    val candidateCount: Int,
    /** `candidateCount` is always the true total; `truncated` marks a bounded payload. */
    val truncated: Boolean,
    val limit: Int,
    val scanComplete: Boolean,
    val findings: List<EvidenceFinding>,
    val categorySummary: Map<String, Int>,
    val subpatternSummary: Map<String, Int> = emptyMap(),
    val warnings: List<String> = listOf(
        "Absence of findings does not prove that an archive is safe or unobfuscated.",
    ),
)

/** One finder tool and the evidence categories it can emit. */
@Serializable
data class FinderCatalogEntry(
    val tool: String,
    val categories: List<String>,
    val subpatterns: List<String>,
    val description: String,
)

/** Published finder vocabulary so a client can build `categoryFilter` without guessing. */
@Serializable
data class FinderCatalogResult(
    val categories: List<String>,
    val subpatterns: List<String>,
    val finders: List<FinderCatalogEntry>,
    val warnings: List<String> = emptyList(),
)
