#!/usr/bin/env python3
"""Check Android arm64 ELF LOAD and GNU_RELRO layout for 16 KB pages."""

from pathlib import Path
import struct
import sys

PAGE_SIZE = 16384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552


def check_library(path: Path) -> None:
    data = path.read_bytes()
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected a little-endian ELF64 library")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("expected an arm64 library")
    table_offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count == 0 or table_offset + entry_size * count > len(data):
        raise ValueError("invalid program-header table")
    load_count = 0
    relro_count = 0
    for index in range(count):
        kind, _, offset, address, _, _, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, table_offset + entry_size * index
        )
        if kind == PT_LOAD:
            load_count += 1
            if alignment < PAGE_SIZE or alignment & (alignment - 1):
                raise ValueError(f"LOAD alignment {alignment} does not support 16 KB pages")
            if (address - offset) % PAGE_SIZE:
                raise ValueError("LOAD file offset and virtual address are not 16 KB congruent")
        if kind == PT_GNU_RELRO:
            relro_count += 1
            if (address + memory_size) % PAGE_SIZE:
                raise ValueError("GNU_RELRO end is not aligned to 16 KB")
    if load_count == 0 or relro_count == 0:
        raise ValueError("expected LOAD and GNU_RELRO program headers")


def main() -> int:
    default = Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a"
    libraries = [Path(argument) for argument in sys.argv[1:]] if len(sys.argv) > 1 else sorted(default.glob("*.so"))
    if not libraries:
        print("No native libraries found", file=sys.stderr)
        return 1
    failures = 0
    for library in libraries:
        try:
            check_library(library)
            print(f"PASS {library.name}: 16 KB LOAD and RELRO alignment")
        except (OSError, ValueError, struct.error) as error:
            failures += 1
            print(f"FAIL {library.name}: {error}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
