param(
    [string]$Endpoint = 'http://127.0.0.1:8080/mcp',
    [string]$Archive = (Join-Path $PSScriptRoot '..\antares-lite-app\src\test\resources\snake-obfuscated.jar'),
    [string]$ReportPath = (Join-Path $PSScriptRoot '..\build\reports\reliability-mcp.json')
)

$ErrorActionPreference = 'Stop'
$requestId = 0
$sessionId = [Guid]::NewGuid().ToString('N')
function Invoke-Rpc([string]$Method, [hashtable]$Parameters) {
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Parameters }
    $json = $body | ConvertTo-Json -Depth 30 -Compress
    $response = Invoke-RestMethod -Uri $Endpoint -Method Post -ContentType 'application/json' `
        -Headers @{ 'Mcp-Session-Id' = $script:sessionId } `
        -Body ([Text.Encoding]::UTF8.GetBytes($json))
    if ($response.error) { throw ($response.error | ConvertTo-Json -Compress) }
    return $response.result
}
function Invoke-Antares([string]$Name, [hashtable]$Arguments) {
    $result = Invoke-Rpc 'tools/call' @{ name = $Name; arguments = $Arguments }
    if ($result.isError) { throw $result.content[0].text }
    return ($result.content[0].text | ConvertFrom-Json)
}
function Assert-Result([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Test-Schema($Node, [string]$Path) {
    if ($Node -is [array]) {
        foreach ($item in $Node) { Test-Schema $item $Path }
    } elseif ($Node -is [pscustomobject]) {
        foreach ($required in $Node.required) {
            Assert-Result ($null -ne $Node.properties.PSObject.Properties[$required]) "$Path missing property $required"
            $property = $Node.properties.$required
            Assert-Result ($null -ne $property.type -or $null -ne $property.oneOf) "$Path.$required missing type"
        }
        foreach ($property in $Node.PSObject.Properties) { Test-Schema $property.Value ($Path + '.' + $property.Name) }
    }
}

try {
$initialization = Invoke-Rpc 'initialize' @{
    protocolVersion = '2025-03-26'
    capabilities = @{}
    clientInfo = @{ name = 'antares-reliability-regression'; version = '1' }
}
$notification = @{ jsonrpc = '2.0'; method = 'notifications/initialized' } | ConvertTo-Json -Compress
Invoke-WebRequest -Uri $Endpoint -Method Post -ContentType 'application/json' `
    -Headers @{ 'Mcp-Session-Id' = $sessionId } -Body $notification | Out-Null
$definitions = (Invoke-Rpc 'tools/list' @{}).tools
Assert-Result ($definitions.Count -eq 34) 'Unexpected registry count'
foreach ($definition in $definitions) { Test-Schema $definition.inputSchema $definition.name }
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$identity = Invoke-Antares 'create_project' @{
    fileName = 'snake-reliability-regression.jar'
    archiveBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($archivePath))
}
Assert-Result ($identity.sha256 -eq 'd4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac') 'Unexpected fixture checksum'
$project = $identity.projectId
$currentIdentity = Invoke-Antares 'verify_archive_identity' @{ projectId = $project; expectedSha256 = $identity.sha256 }
Assert-Result ($currentIdentity.matches -and $currentIdentity.expectedMatches) 'Fresh stored-byte identity mismatch'
$index = Invoke-Antares 'index_archive' @{ projectId = $project }
$backends = foreach ($backend in @('cfr', 'vineflower')) {
    $class = Invoke-Antares 'decompile_class' @{ projectId = $project; className = 'e'; backend = $backend }
    Assert-Result ($class.failedRegions.Count -gt 0 -and $class.confidence -eq 'low') "$backend hid class failures"
    $method = Invoke-Antares 'decompile_method' @{
        projectId = $project; className = 'e'; methodName = 'a'; methodDescriptor = '(J)Ld;'; backend = $backend
    }
    Assert-Result (-not $method.exactMethodMatch -or $method.emittedDescriptor -eq '(J)Ld;') "$backend claimed a wrong descriptor"
    Assert-Result (-not $method.exactMethodMatch -or $method.source -notmatch '^\s*private void a\(d') "$backend returned the wrong overload"
    Assert-Result ($method.exactMethodMatch -or $method.failedRegions.Count -gt 0) "$backend omitted extraction failure"
    [ordered]@{
        backend = $backend
        confidence = $class.confidence
        failedRegions = $class.failedRegions
        exactMethodMatch = $method.exactMethodMatch
        emittedDescriptor = $method.emittedDescriptor
        emittedDeclaration = $method.emittedDeclaration
    }
}
$main = Invoke-Antares 'cfg_constant_propagation' @{
    projectId = $project; className = 'c'; methodName = 'main'; methodDescriptor = '([Ljava/lang/String;)V'
}
Assert-Result ('134279452297597' -in $main.instructionFacts.value) 'Missing folded XOR'
Assert-Result ('2048941838' -in $main.instructionFacts.value) 'Missing unsigned shift'
$loop = Invoke-Antares 'cfg_constant_propagation' @{
    projectId = $project; className = 'e'; methodName = 'run'; methodDescriptor = '()V'
}
Assert-Result ('53836601486112' -in $loop.instructionFacts.value) 'Missing loop key'
Assert-Result ('41896598891982' -in $loop.instructionFacts.value) 'Missing tail key'
Assert-Result (@($loop.branchFacts | Where-Object { $_.opcodeName -eq 'GOTO' -and $_.knownOutcome -eq 'taken' }).Count -gt 0) 'Unconditional jump remains unknown'
$trim = Invoke-Antares 'method_stack_slice' @{
    projectId = $project; className = 'e'; methodName = 'e'; methodDescriptor = '(J)V'; instructionIndex = 73
}
Assert-Result ($trim.analysisStatus -eq 'complete' -and $trim.slices[0].producerInstructionIndex -eq 68 -and $trim.slices[0].localIndex -eq 4) 'Trim branch lost the counter producer'
$callers = Invoke-Antares 'method_references' @{
    projectId = $project; className = 'f'; methodName = '<init>'; methodDescriptor = '(JC)V'; direction = 'callers'
}
$site = @($callers.calls | Where-Object { $_.ownerClass -eq 'c' -and $_.sourceMethod -eq 'main' })[0]
Assert-Result ($null -ne $site) 'Missing recorded constructor caller'
$context = Invoke-Antares 'method_call_context' @{
    projectId = $project; className = $site.ownerClass; methodName = $site.sourceMethod
    methodDescriptor = $site.sourceDescriptor; instructionIndex = $site.instructionIndex
}
Assert-Result ($context.calleeArgumentValues.'1' -eq '2429') 'Missing actual constructor char argument'
$writes = Invoke-Antares 'field_references' @{
    projectId = $project; className = 'd'; fieldName = 'e'; fieldDescriptor = 'Z'; access = 'writes'
}
Assert-Result ($writes.totalCount -eq 1) 'Missing indexed guard writer'
$title = Invoke-Antares 'recover_strings' @{ projectId = $project; className = 'c' }
$messages = Invoke-Antares 'recover_strings' @{ projectId = $project; className = 'e' }
Assert-Result ($title.status -eq 'recovered' -and $title.strings[0].value -eq 'Snake') 'Title recovery failed'
Assert-Result ($messages.status -eq 'recovered' -and $messages.strings[0].value -eq "COLISION! `n" -and $messages.strings[1].value -eq 'Yummy!') 'Message recovery failed'
$flattening = Invoke-Antares 'find_control_flow_flattening' @{ projectId = $project; limit = 1000 }
Assert-Result ($flattening.candidateCount -eq 0) 'Ordinary switches were called flattening'
$guards = Invoke-Antares 'find_opaque_predicates' @{ projectId = $project; limit = 1000 }
Assert-Result (@($guards.findings | Where-Object {
    $_.className -eq 'e' -and $_.methodName -eq 'b' -and $_.subpattern -eq 'static-boolean-guard'
}).Count -gt 0) 'Repeated boolean guards were missed'
$report = [ordered]@{
    endpoint = $Endpoint
    inputSha256 = $identity.sha256
    projectId = $project
    toolSchemasVerified = $definitions.Count
    archive = $index
    currentIdentity = $currentIdentity
    trimOperand = $trim
    constructorContext = $context
    guardWrites = $writes
    recoveredTitle = $title
    recoveredMessages = $messages
    decompilation = @($backends)
    mainConstantFacts = $main.instructionFacts
    loopConstantFacts = $loop.instructionFacts
    loopBranchFacts = $loop.branchFacts
    flattening = $flattening
    opaqueGuards = $guards
    allChecksPassed = $true
}
$reportDirectory = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
[pscustomobject]@{
    Passed = $true
    Schemas = $definitions.Count
    Project = $project
    Report = [IO.Path]::GetFullPath($ReportPath)
    FlatteningCandidates = $flattening.candidateCount
    OpaqueGuardCandidates = $guards.candidateCount
}
} finally {
    try {
        Invoke-WebRequest -Uri $Endpoint -Method Delete -Headers @{ 'Mcp-Session-Id' = $sessionId } | Out-Null
    } catch {
        Write-Warning 'Could not close the verification session.'
    }
}
