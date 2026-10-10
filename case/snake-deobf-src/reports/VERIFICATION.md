# Bounded verification record

Original fixture SHA-256:
`d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac`.

The independent external probe compares original and reconstructed trim results
for scores 0, 1, 3 and 5 and initial body sizes 0 through 8 (36 scenarios).
It compares all four arrow keys under all four current directions (16 scenarios).
Trim inputs include the recorded positive key `41896598891982` and explicitly
false opaque-guard fields. It compares retained coordinates, not only list size.

On JDK 21 the fixed source passes these checks. Replacing the counter decrement
with a no-op still compiles, but the same probe rejects it with a trim divergence.
The check for size 4 and score 3 verifies that an appended segment is trimmed
back to three entries. The probe creates headless cell components but opens no
window and starts no target game thread.

This is bounded differential evidence, not whole-program equivalence. It does
not establish behavior under arbitrary obfuscated key values, guard states,
random food placement, environmental changes or unobserved callers. Run the
repository's `scripts/verify_case.py` to regenerate the actual measurement JSON;
its `--endpoint` option also verifies finder coverage on fixed and broken builds.

The package is checked by clean extraction followed by its own build script,
then comparison of the produced class bytes with a clean source-tree build
using the same JDK. ZIP entries use forward slashes and stable metadata.
