#!/usr/bin/env python3
"""Bounded host-only pointer tracking after CPUCP's domain getter returns.

This is an inventory, not a complete firmware ownership or concurrency proof.
Requires Capstone 5.0.6. No firmware execution or device access.
"""
import argparse
import hashlib
import json
import re
import struct
from collections import deque
from pathlib import Path

from capstone import Cs, CS_ARCH_RISCV, CS_MODE_RISCV32, CS_MODE_RISCVC


def inspect(image):
    raw = image.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    assert digest == "19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808"
    assert raw[:5] == b"\x7fELF\x01"
    phoff = struct.unpack_from("<I", raw, 28)[0]
    phsize, phcount = struct.unpack_from("<HH", raw, 42)
    decoder = Cs(CS_ARCH_RISCV, CS_MODE_RISCV32 | CS_MODE_RISCVC)
    decoder.skipdata = True
    instructions = []
    for i in range(phcount):
        kind, off, va, _, size, _, flags, _ = struct.unpack_from("<8I", raw, phoff + phsize * i)
        if kind == 1 and flags & 1:
            instructions.extend(decoder.disasm_lite(raw[off:off + size], va))
    code = {a: (size, mnemonic, operands) for a, size, mnemonic, operands in instructions}
    calls = []
    getters = {0xd82e09bc, 0xd82e09e6}
    for i, (a, size, mnemonic, operands) in enumerate(instructions):
        fields = [f.strip() for f in operands.split(",")]
        target = None
        if mnemonic in ("jal", "c.jal") and len(fields) == 1:
            target = (a + int(fields[0], 0)) & 0xffffffff
        elif mnemonic == "jalr" and i:
            pa, ps, pm, po = instructions[i - 1]
            pf = [f.strip() for f in po.split(",")]
            if pm == "auipc" and pa + ps == a and len(fields) == 3 and fields[1] == pf[0]:
                target = (pa + (int(pf[1], 0) << 12) + int(fields[2], 0)) & 0xfffffffe
        if target in getters:
            calls.append((a, size, target))

    def plus(value, delta):
        if isinstance(value, tuple):
            return value[0], value[1] + delta
        if isinstance(value, int):
            return (value + delta) & 0xffffffff
        return None

    records = {}
    exits = {}
    counts = []
    # Merge keeps only facts identical along all paths. Unknown is omitted.
    for entry, entrysize, target in calls:
        states = {}
        queue = deque()
        initial = {"a0": ("object", 0), "sp": ("stack", 0), "zero": 0}
        def schedule(pc, registers, stack):
            if pc not in code:
                exits[(entry, pc, "outside decoded code")] = True
                return
            candidate = (registers, stack)
            if pc in states:
                oldr, oldm = states[pc]
                candidate = ({k: v for k, v in oldr.items() if registers.get(k) == v},
                             {k: v for k, v in oldm.items() if stack.get(k) == v})
                if candidate == states[pc]:
                    return
            states[pc] = candidate
            queue.append(pc)
        schedule(entry + entrysize, initial, {})
        steps = 0
        while queue and steps < 10000:
            pc = queue.popleft()
            before, memory = states[pc]
            regs, stack = before.copy(), memory.copy()
            size, mnemonic, operands = code[pc]
            fields = [f.strip() for f in operands.split(",")]
            steps += 1
            value = None
            destination = fields[0] if fields else None
            successors = [pc + size]
            no_destination = False
            try:
                if mnemonic in ("li", "c.li"):
                    value = int(fields[1], 0) & 0xffffffff
                elif mnemonic in ("lui", "c.lui"):
                    value = (int(fields[1], 0) << 12) & 0xffffffff
                elif mnemonic == "auipc":
                    value = (pc + (int(fields[1], 0) << 12)) & 0xffffffff
                elif mnemonic in ("mv", "c.mv"):
                    value = regs.get(fields[1])
                elif mnemonic in ("addi", "c.addi", "c.addi16sp"):
                    source, immediate = (fields[1], fields[2]) if len(fields) == 3 else (fields[0], fields[1])
                    value = plus(regs.get(source), int(immediate, 0))
                elif mnemonic == "c.addi4spn":
                    value = plus(regs.get(fields[1]), int(fields[2], 0))
                elif mnemonic in ("add", "c.add"):
                    left, right = (fields[1], fields[2]) if len(fields) == 3 else (fields[0], fields[1])
                    if isinstance(regs.get(right), int):
                        value = plus(regs.get(left), regs[right])
                    elif isinstance(regs.get(left), int):
                        value = plus(regs.get(right), regs[left])
                elif mnemonic in ("lw", "c.lw", "c.lwsp", "lbu", "lh", "lhu", "lb", "sw", "c.sw", "c.swsp", "sb", "sh"):
                    match = re.fullmatch(r"([^()]+)\(([^()]+)\)", fields[1])
                    address = plus(regs.get(match[2]), int(match[1], 0)) if match else None
                    writing = mnemonic in ("sw", "c.sw", "c.swsp", "sb", "sh")
                    no_destination = writing
                    if isinstance(address, tuple):
                        kind, offset = address
                        if kind == "stack":
                            if writing:
                                if fields[0] in regs:
                                    stack[offset] = regs[fields[0]]
                                else:
                                    stack.pop(offset, None)
                            else:
                                value = stack.get(offset)
                        else:
                            key = (pc, kind, offset, writing)
                            records.setdefault(key, {"pc": hex(pc), "kind": kind, "offset": hex(offset),
                                "access": "write" if writing else "read", "instruction": mnemonic + " " + operands,
                                "source_values": set(), "getter_calls": set()})
                            record = records[key]
                            record["getter_calls"].add(hex(entry))
                            if writing:
                                record["source_values"].add(repr(regs.get(fields[0])))
                            elif kind == "object" and offset == 0x14 and mnemonic in ("lw", "c.lw"):
                                value = ("epss", 0)
                elif mnemonic in ("jal", "c.jal", "jalr", "c.jalr"):
                    # Model ordinary ABI call clobbers. The scan does not enter callees.
                    for key in list(regs):
                        if key == "ra" or re.fullmatch(r"[at][0-9]+", key):
                            regs.pop(key, None)
                    no_destination = True
                elif mnemonic in ("j", "c.j"):
                    successors = [(pc + int(fields[0], 0)) & 0xffffffff]
                    no_destination = True
                elif mnemonic.startswith(("b", "c.b")):
                    successors.append((pc + int(fields[-1], 0)) & 0xffffffff)
                    no_destination = True
                elif mnemonic in ("ret", "jr", "c.jr"):
                    successors = []
                    if operands not in ("", "ra"):
                        exits[(entry, pc, "unresolved indirect transfer")] = True
                    no_destination = True
                elif mnemonic.startswith("csr") or mnemonic in ("nop", "c.nop"):
                    no_destination = True
                elif mnemonic == ".byte":
                    successors = []
                    exits[(entry, pc, "undecoded instruction/data")] = True
                    no_destination = True
                if not no_destination and destination and re.fullmatch(r"[ast][0-9]+|ra|sp|gp|tp", destination):
                    if value is None:
                        regs.pop(destination, None)
                    else:
                        regs[destination] = value
                regs["zero"] = 0
                for nextpc in successors:
                    schedule(nextpc, regs, stack)
            except (ValueError, IndexError):
                exits[(entry, pc, "unsupported operand parsing")] = True
        counts.append({"getter_call": hex(entry), "getter": hex(target), "visited_states": len(states),
                       "worklist_steps": steps, "budget_exhausted": bool(queue)})

    accesses = []
    for key, record in sorted(records.items()):
        record["source_values"] = sorted(record["source_values"])
        record["getter_calls"] = sorted(record["getter_calls"])
        accesses.append(record)
    return {"firmware_sha256": digest, "method": "CFG-local abstract pointer tracking seeded after direct getter returns; assumes a valid returned object. Callees are not entered. Branches explored both ways; unequal facts merge to unknown.",
            "limitations": "Not a whole-program alias proof. Indirect object construction, unresolved jumps, helper-mediated writes, firmware input, other processors and hardware effects are outside this scan. Numeric and pointer facts are deliberately dropped for unsupported operations. No device or firmware execution.",
            "getter_runs": counts, "domain_and_epss_accesses": accesses,
            "domain_flag_writes": [r for r in accesses if r["kind"] == "object" and r["offset"] == "0xc" and r["access"] == "write"],
            "srb_register_accesses": [r for r in accesses if r["kind"] == "epss" and r["offset"] == "0xbc"],
            "unresolved_exits": [{"getter_call": hex(a), "pc": hex(b), "reason": c} for a, b, c in sorted(exits)]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = inspect(args.image)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"getter_runs": len(result["getter_runs"]), "domain_flag_writes": result["domain_flag_writes"],
                      "srb_register_accesses": result["srb_register_accesses"], "unresolved_exits": len(result["unresolved_exits"])}, indent=2))
