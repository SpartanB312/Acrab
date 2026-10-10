#!/usr/bin/env python3
"""Create the public source case ZIP with stable metadata and portable entry names."""

from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def package():
    source = ROOT / "case/snake-deobf-src"
    files = sorted([source / "README.md", source / "build.ps1", source / "build.sh"] +
        list((source / "src").rglob("*.java")) + list((source / "verification").glob("*.java")) +
        list((source / "reports").glob("*.md")))
    destination = ROOT / "case/snake-deobf-src.zip"
    temporary = destination.with_suffix(".pending")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in files:
            entry = zipfile.ZipInfo("snake-deobf-src/" + file.relative_to(source).as_posix(), (2026, 10, 10, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (0o100755 if file.suffix == ".sh" else 0o100644) << 16
            archive.writestr(entry, file.read_bytes())
        license_entry = zipfile.ZipInfo("snake-deobf-src/LICENSE", (2026, 10, 10, 0, 0, 0))
        license_entry.compress_type = zipfile.ZIP_DEFLATED
        license_entry.external_attr = 0o100644 << 16
        archive.writestr(license_entry, (ROOT / "LICENSE").read_bytes())
    temporary.replace(destination)
    return destination


if __name__ == "__main__":
    print(package())
