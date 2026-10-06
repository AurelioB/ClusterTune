#!/usr/bin/env python3
"""Bounded HOST-ONLY lookup simulation for the recorded stock Thor TZ image.

Requires Unicorn 2.1.4 and Capstone 5.0.6. No ADB, device access, SMC execution,
MMIO, or firmware patching. Only lookup code, its read-only jump table, a scratch
response, and identified constant-return stubs are mapped into the emulator.
This is not a runtime firmware availability query or full-system emulation.
Usage: emulate_lmh_dispatch.py LOCAL_TZ_IMAGE OUTPUT_DIRECTORY
"""

import argparse
import hashlib
import json
import struct
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
from unicorn import UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X30


EXPECTED_HASH = "917e26b15114884cd54bc47fdb40ae43b63ee6281ce74f217d3990dcdfa1d206"
LOOKUP = (0x156c4934, 0x156c6674)
SCRATCH = 0x10000000
STOP = 0x20000000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    data = args.image.read_bytes()
    if hashlib.sha256(data).hexdigest() != EXPECTED_HASH:
        raise ValueError("Image hash does not match the recorded stock firmware")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    segments = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size)
                for i in range(count)]

    def read(address, length):
        for typ, flags, file_offset, va, pa, file_size, mem_size, align in segments:
            if typ == 1 and va <= address and address + length <= va + file_size:
                start = file_offset + address - va
                return data[start:start + length]
        raise ValueError(f"Not file-backed: {address:#x}+{length:#x}")

    def simulate(start, allowed, call=None):
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        if call is not None:
            maps = [(0x156c4000, 0x3000), (0x1577c000, 0x1000)]
        else:
            maps = [(start & ~0xfff, 0x1000)]
        for address, length in maps:
            uc.mem_map(address, length, UC_PROT_READ | UC_PROT_EXEC)
            uc.mem_write(address, read(address, length))
        uc.mem_map(SCRATCH, 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        uc.mem_map(STOP, 0x1000, UC_PROT_READ | UC_PROT_EXEC)
        uc.reg_write(UC_ARM64_REG_X30, STOP)
        uc.reg_write(UC_ARM64_REG_X1, SCRATCH)
        # Stub tests deliberately supply an unmapped argument; a request read
        # would fail rather than silently use invented hardware/memory state.
        uc.reg_write(UC_ARM64_REG_X0, call if call is not None else 0xdead0000)
        visited = []
        returned = []
        writes = []

        def instruction(emu, address, length, context):
            if address == STOP:
                returned.append(True)
                emu.emu_stop()
                return
            if not allowed[0] <= address < allowed[1]:
                raise ValueError(f"Execution escaped bounded routine: {address:#x}")
            opcode = int.from_bytes(emu.mem_read(address, 4), "little")
            if opcode & 0xffe0001f == 0xd4000003:
                raise ValueError("SMC instruction rejected")
            visited.append(hex(address))

        def write(emu, access, address, length, value, context):
            if not SCRATCH <= address or address + length > SCRATCH + 0x1000:
                raise ValueError(f"Write escaped scratch response: {address:#x}")
            writes.append(dict(address=hex(address), bytes=length, value=hex(value)))

        uc.hook_add(UC_HOOK_CODE, instruction)
        uc.hook_add(UC_HOOK_MEM_WRITE, write)
        uc.emu_start(start, STOP + 4, count=5000)
        if not returned:
            raise ValueError("Instruction budget exhausted before return")
        return dict(x0=uc.reg_read(UC_ARM64_REG_X0),
                    response=bytes(uc.mem_read(SCRATCH, 24)),
                    visited=visited, writes=writes)

    results = []
    for call in (0x02000601, 0x02000205, 0x02000501,
                 0x02001301, 0x02001308, 0x0200130b,
                 0x02001310, 0x02001311, 0x02001312):
        result = simulate(LOOKUP[0], LOOKUP, call)
        _, returned_id, params, flags, handler = struct.unpack("<4IQ", result.pop("response"))
        result.update(call_id=hex(call), returned_id=hex(returned_id),
                      param_id=hex(params), flags=hex(flags), handler_va=hex(handler))
        if returned_id != call or result["x0"] != 0:
            raise ValueError(f"Lookup did not find expected command: {call:#x}")
        # Only emulate a handler after matching all 16 bytes to this stub form.
        if read(handler, 16).hex() == "5f2403d560008012c0035fd6ff3003d5":
            stub = simulate(handler, (handler, handler + 12))
            result["stub_return_s32"] = struct.unpack("<i", struct.pack(
                "<I", stub["x0"] & 0xffffffff))[0]
            result["stub_visited"] = stub["visited"]
            result["stub_writes"] = stub["writes"]
        results.append(result)
    args.output.mkdir(parents=True, exist_ok=True)
    output = dict(sha256=EXPECTED_HASH, method="bounded host-only emulation",
                  runtime_device_queries=False, results=results)
    (args.output / "fcap-proof-dispatch.json").write_text(json.dumps(output, indent=2) + "\n")
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    ranges = [(LOOKUP[0], 0xa0), (0x156c5ed8, 0x1dc), (0x156c6638, 0x1c),
              (0x156edecc, 0xb0), (0x15696094, 0x94),
              (0x15694774, 0x10), (0x15696868, 0x200),
              (0x15652d08, 0x14)]
    lines = ["# Static stock TZ excerpts; virtual addresses, no on-device execution."]
    for address, length in ranges:
        lines.append(f"\n# range {address:#x}+{length:#x}")
        lines.extend(f"{a:08x} {m} {o}".rstrip() for a, _, m, o in
                     md.disasm_lite(read(address, length), address))
    lines.append("\n# LMH jump table at 0x1577cb24: " + read(0x1577cb24, 36).hex(" "))
    (args.output / "fcap-proof-tz-excerpts.txt").write_text("\n".join(lines) + "\n")
    print(json.dumps([{k: r[k] for k in ("call_id", "param_id", "handler_va")}
                      | ({"stub_return_s32": r["stub_return_s32"]}
                         if "stub_return_s32" in r else {}) for r in results], indent=2))


if __name__ == "__main__":
    main()
