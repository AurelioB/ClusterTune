#!/usr/bin/env python3
"""Host-only excerpts for the stock Thor firmware identified in the manifest.

Requires Capstone 5.0.6. Reads local files; never accesses a device or executes
firmware. Addresses are specific to the recorded stock images, not other builds.
Usage: inspect_limit_handlers.py ARTIFACT_DIRECTORY OUTPUT_DIRECTORY
"""

import argparse
import hashlib
import json
import struct
from pathlib import Path

from capstone import (
    Cs, CS_ARCH_ARM64, CS_ARCH_RISCV, CS_MODE_LITTLE_ENDIAN,
    CS_MODE_RISCV32, CS_MODE_RISCVC,
)


def load_image(path):
    data = path.read_bytes()
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Expected little-endian ELF")
    if data[4] == 1:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        segments = [struct.unpack_from("<8I", data, offset + i * size)
                    for i in range(count)]
        loads = [(s[1], s[2], s[4]) for s in segments if s[0] == 1]
    else:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        segments = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size)
                    for i in range(count)]
        loads = [(s[2], s[3], s[5]) for s in segments if s[0] == 1]

    def read(address, length):
        for file_offset, va, file_size in loads:
            if va <= address and address + length <= va + file_size:
                start = file_offset + address - va
                return data[start:start + length]
        raise ValueError(f"Range not file-backed: {address:#x}+{length:#x}")

    return data, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    expected = json.loads(Path(__file__).with_name(
        "ownership-stock-manifest.json").read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    images = {}
    for name in ("cpucp_b.bin", "hyp_b.bin"):
        data, read = load_image(args.artifacts / name)
        if hashlib.sha256(data).hexdigest() != expected[name]["sha256"]:
            raise ValueError(f"Image hash does not match the recorded build: {name}")
        images[name] = read

    read = images["cpucp_b.bin"]
    cpu = Cs(CS_ARCH_RISCV, CS_MODE_RISCV32 | CS_MODE_RISCVC)
    ranges = [(0x17d06210, 0x13a), (0x17d06434, 0x14),
              (0x17d068e0, 0x12), (0xd82e5f5a, 0x36),
              (0xd82e7462, 0x54), (0xd82e74c0, 0x90)]
    lines = ["# CPUCP stock static disassembly; branch offsets are PC-relative."]
    for start, length in ranges:
        lines.append(f"\n# range {start:#x}+{length:#x}")
        lines.extend(f"{a:08x} {m} {o}".rstrip() for a, _, m, o in
                     cpu.disasm_lite(read(start, length), start))
    for address, length in [(0x17d0e0f4, 52), (0x17d28610, 0x48),
                            (0x17d28658, 6)]:
        lines.append(f"\n# data {address:#x}: {read(address, length).hex(' ')}")
    (args.output / "ownership-cpucp-excerpts.txt").write_text("\n".join(lines) + "\n")

    read = images["hyp_b.bin"]
    relocations = {}
    for offset in range(0, 31104, 24):
        address, info, addend = struct.unpack("<QQq", read(0xf6218 + offset, 24))
        if info & 0xffffffff == 1027:
            relocations[address] = addend
    entries = []
    for address in range(0x218000, 0x218420, 24):
        service, call, params, flags = struct.unpack("<4I", read(address, 16))
        entries.append(dict(va=hex(address), mink_service=service,
                            smc_id=hex(call), param_id=hex(params), flags=hex(flags),
                            handler_va=hex(relocations[address + 16])))
    result = dict(table_start="0x218000", table_end="0x218420",
                  entry_count=len(entries), entries=entries)
    (args.output / "ownership-hyp-dispatch.json").write_text(
        json.dumps(result, indent=2) + "\n")
    arm = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    lines = ["# HYP stock static disassembly; this does not execute SMC."]
    for start, length in [(0xdc10, 0xd0), (0x8d6b4, 0xac)]:
        lines.append(f"\n# range {start:#x}+{length:#x}")
        lines.extend(f"{a:08x} {m} {o}".rstrip() for a, _, m, o in
                     arm.disasm_lite(read(start, length), start))
    (args.output / "ownership-hyp-excerpts.txt").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
