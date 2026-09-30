#!/usr/bin/env python3
"""Reject mislabeled and malformed native binaries before packaging a Play AAB."""

import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("native_alignment", Path(__file__).with_name("check-native-page-alignment.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def fixture(abi: str, *, alignment: int = 16384, address: int = 0,
            relro_size: int = 16384, include_load: bool = True,
            include_relro: bool = True) -> bytes:
    elf_class, machine = checker.ABI_IDENTITIES[abi]
    header_size, program_size = (52, 32) if elf_class == 1 else (64, 56)
    entries = []
    if include_load:
        entries.append((checker.PT_LOAD, 0, address, 512, 32768, alignment))
    if include_relro:
        entries.append((checker.PT_GNU_RELRO, 256, 16384, 256, relro_size, 1))
    data = bytearray(512)
    data[:7] = b"\x7fELF" + bytes((elf_class, 1, 1))
    struct.pack_into("<HHI", data, 16, 3, machine, 1)
    if elf_class == 1:
        struct.pack_into("<I", data, 28, header_size)
        struct.pack_into("<HHH", data, 40, header_size, program_size, len(entries))
    else:
        struct.pack_into("<Q", data, 32, header_size)
        struct.pack_into("<HHH", data, 52, header_size, program_size, len(entries))
    for index, (kind, offset, start, size, memory, page_alignment) in enumerate(entries):
        position = header_size + index * program_size
        if elf_class == 1:
            struct.pack_into("<IIIIIIII", data, position, kind, offset, start, start, size, memory, 6, page_alignment)
        else:
            struct.pack_into("<IIQQQQQQ", data, position, kind, 6, offset, start, start, size, memory, page_alignment)
    return bytes(data)


class NativeAlignmentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def library(self, content, parent="stage"):
        target = self.root / parent / "libtranscribe.so"
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(content)
        return target

    def test_all_four_abis(self):
        for abi in checker.ABI_IDENTITIES:
            with self.subTest(abi=abi):
                self.assertEqual(checker.check_library(self.library(fixture(abi), abi)), abi)

    def test_wrong_architecture_in_directory(self):
        with self.assertRaisesRegex(ValueError, "expected x86_64, found arm64-v8a"):
            checker.check_library(self.library(fixture("arm64-v8a"), "x86_64"))

    def test_explicit_expected_abi(self):
        with self.assertRaisesRegex(ValueError, "expected x86, found armeabi-v7a"):
            checker.check_library(self.library(fixture("armeabi-v7a")), "x86")

    def test_argument_cannot_override_mislabeled_directory(self):
        with self.assertRaisesRegex(ValueError, "directory ABI"):
            checker.check_library(self.library(fixture("arm64-v8a"), "x86_64"), "arm64-v8a")

    def test_rejects_4k_alignment_all_abis(self):
        for abi in checker.ABI_IDENTITIES:
            with self.subTest(abi=abi), self.assertRaisesRegex(ValueError, "LOAD alignment"):
                checker.check_library(self.library(fixture(abi, alignment=4096)))

    def test_rejects_noncongruent_load(self):
        for abi in checker.ABI_IDENTITIES:
            with self.subTest(abi=abi), self.assertRaisesRegex(ValueError, "not 16 KB congruent"):
                checker.check_library(self.library(fixture(abi, address=4096)))

    def test_rejects_relro_partial_page(self):
        for abi in checker.ABI_IDENTITIES:
            with self.subTest(abi=abi), self.assertRaisesRegex(ValueError, "GNU_RELRO end"):
                checker.check_library(self.library(fixture(abi, relro_size=4096)))

    def test_requires_load_and_relro(self):
        for options in ({"include_load": False}, {"include_relro": False}):
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "expected LOAD and GNU_RELRO"):
                checker.check_library(self.library(fixture("arm64-v8a", **options)))

    def test_rejects_truncated_headers_and_segments(self):
        for abi in checker.ABI_IDENTITIES:
            for length in (0, 19, 30, 70, 511):
                with self.subTest(abi=abi, length=length), self.assertRaises(ValueError):
                    checker.check_library(self.library(fixture(abi)[:length]))

    def test_rejects_wrong_elf_class_machine_pair(self):
        data = bytearray(fixture("arm64-v8a"))
        struct.pack_into("<H", data, 18, 40)
        with self.assertRaisesRegex(ValueError, "unsupported Android ELF identity"):
            checker.check_library(self.library(data))

    def test_requires_complete_directory(self):
        path = self.library(fixture("x86"), "x86").parent
        with self.assertRaisesRegex(ValueError, "incomplete native engine"):
            checker.expand_libraries([path])
        for name in checker.ENGINE_LIBRARIES:
            (path / name).write_bytes(fixture("x86"))
        self.assertEqual(len(checker.expand_libraries([path])), 5)


if __name__ == "__main__":
    unittest.main()
