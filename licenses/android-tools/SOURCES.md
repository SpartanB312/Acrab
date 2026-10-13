# Bundled Android Tool Sources and Licenses

These unchanged upstream Java tools run in separate JVM processes and are not
linked into Acrab's application classpath. Their licenses remain their own;
the Acrab MIT license does not relabel these artifacts or their dependencies.

| Tool | Version | Primary upstream source |
| --- | --- | --- |
| dex2jar | 2.4.38 | https://github.com/ThexXTURBOXx/dex2jar/tree/2.4.38 |
| smali/baksmali | 3.0.10 | https://github.com/google/smali/tree/3.0.10 |
| D8/R8 | 9.5.23 | https://r8.googlesource.com/r8/+/d4479de6af1586fc1f6911964542da8d442549db |
| Sable Jasmin | 3.0.3 | https://github.com/Sable/jasmin |
| Java CUP | 0.9.2 | https://repo.maven.apache.org/maven2/ca/mcgill/sable/java_cup/0.9.2/ |

`toolchain.json` records every runtime JAR's SHA-256 and each tool's exact
classpath. The primary tool binaries and dex2jar release archive are also
checked against fixed SHA-256 values when assembling a distribution.
No downloads occur when the installed server starts or runs a converter.

The dex2jar release's LICENSE, NOTICE and open-source-license files are included
under `dex2jar/`. R8's complete multi-component LICENSE and other embedded JAR
notices are copied under `embedded/`. R8 includes components with BSD, Apache,
MIT, LGPL, CDDL/GPL Classpath Exception and EDL license declarations; it must not
be described as exclusively Apache-2.0. Primary upstream Maven POMs are retained
under `poms/` with their license declarations and source references.

Jasmin is GPL-2.0. Its Maven 3.0.3 sources and the Java CUP 0.9.2 source archive
are included under `sources/`, outside the executable classpaths. The GPL-2.0
license is included here. The complete R8 source tree at the exact compiler
revision is available from the upstream source link above, including build and
third-party dependency source information; R8's LICENSE retains each component's
own copyright, license terms and source URLs.

The Android platform SDK and application-specific libraries are not included.
Configure D8 libraries/classpath for inputs that require them; do not infer
Android runtime compatibility from the presence of a compiler.
