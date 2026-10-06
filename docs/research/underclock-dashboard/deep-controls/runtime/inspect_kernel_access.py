#!/usr/bin/env python3
"""Inspect the cached stock ARM64 Image and debug module, host-only.

Usage: inspect_kernel_access.py IMAGE KALLSYMS_TEXT DEBUG_MODULE OUTPUT_DIR
Use kallsyms-finder from vmlinux-to-elf 1.3.6 to produce KALLSYMS_TEXT.
Requires Capstone 5.0.6. Never calls firmware or accesses a device.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN


def module_versions(path):
    raw = path.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == "4b096f6f1cf454c92041fd576877c2b84ba2b025a76819d2842b54574ccf577f"
    assert raw[:5] == b"\x7fELF\x02"
    section_offset = struct.unpack_from("<Q", raw, 40)[0]
    section_size, count, name_index = struct.unpack_from("<HHH", raw, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", raw, section_offset + i * section_size) for i in range(count)]
    ns = sections[name_index]
    names = raw[ns[4]:ns[4] + ns[5]]
    versions, metadata = [], []
    for section in sections:
        name = names[section[0]:].split(b"\0")[0].decode()
        data = raw[section[4]:section[4] + section[5]]
        if name == "__versions":
            assert len(data) % 64 == 0
            versions = [{"symbol": data[i + 8:i + 64].split(b"\0")[0].decode(),
                         "crc": hex(struct.unpack_from("<Q", data, i)[0])} for i in range(0, len(data), 64)]
        elif name == ".modinfo":
            metadata = list(filter(None, data.decode().split("\0")))
    assert len(versions) == 23
    return {"module_sha256": hashlib.sha256(raw).hexdigest(), "version_entries": versions, "modinfo": metadata}


def inspect(image, kallsyms, module, output):
    raw = image.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    assert digest == "b53c6063332f1d7057416cb708e443d008634752ec578c56d68040b2af9bcdf3"
    assert raw[56:60] == b"ARMd"
    symbols = {}
    for line in kallsyms.read_text().splitlines():
        fields = line.split()
        if len(fields) == 3:
            symbols[fields[2]] = int(fields[0], 16)
    base = symbols["_text"]
    assert base == 0xffffffc008000000
    decoder = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    def instructions(address, size):
        offset = address - base
        assert 0 <= offset and offset + size <= len(raw)
        return list(decoder.disasm_lite(raw[offset:offset + size], address))

    selected = {"_text": base}
    checks = []
    excerpts = []
    for name, length in [("gki_is_module_protected_symbol", 0x44), ("gki_is_module_exported_symbol", 0x44),
                         ("is_module_sig_enforced", 0x10), ("bsearch", 0xdc)]:
        address = symbols[name]
        selected[name] = address
        decoded = instructions(address, length)
        excerpts.append(f"[{name}]\n" + "\n".join(f"{a:016x} {m} {o}".rstrip() for a, _, m, o in decoded))
        if name.startswith("gki_"):
            # Pinned stock function supplies zero entries before unconditional bsearch.
            assert decoded[8][2:] == ("mov", "x2, xzr")
            assert decoded[10][2] == "bl"
            assert decoded[10][3] == f"#{symbols['bsearch']:#x}"
            assert not any(m.startswith(("b.", "cb", "tb")) for _, _, m, _ in decoded[:11])
            checks.append({"name": name, "address": hex(address), "search_entry_count": 0,
                           "count_instruction": hex(decoded[8][0]), "search_call": hex(decoded[10][0])})
        elif name == "is_module_sig_enforced":
            assert [x[2:] for x in decoded] == [("paciasp", ""), ("mov", "w0, wzr"), ("autiasp", ""), ("ret", "")]
    # Independently inspect the check embedded into stock simplify_symbols.
    embedded = instructions(0xffffffc00823f3f4, 0x2c)
    assert any(m == "mov" and o == "x2, xzr" for _, _, m, o in embedded)
    excerpts.append("[simplify_symbols embedded unsigned-import check]\n" +
                    "\n".join(f"{a:016x} {m} {o}".rstrip() for a, _, m, o in embedded))
    assert any(m == "cbz" and o == "x2, #0xffffffc0087734a8" for _, _, m, o in instructions(symbols["bsearch"], 0x28))

    metadata = module_versions(module)
    comparison = []
    for record in metadata["version_entries"]:
        name = record["symbol"]
        address = symbols["__ksymtab_" + name]
        group = "_gpl" if symbols["__start___ksymtab_gpl"] <= address < symbols["__stop___ksymtab_gpl"] else ""
        for key in ("__start___ksymtab" + group, "__stop___ksymtab" + group,
                    "__start___kcrctab" + group, "__stop___kcrctab" + group, "__ksymtab_" + name):
            selected[key] = symbols[key]
        assert (address - symbols["__start___ksymtab" + group]) % 12 == 0
        index = (address - symbols["__start___ksymtab" + group]) // 12
        crc_address = symbols["__start___kcrctab" + group] + index * 4
        assert crc_address < symbols["__stop___kcrctab" + group]
        name_offset = struct.unpack_from("<i", raw, address - base + 4)[0]
        name_address = address + 4 + name_offset
        actual_name = raw[name_address - base:name_address - base + 128].split(b"\0")[0].decode()
        assert actual_name == name
        crc = struct.unpack_from("<I", raw, crc_address - base)[0]
        assert hex(crc) == record["crc"]
        comparison.append({"symbol": name, "export_group": group or "ordinary", "index": index,
                           "crc_word_address": hex(crc_address), "kernel_crc": hex(crc),
                           "module_crc": record["crc"], "match": True})
    result = {"kernel_sha256": digest, "kernel_base": hex(base), "protected_checks": checks,
              "signature_enforcement_constant_false": True, "debug_module": metadata, "crc_comparison": comparison,
              "limits": "Stock-image static analysis only. Empty compiled lists do not establish another firmware's policy, new-module compatibility, successful loading, HLOS register-write permission, or register semantics. CRCs are comparison evidence, never a recipe to force mismatched modules to load."}
    output.mkdir(parents=True, exist_ok=True)
    (output / "kernel-access-proof.json").write_text(json.dumps(result, indent=2) + "\n")
    (output / "kernel-access-excerpts.txt").write_text("\n\n".join(excerpts) + "\n")
    (output / "kernel-selected-symbols.txt").write_text("\n".join(f"{value:016x} ? {name}" for name, value in sorted(selected.items())) + "\n")
    print(json.dumps({"protected_list_counts": [r["search_entry_count"] for r in checks],
                      "signature_enforced": False, "matched_debug_module_crcs": len(comparison)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("kallsyms", type=Path)
    parser.add_argument("module", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    inspect(args.image, args.kallsyms, args.module, args.output)
