# Guarded Method Patching

Schema v2 patches one exact method per request, with 1-50 operations. Draft
validates anchors; preview prepares/verifies class bytes; apply publishes those
bytes once to a new artifact/project. The input JAR is unchanged.

Read `patch_catalog` and current `tools/list` schemas. Catalog
`proofRequired: false` means no proof-token argument is required; it does
not certify semantic correctness.

## Stages and checks

| Stage | Required evidence | Completion means |
| --- | --- | --- |
| Source | Project/hash, exact method, observed anchors | Source and requested change are unambiguous |
| Draft | Supported/valid, every operation valid, metadata available | Request validates; final class not yet verified |
| Preview | Supported/valid, applied operations, ID, reviewed diff/static checks | Prepared reviewable output exists in this process |
| Apply | Authorized identical request, live unconsumed preview | New artifact/project published once |
| Postcheck | Verified output hash, successful output indexing, observed change | Published output matches the bounded requested change |

Preview-only work finishes without apply. Preserve existing permission for the
concrete change. When apply permission is missing, prepare the reviewable
preview before asking for the final decision.

## Operations and types

Anchors are executable source indexes from `method_bytecode`. Gaps are
expected because omitted label/line/frame nodes occupy raw ASM positions.
Metadata nodes cannot be operation anchors.

| Operation | Required arguments | Optional arguments | Preconditions |
| --- | --- | --- | --- |
| `replace_insn` | `kind`, `opcode`, kind-specific fields | Kind-dependent | Supported opcode/operands; final class verifies |
| `delete_insn` | None | None | Removes one executable anchor; final class verifies |
| `replace_constant` | `value` | `valueType` (`string`/`int`) | String/Int `LDC`, same type |
| `redirect_call` | `owner`, `name`, `descriptor` | `itf` | Existing method invocation; preserve required stack shape |
| `patch_local_name` | `name` | `descriptor` | Existing table, exactly one active entry for anchored slot |
| `patch_line_table` | `line` | None | Existing unambiguous line entry; range 0-65535 |

Replacement kinds: `simple`, `int`, `var`, `ldc`, `type`, `field`,
`method`, `jump`. The int/var/ldc kinds require `operand`; type requires
`typeDescriptor`; field/method require `owner`, `name`, `descriptor`
(method also accepts `itf`); jump requires `targetInstructionIndex` from
existing `method_bytecode.labelIndexes`. Kind schemas and draft validation
govern opcode/value choices; this is not an arbitrary ASM editor.

`anchorInstructionIndex` is an integer. Values inside `arguments` are
strings, including `"line": "42"`, `"itf": "false"` and
`"targetInstructionIndex": "17"`. Owners use internal names such as
`java/lang/String`. Local `descriptor` filters an existing entry, not a
method signature edit.

Operations always refer to original source nodes. Conflicting anchors/metadata
edits can fail draft. A subsequent patch must use the previous output as its
source when composition is intended, with fresh indexes/hashes.

## Build a request from evidence

Use a small fixture rather than guess an index. Place this source in
`PatchFixture.java` in a fixture working directory:

```java
public final class PatchFixture {
    public static String label() {
        return "before";
    }
}
```

Compile and package only the fixture class:

```sh
javac -g PatchFixture.java
jar --create --file patch-fixture.jar PatchFixture.class
```

Start HTTP as described in [the quickstart](USER-QUICKSTART.md).
This PowerShell client initializes a distinct session and checks protocol/tool
errors before decoding JSON tool bodies:

```powershell
$liteEndpoint = 'http://127.0.0.1:8080/mcp'
$liteHeaders = @{ 'Mcp-Session-Id' = ('fixture-' + [guid]::NewGuid().ToString('N')) }
$script:liteCallId = 0
function Invoke-LiteRpc($method, $params) {
    $script:liteCallId += 1
    $body = @{ jsonrpc = '2.0'; id = $script:liteCallId; method = $method; params = $params } |
        ConvertTo-Json -Depth 30 -Compress
    $reply = Invoke-RestMethod -Uri $liteEndpoint -Method Post -Headers $liteHeaders -ContentType 'application/json' -Body $body
    if ($reply.error) { throw $reply.error.message }
    return $reply.result
}
function Invoke-LiteTool($name, $arguments) {
    $result = Invoke-LiteRpc 'tools/call' @{ name = $name; arguments = $arguments }
    $text = $result.content[0].text
    if ($result.isError) { throw $text }
    return ($text | ConvertFrom-Json)
}
Invoke-LiteRpc 'initialize' @{
    protocolVersion = '2025-03-26'
    capabilities = @{}
    clientInfo = @{ name = 'patch-fixture'; version = '1.0' }
} | Out-Null
$notification = @{ jsonrpc = '2.0'; method = 'notifications/initialized' } | ConvertTo-Json -Compress
Invoke-WebRequest -Uri $liteEndpoint -Method Post -Headers $liteHeaders -ContentType 'application/json' -Body $notification | Out-Null
Invoke-LiteRpc 'tools/list' @{} | Out-Null
```

Import, verify identity and select the actual returned `LDC` index:

```powershell
$fixtureBytes = [IO.File]::ReadAllBytes((Join-Path (Get-Location) 'patch-fixture.jar'))
$project = Invoke-LiteTool 'create_project' @{
    fileName = 'patch-fixture.jar'
    archiveBase64 = [Convert]::ToBase64String($fixtureBytes)
}
Invoke-LiteTool 'index_archive' @{ projectId = $project.projectId } | Out-Null
$summary = Invoke-LiteTool 'archive_summary' @{ projectId = $project.projectId }
if ($summary.archiveSha256 -ne $project.sha256) { throw 'Source identity mismatch' }
Invoke-LiteTool 'patch_catalog' @{} | Out-Null
$target = @{
    projectId = $project.projectId
    className = 'PatchFixture'
    methodName = 'label'
    methodDescriptor = '()Ljava/lang/String;'
}
$bytecode = Invoke-LiteTool 'method_bytecode' $target
$anchors = @($bytecode.instructions | Where-Object {
    $_.opcodeName -eq 'LDC' -and $_.operand -eq 'before'
})
if ($anchors.Count -ne 1) { throw 'Expected one observed before constant' }
$request = @{
    schemaVersion = 2
    className = $target.className
    methodName = $target.methodName
    methodDescriptor = $target.methodDescriptor
    operations = @(@{
        operation = 'replace_constant'
        anchorInstructionIndex = $anchors[0].index
        arguments = @{ value = 'after' }
    })
}
$draft = Invoke-LiteTool 'method_patch_draft' @{ projectId = $project.projectId; request = $request }
if (-not $draft.supported -or $draft.status -ne 'valid') { throw 'Draft rejected' }
$request.inputSha256 = $draft.inputSha256
$request.methodInstructionSha256 = $draft.methodInstructionSha256
$request.debugMetadataSha256 = $draft.debugMetadataSha256
$request.operations[0].anchorOpcode = $draft.operations[0].anchorOpcode
$preview = Invoke-LiteTool 'method_patch_preview' @{ projectId = $project.projectId; request = $request }
if (-not $preview.supported -or $preview.status -ne 'valid' -or -not $preview.previewId) {
    throw 'Preview rejected'
}
$preview | ConvertTo-Json -Depth 30
```

Review every operation's status/evidence, source fingerprints,
`patchedClassSha256`, diagnostics/checks, semantic/structural/debug changes
and warnings. Require `verificationScope: asm-static`. The total count equals
`instructionChanges.length` and semantic plus structural counts. Debug changes
are separate. With `diffTruncated`, evidence is operation-level, not a full diff.

Only after review and authorization, apply the identical request and postcheck:

```powershell
$applied = Invoke-LiteTool 'apply_method_patch' @{
    projectId = $project.projectId
    previewId = $preview.previewId
    request = $request
}
if (-not $applied.verified) { throw 'Output verification failed' }
if (-not $applied.reindexed) {
    $outputIndex = Invoke-LiteTool 'index_archive' @{ projectId = $applied.outputProjectId }
    if ($outputIndex.archiveSha256 -ne $applied.artifactChecksum) { throw 'Output index identity mismatch' }
}
$outputSummary = Invoke-LiteTool 'archive_summary' @{ projectId = $applied.outputProjectId }
if ($outputSummary.archiveSha256 -ne $applied.artifactChecksum) { throw 'Output identity mismatch' }
$outputTarget = $target.Clone()
$outputTarget.projectId = $applied.outputProjectId
$outputCode = Invoke-LiteTool 'method_bytecode' $outputTarget
if (-not ($outputCode.instructions | Where-Object {
    $_.opcodeName -eq 'LDC' -and $_.operand -eq 'after'
})) { throw 'Requested output constant is missing' }
$applied | ConvertTo-Json -Depth 30
```

This checks constant replacement without executing the target. Keep original
identity, preview evidence, output path/hash/ID and verification/postchecks.
The current apply implementation returns `reindexed: false`; explicit indexing
is an expected postcheck, not evidence that publication failed. Record the raw
apply flag separately from subsequent indexing success. Indexes in that output
belong to a new source.

## Lifetime and recovery

IDs bind project/archive, canonical request, method/debug fingerprints, class
bytes and engine/schema. Optional `idempotencyKey` cannot be rebound to a
different request/source; keep it identical through preview/apply.

Prepared bytes live in process memory: 15 minutes, 64 entries and 128 MiB total.
Other requests can evict them. Restart loses preparation while durable
consumption/idempotency bookkeeping can remain. Consumed requests cannot mint
replacement tokens to publish again.

| Condition | Action |
| --- | --- |
| Invalid/unsupported draft | Correct the stated operation/anchor/metadata condition; draft again |
| Failed verifier or missing required dependency | Resolve the limitation; do not apply or weaken checks |
| Hash/opcode/request mismatch | Re-establish identity/intent; do not blindly replace expected hashes |
| Expired/evicted/restarted preview, apply never sent | Re-draft/re-preview that source and review again |
| `preview-consumed` | Recover the output ID in the diagnostic; inspect that output |
| Timeout/disconnect during apply | Reconcile output with `list_projects`, known output IDs and artifact metadata |
| Unresolved apply state | Stop application and report uncertainty; no public journal-status tool exists |

Do not blindly retry uncertain apply, change keys to duplicate publication, or
treat a single missing listing as proof that no apply ran. If output cannot be
identified unambiguously, report the missing outcome as partial/blocked.

## Verification and unsupported input

`asm-static` checks serialized structure/types/stacks without target execution.
Missing hierarchy dependencies can fail required checks. Success does not prove
runtime linking, access resolution in deployment, behavior or equivalence.

Signed JARs and multi-release overrides of the target class are unsupported;
do not strip them silently. Debug edits require existing unambiguous entries.
Oversized, duplicate or invalid archive structures can fail validation.
Schema v1/old tokens do not migrate implicitly: start a v2 draft from current
source evidence.
