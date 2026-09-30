#!/usr/bin/env python3
"""Check Android ELF identity and LOAD/GNU_RELRO layout for 16 KB pages.

Directories must contain the complete five-library speech engine. Individual
files remain supported for inspecting a staged mix of new and retained libraries.
"""

import argparse
from pathlib import Path
import struct
import sys

PAGE_SIZE = 16384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
ABI_IDENTITIES = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
ENGINE_LIBRARIES = frozenset({
    "libggml.so", "libggml-base.so", "libggml-cpu.so",
    "libtranscribe.so", "libnemotron_jni.so",
})


def check_library(path: Path, expected_abi: str | None = None) -> str:
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5:7] != b"\x01\x01":
        raise ValueError("expected a little-endian ELF32 or ELF64 library")
    elf_class = data[4]
    header_size, program_size = (52, 32) if elf_class == 1 else (64, 56)
    if len(data) < header_size:
        raise ValueError("truncated ELF header")
    kind, machine, version = struct.unpack_from("<HHI", data, 16)
    if kind != 3 or version != 1:
        raise ValueError("expected a version 1 ELF shared library")
    abi = next((name for name, identity in ABI_IDENTITIES.items() if identity == (elf_class, machine)), None)
    if abi is None:
        raise ValueError(f"unsupported Android ELF identity: class={elf_class}, machine={machine}")
    path_abi = path.parent.name if path.parent.name in ABI_IDENTITIES else None
    if expected_abi is not None and path_abi is not None and expected_abi != path_abi:
        raise ValueError(f"directory ABI {path_abi} differs from expected ABI {expected_abi}")
    expected_abi = expected_abi or path_abi
    if expected_abi is not None and abi != expected_abi:
        raise ValueError(f"expected {expected_abi}, found {abi}")
    if elf_class == 1:
        table_offset = struct.unpack_from("<I", data, 28)[0]
        declared_header_size, entry_size, count = struct.unpack_from("<HHH", data, 40)
    else:
        table_offset = struct.unpack_from("<Q", data, 32)[0]
        declared_header_size, entry_size, count = struct.unpack_from("<HHH", data, 52)
    if declared_header_size != header_size or entry_size != program_size or count == 0 or table_offset < header_size or table_offset + entry_size * count > len(data):
        raise ValueError("invalid program-header table")
    load_count = 0
    relro_count = 0
    for index in range(count):
        position = table_offset + entry_size * index
        if elf_class == 1:
            kind, offset, address, _, file_size, memory_size, _, alignment = struct.unpack_from("<IIIIIIII", data, position)
        else:
            kind, _, offset, address, _, file_size, memory_size, alignment = struct.unpack_from("<IIQQQQQQ", data, position)
        if kind in (PT_LOAD, PT_GNU_RELRO):
            if file_size > memory_size or offset + file_size > len(data):
                raise ValueError("invalid LOAD or GNU_RELRO segment size")
            if address + memory_size > (1 << (32 if elf_class == 1 else 64)):
                raise ValueError("LOAD or GNU_RELRO address range overflows")
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
    return abi


def expand_libraries(paths: list[Path]) -> list[Path]:
    libraries = []
    for path in paths:
        if path.is_dir():
            found = sorted(path.glob("*.so"))
            missing = ENGINE_LIBRARIES - {item.name for item in found}
            if missing:
                raise ValueError(f"{path}: incomplete native engine; missing {', '.join(sorted(missing))}")
            libraries.extend(found)
        else:
            libraries.append(path)
    return libraries


def main() -> int:
    default = Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABI_IDENTITIES, help="Require this ABI in every library; ABI directory names are checked automatically.")
    parser.add_argument("paths", nargs="*", type=Path, help="Library files or complete engine ABI directories.")
    args = parser.parse_args()
    try:
        libraries = expand_libraries(args.paths or [default])
    except ValueError as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 1
    failures = 0
    for library in libraries:
        try:
            abi = check_library(library, args.abi)
            print(f"PASS {library}: {abi}, 16 KB LOAD and RELRO alignment")
        except (OSError, ValueError, struct.error) as error:
            failures += 1
            print(f"FAIL {library.name}: {error}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
