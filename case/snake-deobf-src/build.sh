#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source_root="$root/src"
if [ ! -d "$source_root" ]; then source_root="$root/deobf/src"; fi
if [ ! -d "$source_root" ]; then echo "Source tree not found." >&2; exit 1; fi
if [ -n "${JAVA_HOME:-}" ]; then
    compiler="$JAVA_HOME/bin/javac"
    archiver="$JAVA_HOME/bin/jar"
else
    compiler=javac
    archiver=jar
fi
mkdir -p "$root/build/classes" "$root/dist"
find "$source_root" -name '*.java' -exec "$compiler" --release 21 -encoding UTF-8 -Xlint:all -d "$root/build/classes" {} +
"$archiver" --create --file "$root/dist/snake-deobf.jar" --main-class snake.Snake -C "$root/build/classes" .
echo "$root/dist/snake-deobf.jar"
