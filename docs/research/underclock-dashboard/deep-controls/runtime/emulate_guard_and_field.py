#!/usr/bin/env python3
"""Host-only CPUCP initializer-guard and isolated field-update simulation.

Requires Unicorn 2.1.4. Synthetic register memory has no hardware effects.
This does not model full initialization, live ownership, or register semantics.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

from unicorn import Uc, UC_ARCH_RISCV, UC_MODE_RISCV32, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
from unicorn.riscv_const import UC_RISCV_REG_PC, UC_RISCV_REG_SP, UC_RISCV_REG_RA, UC_RISCV_REG_A0, UC_RISCV_REG_S0


def machine(raw):
    u = Uc(UC_ARCH_RISCV, UC_MODE_RISCV32)
    pages = set()
    phoff = struct.unpack_from("<I", raw, 28)[0]
    phsize, count = struct.unpack_from("<HH", raw, 42)
    for i in range(count):
        kind, off, va, _, size, memsize, _, _ = struct.unpack_from("<8I", raw, phoff + i * phsize)
        if kind != 1:
            continue
        for page in range(va & ~4095, (va + memsize + 4095) & ~4095, 4096):
            if page not in pages:
                u.mem_map(page, 4096)
                pages.add(page)
        if size:
            u.mem_write(va, raw[off:off + size])
    u.mem_map(0x10000000, 0x3000)
    u.reg_write(UC_RISCV_REG_SP, 0x10001000)
    u.reg_write(UC_RISCV_REG_RA, 0x10002000)
    return u


def run(image):
    raw = image.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    assert digest == "19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808"
    guarded = []
    updates = []
    for domain in range(4):
        u = machine(raw)
        u.mem_write(0x17d0d478, b"\x01")  # Synthetic getter initialization flag.
        u.mem_write(0xd82f7a58 + domain * 24 + 12, struct.pack("<I", 1))
        writes, steps = [], []
        def trace(u, pc, size, _):
            assert (0xd82e0d04 <= pc < 0xd82e0d30 or 0xd82e09bc <= pc < 0xd82e09e6 or pc == 0x10002000), hex(pc)
            steps.append(hex(pc))
            if pc == 0x10002000:
                u.emu_stop()
        def write(u, access, address, size, value, _):
            assert 0x10000000 <= address and address + size <= 0x10001000, hex(address)
            writes.append({"pc": hex(u.reg_read(UC_RISCV_REG_PC)), "address": hex(address), "size": size, "value": hex(value)})
        u.hook_add(UC_HOOK_CODE, trace)
        u.hook_add(UC_HOOK_MEM_WRITE, write)
        u.reg_write(UC_RISCV_REG_A0, domain)
        u.emu_start(0xd82e0d04, 0x10002000, count=200)
        assert u.reg_read(UC_RISCV_REG_PC) == 0x10002000
        assert u.reg_read(UC_RISCV_REG_A0) == 0
        guarded.append({"domain": domain, "return_value": 0, "mmio_pages_mapped": False,
                        "instruction_trace": steps, "stack_writes": writes})

    branches = [(0, 0xd82e0df8, 0xd82e0e04, False), (1, 0xd82e1082, 0xd82e108e, False),
                (2, 0xd82e109e, 0xd82e10ac, True), (3, 0xd82e0dde, 0xd82e0dec, True)]
    for domain, entry, end, setting in branches:
        for seed in (0, 0xffffffff, 0xa5a5a5a5):
            u = machine(raw)
            base = 0x17d90000 + domain * 0x1000
            u.mem_map(base, 4096)  # Host scratch memory, not hardware MMIO.
            u.mem_write(base + 0xbc, struct.pack("<I", seed))
            u.reg_write(UC_RISCV_REG_S0, 0xd82f7a58 + domain * 24)
            writes, steps = [], []
            def trace(u, pc, size, _):
                assert entry <= pc <= end, hex(pc)
                steps.append(hex(pc))
                if pc == end:
                    u.emu_stop()
            def write(u, access, address, size, value, _):
                assert address == base + 0xbc and size == 4, hex(address)
                writes.append({"pc": hex(u.reg_read(UC_RISCV_REG_PC)), "value": hex(value)})
            u.hook_add(UC_HOOK_CODE, trace)
            u.hook_add(UC_HOOK_MEM_WRITE, write)
            u.emu_start(entry, end, count=20)
            assert u.reg_read(UC_RISCV_REG_PC) == end
            actual = struct.unpack("<I", u.mem_read(base + 0xbc, 4))[0]
            expected = seed | 1 if setting else seed & 0xfffffffe
            assert actual == expected and len(writes) == 1
            updates.append({"domain": domain, "synthetic_address": hex(base + 0xbc), "before": hex(seed),
                            "after": hex(actual), "other_bits_preserved": True, "writes": writes, "trace": steps})
    return {"firmware_sha256": digest, "method": "Native stock instructions run only on host. Guard cases map no EPSS MMIO pages. Field cases model isolated startup RMW fragments with synthetic memory. Strict PC/write bounds and instruction budgets apply.",
            "limits": "Does not simulate full CPUCP boot, live register protection, register side effects, synchronization, resume, or another firmware writer. A passing RMW model does not justify a runtime register write.",
            "already_initialized_cases": guarded, "isolated_field_cases": updates}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = run(args.image)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"guard_cases": len(result["already_initialized_cases"]),
                      "field_cases": len(result["isolated_field_cases"]), "all_passed": True}))
