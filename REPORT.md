# Deobfuscating `snake.jar` with MiniMax M3.1 Flash and Antares Lite 1.2.1

**Target:** a 6,498-byte Java SE archive containing seven classes, heavily obfuscated with **Zelix KlassMaster (ZKM)**.\
**Goal:** remove string/constant obfuscation, control-flow obfuscation, and identifier renaming; reconstruct readable
source; and rebuild a working artifact.\
**Result:** the targeted obfuscation was removed, the reconstructed source compiled successfully, and no behavioral
divergence was observed under the static and runtime differential checks described in §6.

Despite its small program size, the target is not a trivial renaming-only sample. Its ZKM protection substantially
degrades conventional decompilation: one key method could not be reconstructed by Vineflower at all, while CFR reported
extensive failed regions in the most heavily obfuscated class. Program size and obfuscation intensity are therefore
treated as separate dimensions throughout this report.

This is a case study of an **LLM-driven reverse-engineering workflow**, not a claim that the model reasoned flawlessly.
It did not. During the run, the agents produced several incorrect intermediate conclusions, including one reconstruction
defect that compiled and ran but changed program behavior. The final result was obtained because the workflow repeatedly
challenged those conclusions through independent evidence and differential verification.

This document is self-contained and prepared for public release. Host-specific paths, local project/session identifiers,
ports, process IDs, and other machine-local state have been removed.

---

## Case artifacts

The original target and the reconstructed source package used in this case study are included in the repository:

| Artifact                                               | Description                                                                        |
|--------------------------------------------------------|------------------------------------------------------------------------------------|
| [`case/snake.jar`](case/snake.jar)                     | Original ZKM-obfuscated target, unmodified                                         |
| [`case/snake-deobf-src.zip`](case/snake-deobf-src.zip) | Reconstructed readable Java source, build script, reports, and supporting material |

Original target SHA-256:

```text
d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac
```

These files allow the case study to be inspected against the exact target described below rather than against an
unspecified sample.

---

## Experiment setup

| Property                 | Value                                                              |
|--------------------------|--------------------------------------------------------------------|
| Model                    | **MiniMax M3.1 Flash Preview**                                     |
| Antares Lite             | **1.2.1**                                                          |
| Obfuscator               | **Zelix KlassMaster (ZKM)25 obfuscation**                          |
| Target                   | [`case/snake.jar`](case/snake.jar), 6,498 bytes                    |
| Program size             | 7 classes, 28 methods, 23 fields                                   |
| Coordinator              | 1 MiniMax M3.1 Flash agent                                         |
| Specialists              | 5 same-model Antares Lite specialists                              |
| Specialists used         | Sirius, Rigel, Mira, Vega, Altair                                  |
| Independent verification | 1 separate verifier/auditor agent                                  |
| Final compilation        | **PASS**                                                           |
| Finder residue           | **10 → 0**                                                         |
| Runtime differential     | **PASS after one verifier-detected semantic defect was corrected** |
| Primary deliverable      | [`case/snake-deobf-src.zip`](case/snake-deobf-src.zip)             |

The five specialists followed Antares Lite's supplied role contracts:

- **Sirius** — JVM indexing and identity/retrieval
- **Rigel** — bytecode, CFG, and bounded analysis
- **Mira** — evidence/finder analysis
- **Vega** — decompiler-backed reconstruction
- **Altair** — guarded bytecode patching

All specialists inherited the same underlying model as the coordinator.

Unless explicitly stated otherwise, descriptions such as “the coordinator read”, “the coordinator concluded”, or “the
reconstruction initially assumed” refer to agent actions rather than human semantic analysis.

The important result is therefore not that MiniMax M3.1 Flash made no mistakes. It made several. The result is that the
workflow allowed those mistakes to be detected, contained, and corrected before final delivery.

---

## Table of contents

1. [Target artifact and identity](#1-target-artifact-and-identity)
2. [Obfuscation inventory and remediation](#2-obfuscation-inventory-and-remediation)
3. [Recovered semantics](#3-recovered-semantics)
4. [Bytecode-level patching](#4-bytecode-level-patching)
5. [Rebuilt artifact](#5-rebuilt-artifact)
6. [Verification](#6-verification)
7. [Process report: what went wrong](#7-process-report-what-went-wrong)
8. [Tooling friction](#8-tooling-friction)
9. [Lessons](#9-lessons)
10. [Deliverables](#10-deliverables)
11. [Tool capability boundaries](#11-tool-capability-boundaries)
12. [Execution scope](#12-execution-scope)
13. [Limitations](#13-limitations)
14. [Privacy notes](#14-privacy-notes)

---

# 1. Target artifact and identity

| Property                   | Value                                                              |
|----------------------------|--------------------------------------------------------------------|
| File                       | [`case/snake.jar`](case/snake.jar)                                 |
| Size                       | 6,498 bytes                                                        |
| Obfuscator                 | Zelix KlassMaster (ZKM), heavily obfuscated                        |
| SHA-256                    | `d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac` |
| Classes                    | 7 (`a`–`g`, default package)                                       |
| Methods                    | 28                                                                 |
| Fields                     | 23                                                                 |
| Encrypted string constants | 2                                                                  |
| Call sites                 | 131                                                                |
| Entry point                | `META-INF/MANIFEST.MF` → Main-Class `c`                            |

Before semantic analysis began, the target identity was checked through four separately retrieved representations:

1. the on-disk archive;
2. the analysis server's stored copy;
3. the `archive_summary` response;
4. finder responses referring to the indexed artifact.

All reported the same SHA-256.

This became relevant later when a specialist reported that the server-side artifact had a different hash. Independent
recomputation showed that no mismatch existed; the specialist had introduced a transcription error while comparing the
strings.

The analysis environment used JDK 25.0.1 and Antares Lite 1.2.1 over its HTTP transport so multiple specialist processes
could connect concurrently.

---

# 2. Obfuscation inventory and remediation

The target used three broad categories of ZKM protection addressed in this reconstruction:

1. **string and numeric constant obfuscation**;
2. **control-flow obfuscation / opaque predicates**;
3. **identifier renaming**.

The implementation details of the first category differ enough that strings and numeric constants are discussed
separately below.

---

## 2.1 Encrypted strings

Two static initializers decode string tables using a seven-byte cycling XOR key.

| Location     | Ciphertext                       | XOR key                     | Plaintext                    |
|--------------|----------------------------------|-----------------------------|------------------------------|
| `c.<clinit>` | `">\u0015l<\u0019"`              | `109,123,13,87,124,101,116` | `"Snake"`                    |
| `e.<clinit>` | `"sA\FP\!~/0\u0005\u0006i{}bz4"` | `48,14,16,15,3,21,110`      | `{"COLISION! \n", "Yummy!"}` |

The layout of the second table is self-describing. The byte following one encoded block specifies the size of the next
block: the first block has length 11 and `charAt(11) = 6`, indicating that the second plaintext contains six characters.

The plaintext was recovered arithmetically. Loading or executing the target classes was unnecessary for this step.

`"COLISION"` intentionally contains only one `L`. That spelling exists in the original program and was preserved
verbatim.

---

## 2.2 Numeric constant obfuscation

`c.main`, `f.<init>`, and `e.run()` derive numeric arguments from seed constants through combinations of XOR and shifts.

Representative seeds include:

```text
116018857672281
81557154710886
```

The resulting `long`, `char`, `int`, and `short` values feed comparisons and arguments whose surrounding obfuscated
structure was later simplified.

Static derivation produced, among other values:

```text
c.main:
    2048941838
    2429

GameThread.run:
    53836601486112
    29663
    22826
    20626
    71224974300475
    41896598891982
```

An isolated runtime instrumentation check performed later by the independent verifier observed the same values.

The static and runtime results therefore agreed for the constants actually measured.

---

## 2.3 Control-flow obfuscation and opaque predicates

Two static boolean fields participate in opaque control-flow structure.

Both were determined to be `false` at the relevant observed call sites by combining:

- enumeration of all `PUTSTATIC` sites;
- CFG reachability;
- the concrete arguments supplied by the observed callers;
- direct bytecode inspection.

| Field     | Write site                        | Observed result                                                  |
|-----------|-----------------------------------|------------------------------------------------------------------|
| `d.e : Z` | `e.<init>(JLd;)V`, instruction 83 | reachable write; sole observed caller supplies `ICONST_0`        |
| `e.h : Z` | `f.<init>(JC)V`, instruction 162  | reachable write; observed `char = 2429` path supplies `ICONST_0` |

One detail is important:

`cfg_constant_propagation` itself returned:

```text
knownOutcome: "unknown"
```

for these branches.

The final folding decision was therefore **not** accepted as a fact produced by that tool. It came from manual
agent-side propagation across the observed call sites and was subsequently checked against bytecode and runtime
behavior.

That distinction matters because tool output, reconstruction, and proof are not interchangeable evidence classes.

Once the relevant value of `e.h` was resolved, the apparent irreducible structure collapsed into conventional loops,
straight-line code, and an ordinary `TABLESWITCH`.

---

## 2.4 Identifier renaming

The original archive consists entirely of short obfuscated identifiers.

The reconstructed source assigns semantic names and moves the code into a `snake` package.

| Obfuscated | Reconstructed      |
|------------|--------------------|
| `c`        | `Snake`            |
| `e`        | `GameThread`       |
| `f`        | `SnakeFrame`       |
| `a`        | `SnakeKeyListener` |
| `g`        | `CellPanel`        |
| `b`        | `Cell`             |
| `d`        | `GridPoint`        |
| `e.i`      | `MESSAGES`         |

All seven classes, 28 methods, and 23 fields were given readable names in the source reconstruction.

Antares Lite's bytecode patch schema does **not** provide arbitrary class, method, or field renaming. Identifier
recovery was therefore performed at the reconstructed-source layer rather than through the patched JAR.

The rebuilt source artifact is consequently the primary complete deobfuscation output.

---

# 3. Recovered semantics

## 3.1 Direction mapping

The first attempted mapping was taken from decompiler output and was wrong.

Raw `TABLESWITCH` targets resolved the ambiguity:

```text
TABLESWITCH targets={
    37 -> @32,
    38 -> @21,
    39 -> @9,
    40 -> @43
}

@9  (39 RIGHT) -> ICONST_1
@21 (38 UP)    -> ICONST_3
@32 (37 LEFT)  -> ICONST_2
@43 (40 DOWN)  -> ICONST_4
```

Therefore:

| Key             | Direction | Movement                 |
|-----------------|----------:|--------------------------|
| `VK_LEFT` (37)  |         2 | `x - 1`, wrapping 0 → 19 |
| `VK_UP` (38)    |         3 | `y - 1`, wrapping 0 → 19 |
| `VK_RIGHT` (39) |         1 | `(x + 1) % 20`           |
| `VK_DOWN` (40)  |         4 | `(y + 1) % 20`           |

A reverse-direction guard rejects immediate 180-degree turns:

```text
1 ↔ 2
3 ↔ 4
```

The error occurred because nested labels in Vineflower output were interpreted incorrectly. The raw bytecode was treated
as authoritative once the two disagreed.

---

## 3.2 Preserved implementation quirks

Several axis/orientation inconsistencies are present in the original program.

They were intentionally preserved rather than “corrected”, because reconstruction fidelity was the objective.

Observed examples include:

- `paintCell` indexes `grid.get(x).get(y)`;
- `clearBody` and `paintBodySegments` index `grid.get(y).get(x)`;
- the food collision comparison is transposed:\
  `head.x == food.y && head.y == food.x`;
- `spawnFood` performs the corresponding transposed overlap comparison;
- `spawnFood` resets a scan index to zero and then pre-increments it, causing rescans to begin at index 1 rather than
  rechecking index 0.

The independent verifier later checked these behaviors against the original bytecode and did not treat them as
reconstruction mistakes.

---

## 3.3 Reconstructing `e.e(J)V`

This was the most difficult method in the archive.

Neither decompiler returned a usable high-level implementation.

Vineflower emitted:

```text
// $VF: Couldn't be decompiled
```

followed by raw bytecode.

CFR reported extensive failed regions in the containing class.

The coordinator therefore reconstructed the method directly from bytecode evidence.

The method contains two conceptually related loops.

### First loop

The first loop deals with the difference between current body length and score.

With the normal state used by the program:

```text
score = 3
body.size() = 3
```

the effective amount is zero before movement-related growth changes the body.

### Second loop

The second loop trims tail elements using a local counter.

The crucial branch semantics are:

```java
if(counter !=0){
counter--;
        }else{
        body.

remove(i);
}
```

The relevant bytecode branch is easy to read backwards:

```text
ILOAD counter
...
IFNE -> decrement path
```

The branch consumes the counter value; the taken path reaches the `IINC` implementing the decrement.

The initial reconstruction incorrectly associated this conditional with another value and therefore removed the
decrement logic.

The correct overall behavior is:

```text
body length before move = 3

move:
    append one body segment
    body length = 4

trim:
    remove body.size() - score
    = 4 - 3
    = 1 tail element

body length after trim = 3
```

Thus `move` grows the temporary representation by one element, while `trim` restores the body to `score` segments.

Since `score` remains 3 in the observed execution, body length remains stable at 3.

This detail later became the decisive behavioral differential used to detect the faulty reconstruction.

---

# 4. Bytecode-level patching

Antares Lite's schema-v2 patch protocol was used to remove two instruction sequences proven dead.

The protocol is:

```text
draft
→ preview
→ verification
→ apply
→ re-index
→ read back
```

Two patch sets were applied.

| Patch | Method                                     | Operation                                                             | Result               |
|-------|--------------------------------------------|-----------------------------------------------------------------------|----------------------|
| B1    | `a.keyPressed(Ljava/awt/event/KeyEvent;)V` | delete two instructions: `LDC 121593940001805`, `LSTORE 2`            | 55 → 53 instructions |
| B2    | `e.d(ISI)V`                                | delete 19-instruction shift/or prefix stored into unused local slot 4 | 51 → 34 instructions |

The deleted local values are not subsequently read.

Both previews returned:

```text
verified: true
verificationScope: asm-static
diffTruncated: false
```

After application:

- the result was re-indexed;
- archive and artifact checksums were compared;
- `method_bytecode` was read again;
- the surviving control-flow structures were checked.

In particular:

- the `TABLESWITCH` remained;
- the relevant iterator loop remained;
- the deleted `LSTORE` instructions were absent;
- the target methods still resolved exactly.

---

## 4.1 Patch composition

The second patch was not applied independently to the original archive.

Instead:

```text
original
   ↓
B1
   ↓
B1 output becomes new source
   ↓
new anchors / hashes fetched
   ↓
B2
   ↓
composite artifact
```

This matters because guarded patches are bound to artifact identity and instruction context. Composition is not
implicitly valid across different source states.

The resulting composite artifact:

```text
snake-bytecode-patched.jar
```

has size:

```text
6,405 bytes
```

and SHA-256:

```text
c59bb7340a23343314689a3b871ec870f30ddef975ea76d6e9f465231c0d3944
```

The patcher's own success report was not treated as sufficient evidence. Independent scripts subsequently checked the
artifact on disk and the relevant bytecode.

---

# 5. Rebuilt artifact

The reconstructed Java source consists of seven files:

```text
snake/
├─ Snake.java
├─ SnakeFrame.java
├─ GameThread.java
├─ SnakeKeyListener.java
├─ GridPoint.java
├─ Cell.java
└─ CellPanel.java
```

The reconstruction removes:

- encrypted string initialization;
- numeric constant folding scaffolding;
- opaque control-flow structure;
- dead instruction sequences;
- obfuscated identifiers.

The program entry point changes from:

```text
c
```

to:

```text
snake.Snake
```

The rebuilt artifact is therefore not byte-for-byte identical to the original JAR, nor is that the intended goal.

The relevant question is behavioral consistency under the checks actually performed.

The complete reconstructed source distribution is available at:

**[`case/snake-deobf-src.zip`](case/snake-deobf-src.zip)**

The exact original ZKM-obfuscated artifact used for comparison is:

**[`case/snake.jar`](case/snake.jar)**

---

# 6. Verification

Verification was intentionally layered because several intermediate results that looked convincing were wrong.

---

## 6.1 Compilation

Compilation command:

```text
javac 25.0.1 -Xlint:all
```

Result:

```text
0 errors
```

Two `this-escape` warnings remained. They arise from the original Swing construction pattern rather than from residual
obfuscation.

Compilation was treated only as a structural check.

A compiling reconstruction can still be semantically wrong, as the later verifier failure demonstrated.

---

## 6.2 Re-import and finder scan

The rebuilt JAR was imported back into Antares Lite and scanned again.

| Check                          |             Original |       Rebuilt |
|--------------------------------|---------------------:|--------------:|
| `find_string_transforms`       |                    2 |         **0** |
| `find_number_secrets`          |                    1 |         **0** |
| `find_opaque_predicates`       |                    7 |         **0** |
| `find_control_flow_flattening` |                    0 |             0 |
| `find_invokedynamic_dispatch`  |                    0 |             0 |
| `evidence_summary` total       |                   10 |         **0** |
| String constants               | 2 encrypted literals |   3 plaintext |
| Main-Class                     |                  `c` | `snake.Snake` |

The finder responses reported:

```text
scanComplete: true
truncated: false
```

The result therefore means that **Antares Lite's supported finders detected no remaining instances of those patterns in
the rebuilt artifact**.

It does not establish the stronger proposition that the program is “free of all possible obfuscation” under every
conceivable definition.

---

## 6.3 Independent verifier: first result was FAIL

At this point, the reconstructed program:

- compiled;
- produced a clean finder scan;
- launched successfully;
- appeared superficially complete.

That was not accepted as sufficient.

A separate verifier agent was explicitly tasked to assume the reconstruction was wrong until independent evidence
supported it.

The verifier used:

```text
javap -c -p
+
independent ASM disassembly
+
runtime differential probing
```

and returned:

```text
FAIL
```

The defect was in the reconstructed trim logic.

The incorrect source failed to decrement the loop counter correctly, so the snake body grew instead of being restored to
its intended length.

Measured behavior:

| Observable       |   Original |       Broken rebuild | Fixed rebuild |
|------------------|-----------:|---------------------:|--------------:|
| `body.size`      | constant 3 | 16 → 21, then frozen |    constant 3 |
| stdout over 40 s |      empty |          `COLISION!` |         empty |

This was a genuine semantic reconstruction error.

It is especially important because the broken result had already passed several weaker checks:

```text
source exists       PASS
javac               PASS
finder scan         PASS
program starts      PASS
```

Yet the program was still wrong.

---

## 6.4 Why the first runtime test failed as a verification method

The broken reconstruction eventually printed:

```text
COLISION!
```

The coordinator initially interpreted this as normal self-collision behavior and therefore as evidence that the game was
functioning.

It was actually the observable consequence of the defect.

The faulty trim routine allowed the body to grow until the snake collided with itself.

Thus the runtime test had already exposed the error, but the result was incorrectly explained away.

This is a useful distinction:

```text
observation
≠
interpretation
```

A runtime test only helps if unexpected output is treated as evidence against the current hypothesis rather than
rationalized into it.

---

## 6.5 Correction and differential verification

After correcting the branch interpretation, the rebuilt artifact was measured using the same type of body-length probe
used against the original.

Eight samples at 0.5-second intervals produced:

```text
3, 3, 3, 3, 3, 3, 3, 3
```

for the rebuilt program.

Observed state:

| Property                    | Value |
|-----------------------------|-------|
| `body.size`                 | 3     |
| `score`                     | 3     |
| dark-gray body cells        | 3     |
| blue food cells             | 1     |
| remaining initialized cells | 396   |

The program stayed alive during the observation window and produced no `COLISION!` output.

Within the observables exercised by this verification process, no divergence from the original was detected after the
fix.

This is **not** a formal semantic-equivalence proof.

It is a differential empirical result over the explicitly measured state and behavior.

---

## 6.6 Additional verifier findings

The verifier also identified two further issues.

### Class-shape mismatch

The reconstructed `Snake` class had initially been declared:

```java
final
```

with a private constructor.

The original `c` class is non-final and exposes a public no-argument constructor.

The reconstructed source was adjusted to match the original class shape.

### Evidence-file defect

One evidence JSON file associated with the UP branch transposed accesses to `d.a` and `d.b`.

The reconstructed source had followed the class bytecode rather than that incorrect evidence file, so the implementation
was already correct.

However, the JSON artifact itself was marked unsuitable for deciding field identity at that location.

This is another example of why evidence artifacts themselves require validation.

---

# 7. Process report: what went wrong

The most useful part of this case study is not the successful final output but the intermediate failures.

---

## 7.1 Error 1: direction mapping

The initial mapping inferred from decompiler output was:

```text
VK_LEFT  -> 3
VK_UP    -> 2
VK_RIGHT -> 1
VK_DOWN  -> 4
```

Rigel disagreed and cited the `TABLESWITCH` directly.

Raw bytecode inspection confirmed:

```text
LEFT  = 2
UP    = 3
RIGHT = 1
DOWN  = 4
```

The error came from misreading Vineflower's nested labels and interpreting:

```text
break labelXX
```

as entering a labeled region rather than transferring control past it.

The resolution rule used thereafter was simple:

> Decompiled structure is reconstruction evidence. Raw bytecode controls when the two disagree.

---

## 7.2 Error 2: trim-loop branch polarity

The more serious error occurred in the loop later reconstructed as `trimBodySegments()`.

A simplified portion of the observed bytecode looked like:

```text
ILOAD ...
ILOAD ...
IFNE ...
IFNE ...
```

The coordinator associated the second conditional with the wrong stack value.

That produced the incorrect hypothesis:

> the local counter never decrements.

The corresponding Java reconstruction was therefore wrong.

In the actual operand-stack sequence, the conditional consumes the counter and the taken path reaches the decrement.

Correct conceptual behavior:

```java
if(counter !=0){
counter--;
        }else{
        body.

remove(i);
}
```

The incorrect interpretation changed runtime behavior substantially.

This defect was not discovered by compilation or static finder cleanup. It was discovered only because the independent
verifier compared runtime state.

---

## 7.3 False hash conflict from a specialist

Mira reported that the archive hash differed between the disk and analysis server.

The report was unusually convincing:

- it included a character-by-character comparison;
- it proposed a corrected value;
- it then claimed that the first correction was itself wrong.

The hashes were recomputed in grouped form:

```text
disk   : d4802826 f15cb6dc 2d1d73f0 1b767677 9a7f3bde 239e7236 80d8ffeb e2cfa4ac
stored : d4802826 f15cb6dc 2d1d73f0 1b767677 9a7f3bde 239e7236 80d8ffeb e2cfa4ac
server : d4802826 f15cb6dc 2d1d73f0 1b767677 9a7f3bde 239e7236 80d8ffeb e2cfa4ac
import : d4802826 f15cb6dc 2d1d73f0 1b767677 9a7f3bde 239e7236 80d8ffeb e2cfa4ac
```

All matched.

The contradiction existed only in the specialist's comparison.

The practical lesson is:

> A subagent reporting a contradiction does not make the contradiction a fact.

The contradiction itself becomes a claim requiring verification.

---

## 7.4 Packaging exposed two additional defects

Preparing the deliverables uncovered two issues that had not appeared during analysis.

### Windows ZIP separators

PowerShell's `Compress-Archive` produced entries such as:

```text
vf-mcp\a.java
```

On Unix-like systems this may be interpreted as a literal filename rather than a path.

The packaging logic was replaced with direct `.NET ZipArchive` handling using forward-slash entry names.

### Build-script layout assumption

`build.ps1` initially assumed the source tree always existed at:

```text
deobf\src
```

but inside the distributed ZIP it existed at:

```text
src\
```

The archive could therefore not rebuild itself from a clean extraction.

The script was changed to detect both layouts.

A clean:

```text
extract
→ build
→ package
→ compare
```

cycle then reproduced all seven `.class` files byte-for-byte.

---

## 7.5 Operational mistakes

Two unrelated operational failures were also recorded rather than hidden.

### Killing the analysis server

A cleanup command targeting all Java processes also terminated the Antares Lite server.

After restart, the service and stored analysis state were checked before work continued.

### False runtime regression

One later execution exited unexpectedly after roughly 12 seconds.

It was initially suspected to be a regression, but investigation showed interference from leftover Java processes from
earlier verification runs.

After cleanup, repeated observation showed stable execution with empty stdout/stderr.

The unexplained anomaly was therefore not promoted to a technical conclusion.

---

# 8. Tooling friction

The specialist environment did not expose an MCP client directly.

A small PowerShell wrapper was therefore written to communicate with Antares Lite through JSON-RPC.

It handled:

```text
initialize
tools/call
```

and assigned separate MCP session IDs to concurrent specialist processes.

Responses were written to files rather than mixed into stdout.

The wrapper itself exposed several environment-level issues.

| Symptom                                                  | Cause                                       | Resolution                                               |
|----------------------------------------------------------|---------------------------------------------|----------------------------------------------------------|
| MCP initialization failure with little diagnostic output | rejected notification plus swallowed output | removed unnecessary notification; routed errors directly |
| Base64 arguments corrupted                               | command-line quoting/parsing                | read arguments from UTF-8 file                           |
| Relative paths resolve incorrectly                       | PowerShell working-directory behavior       | resolve from `$PSScriptRoot` / absolute paths            |
| `jar` command unavailable                                | only javapath shim present                  | derive JDK root and locate `bin\jar.exe`                 |
| `javac` warning treated as failure                       | stderr + PowerShell error behavior          | rely on `$LASTEXITCODE`                                  |
| child process exits `-1`                                 | pipeline consumer closes output early       | do not truncate live child-process output                |
| `Get-FileHash` path errors                               | byte array supplied where path expected     | hash extracted files                                     |
| ZIP entries contain backslashes                          | platform-specific archive behavior          | construct ZIP entries directly                           |
| destructive delete blocked                               | unsafe removal mode                         | use recoverable deletion                                 |

These problems were not part of JVM reverse engineering, but they affected the reliability of the autonomous workflow
and are therefore relevant to reproducibility.

---

# 9. Lessons

## 9.1 Decompiler output is evidence, not ground truth

Three semantic conclusions were initially read incorrectly from decompiler output.

The decisive evidence came from raw bytecode.

This was particularly important around methods for which decompilers emitted warnings such as:

```text
Irreducible bytecode was duplicated
```

or failed to reconstruct a method at all.

---

## 9.2 Important claims need an independent path

Examples from this run:

```text
direction mapping
→ TABLESWITCH targets

trim semantics
→ runtime differential

numeric constants
→ standalone arithmetic derivation + runtime measurement

reconstruction defect
→ javap + ASM + runtime probe
```

A conclusion produced once by one agent or one analysis backend was not considered strong enough for high-impact
semantic decisions.

---

## 9.3 Unexpected runtime behavior should challenge the hypothesis

The incorrect rebuild printed:

```text
COLISION!
```

That observation should immediately have raised suspicion.

Instead it was initially interpreted as reasonable gameplay.

This case demonstrates why a workflow needs an explicit rule:

> When an observation is counter-intuitive, first test whether the current interpretation is wrong.

---

## 9.4 Specialist confidence is not evidence strength

The false SHA-256 conflict was presented in a detailed and self-correcting report.

It was still wrong.

A polished explanation does not increase the evidentiary status of its underlying claim.

Independent verification remained necessary.

---

## 9.5 “It works here” is not artifact verification

The build script worked in its original working tree and failed inside the distributed archive.

Only an actual clean extraction and rebuild found the problem.

Deliverables should be tested in the form users will receive them.

---

## 9.6 Tool boundaries should be established early

`patch_catalog` made several limits explicit before patching began.

That prevented later assumptions that Antares Lite could directly rename arbitrary JVM entities or rewrite complete
method bodies.

Known absence of a capability is preferable to discovering that absence halfway through a transformation.

---

## 9.7 System reliability can exceed individual model reliability

The model made multiple local reasoning errors.

The final deliverable nevertheless converged because those errors were prevented from automatically becoming trusted
global facts.

A useful way to summarize the workflow is:

```text
hypothesis
    ↓
evidence collection
    ↓
cross-check
    ↓
reconstruction
    ↓
independent adversarial verification
    ↓
commit
```

rather than:

```text
model says X
    ↓
X becomes truth
```

That distinction is the central result of this case study.

---

# 10. Deliverables

The two primary public case artifacts are stored under [`case/`](case/):

| File                                                   | Role                                                     |
|--------------------------------------------------------|----------------------------------------------------------|
| [`case/snake.jar`](case/snake.jar)                     | Exact original ZKM-obfuscated target used by this report |
| [`case/snake-deobf-src.zip`](case/snake-deobf-src.zip) | Complete reconstructed source distribution               |

The expected repository layout is:

```text
.
├─ REPORT_EN.md
└─ case/
   ├─ snake.jar
   └─ snake-deobf-src.zip
```

### Original target

[`case/snake.jar`](case/snake.jar)

```text
SHA-256:
d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac
```

The archive is retained unmodified so the evidence and reconstructed output can be checked against the exact
ZKM-obfuscated binary discussed in this report.

### Reconstructed source package

[`case/snake-deobf-src.zip`](case/snake-deobf-src.zip)

The package contains the reconstructed source and its supporting build/report material, including the seven semantic
source files:

```text
snake/
├─ Snake.java
├─ SnakeFrame.java
├─ GameThread.java
├─ SnakeKeyListener.java
├─ GridPoint.java
├─ Cell.java
└─ CellPanel.java
```

The working analysis tree also contained:

```text
deobf/
├─ src/snake/
├─ dist/
│  ├─ snake-deobf.jar
│  └─ snake-bytecode-patched.jar
├─ build.ps1
└─ evidence/
```

Evidence produced during the run included:

```text
inventory.json
findings.json
decomp-rollup.json
listing.txt
bytecode/
postcheck/
run/
```

Together these represent:

- archive inventory;
- finder results and coverage;
- CFR/Vineflower reconstruction records;
- ASM listings;
- raw method-bytecode evidence;
- post-rebuild finder scans;
- runtime differential measurements.

---

## Rebuilding the source package

After extracting [`case/snake-deobf-src.zip`](case/snake-deobf-src.zip), with JDK 21 or newer:

```powershell
.\build.ps1
java -jar .\dist\snake-deobf.jar
```

`build.ps1` supports both:

```text
<root>/deobf/src
```

and:

```text
<root>/src
```

so the same build logic works from the analysis working tree and from the distributed source archive.

---

# 11. Tool capability boundaries

Antares Lite's patch catalog exposes six relevant mutation operations:

```text
replace_insn
delete_insn
replace_constant
redirect_call
patch_local_name
patch_line_table
```

Several consequences follow.

## 11.1 No arbitrary identifier renaming

There is no general:

```text
rename_class
rename_method
rename_field
```

operation.

The semantic renaming in this case study therefore exists in reconstructed source, not in the bytecode-patched archive.

---

## 11.2 No wholesale method-body replacement

The patch schema does not provide arbitrary method-body replacement or unrestricted instruction insertion.

Bytecode patching was therefore used only for bounded, well-supported transformations.

---

## 11.3 Debug metadata cannot be created when absent

The target was compiled without the relevant debugging tables.

It contains neither:

```text
LocalVariableTable
```

nor:

```text
LineNumberTable
```

so the corresponding patch operations return `unsupported`.

The schema can modify existing metadata; it does not synthesize nonexistent tables.

---

## 11.4 `asm-static` is not semantic proof

A patch preview marked:

```text
verified: true
verificationScope: asm-static
```

means that the result passed the scope of Antares Lite's static ASM-based verifier.

It does **not** prove:

- runtime linkage;
- environmental compatibility;
- whole-program behavioral equivalence;
- semantic equivalence with the original artifact.

This distinction is why independent runtime differential verification was still performed.

---

# 12. Execution scope

The primary reverse-engineering workflow analyzed the original target statically.

During that main analysis, the original target classes were not loaded or initialized by Antares Lite.

One explicit exception occurred during verification.

To resolve runtime constants and the body-length behavior, the independent verifier instrumented the original classes
with ASM in an isolated environment and executed that instrumented copy.

The measurements included:

```text
2048941838
2429
```

and the folded constants associated with `GameThread.run`, as well as the observation that the original body length
remains constant at 3.

Those measurements agreed with the static reconstruction.

Other runtime execution involved the reconstructed or bytecode-patched artifacts.

---

# 13. Limitations

This report describes **one case study**, not a general benchmark proving universal JVM deobfuscation capability.

Several limitations should be kept explicit.

### Small program, ZKM25 obfuscation

The archive contains only:

```text
7 classes
28 methods
23 fields
6.5 KB
```

Its semantic program size is therefore small.

However, the target is **heavily obfuscated with Zelix KlassMaster (ZKM)**, and the obfuscation intensity is materially
higher than the program size alone suggests. In particular, conventional decompilers substantially degrade on the
protected control flow, including a key method that Vineflower could not reconstruct.

Program size and protection difficulty should therefore not be conflated.

The result demonstrates convergence on a small but heavily ZKM-obfuscated target; it should not be extrapolated directly
to large applications.

### No universal protector claim

Success here does not establish equivalent performance against arbitrary:

- ZKM configurations or versions;
- JVM obfuscators;
- custom virtual machines;
- native-assisted protections;
- dynamic loading schemes;
- reflection-heavy systems;
- large multi-module applications.

### Finder-zero is tool-relative

A post-rebuild finder count of zero means that Antares Lite's current supported finders reported no matching patterns
under complete scans.

It does not prove that no obfuscation of any kind exists under a broader definition.

### Differential testing is bounded

Runtime verification exercised specific observable behaviors and internal state.

No divergence was observed within those checks.

That result is not a formal whole-program semantic-equivalence proof.

### Reconstruction quality depended on verification

The initial reconstruction contained an actual behavioral defect.

Therefore this case should not be interpreted as evidence that a single LLM pass can reliably recover heavily obfuscated
JVM semantics.

The successful result depended materially on the multi-stage verification workflow.

---

# 14. Privacy notes

This report was prepared for public release.

The following machine-local information was removed:

- absolute host paths;
- local Windows account names;
- Antares project/session identifiers;
- server endpoint and port;
- process IDs;
- the number of locally retained project records.

These values describe the analysis environment rather than the target.

Retained intentionally because they belong to the analyzed artifact or the reproducibility record:

- target SHA-256;
- structural inventory;
- encrypted and recovered constants;
- reconstructed semantic mappings;
- measured runtime values;
- checksums of produced artifacts.

The original [`case/snake.jar`](case/snake.jar) was never modified.

Its SHA-256 remains:

```text
d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac
```

---

# Conclusion

This experiment should not be summarized as:

> “MiniMax M3.1 Flash perfectly deobfuscated the program.”

That would be false.

A more accurate summary is:

> **MiniMax M3.1 Flash, operating through Antares Lite's specialist and verification workflow, produced a src-ready
reconstruction of a heavily ZKM-obfuscated JVM program despite multiple local reasoning failures. Independent evidence
and adversarial verification prevented those failures from surviving into the final artifact.**

The model:

- misread decompiler-generated control flow;
- misinterpreted a bytecode branch;
- generated a false hash-conflict report through one specialist;
- accepted an incorrect runtime interpretation;
- produced a reconstruction that compiled and ran but behaved differently.

Yet the system eventually converged because the workflow did not treat any one of those outputs as automatically
authoritative.

The complete case can be inspected directly using:

- **Original ZKM-obfuscated target:** [`case/snake.jar`](case/snake.jar)
- **Reconstructed source:** [`case/snake-deobf-src.zip`](case/snake-deobf-src.zip)

The case therefore illustrates a distinction that matters for agent-based reverse engineering:

```text
model reliability
≠
system reliability
```

A relatively weak model can still produce a reliable final result when the surrounding workflow constrains claims,
preserves evidence provenance, forces independent checks, and treats unexpected behavior as grounds for re-investigation
rather than explanation.
