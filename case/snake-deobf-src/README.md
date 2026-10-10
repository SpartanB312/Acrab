# Snake reconstructed source

This package contains the seven readable Java classes from the Antares Lite
case study, build scripts, and an external differential probe. The original
obfuscated archive is distributed separately as `case/snake.jar`.

Use JDK 21 or newer. From the extracted package directory:

```powershell
& .\build.ps1 -JdkHome $env:JAVA_HOME
```

```sh
sh ./build.sh
```

The build produces `dist/snake-deobf.jar`. Run the game in a graphical session:

```sh
java -jar dist/snake-deobf.jar
```

The source preserves the original program's axis conventions and spelling.
Compilation, zero finder residue and matching class structure do not prove
behavioral equivalence. `verification/CaseProbe.java` compares bounded trim
and direction scenarios in an external headless JVM. It never starts the game
loop or opens a window. Its original-archive input must be the exact public
fixture, SHA-256 `d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac`.

The repository's `scripts/verify_case.py` builds both the reconstruction and a
deliberately faulty counter variant and requires the independent probe to
reject that variant. It can also compare finder results through a supplied
Antares HTTP endpoint. Runtime probes are separate from Antares Lite's static
analysis server.

Distributed under the MIT license included in the source ZIP.
