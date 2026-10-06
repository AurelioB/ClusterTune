# Deeper audit of stock CPUCP protocol 0x83

No reachable CPU cap or boost/SRB-disable command was established through 0x83. The strongest supported explanation is a mismatch between a fixed advertisement list and separately registered firmware modules. The examined stock initialization paths register vendor protocols 0x86, 0x80, 0x87 and 0x84; no 0x83 handler or module ABI was identified. The identity of the reserved advertised ID remains unknown. This is stronger than merely failing to find an immediate registration instruction, but is not a live proof against external firmware state or arbitrary computed code paths.

All work was host-only, under /tmp/ct-vendor83-deep. No device command, kernel module load, reboot or MMIO access was performed. Offline emulation uses allocated synthetic memory only. Stock image SHA-256: 19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808.

## Advertisement and runtime module list are independent

The six advertised bytes at 0x17d28658 are [0x13,0x83,0x86,0x87,0x80,0x84]. SCMI initializer 0xd82e5f5a writes the literal count6 to 0x17d28664 at 0xd82e5f64. It initializes channel buffers and their registers, then passes callback initializer table0x17d28610 to 0xd82e74c0. It does not derive the advertisement list from the runtime vendor registration table.

Advertisement callback0xd82e7462 reads those six bytes and packs up to four IDs per response word. A separate count accessor0xd82e74b6 reads 0x17d28664. The list callback is installed at runtime table0x17d0d520 by0xd82e74c0. Base-protocol advertisement path0x17d06192 invokes it; base attributes path0x17d06112 reads the count directly. In contrast, the vendor dispatcher0x17d06808 uses a different nine-entry table0x17d0d528, whose initial eighteen u32 words are all zero.

An offline run of the complete SCMI initializer with zero-valued synthetic MMIO/register pages returns after2201 instructions, sets advertisement count6 and leaves the vendor table entirely zero. Thus advertising0x83 is not evidence that its module has registered.

## Initialization and module-event graph

Main firmware task initialization0x17d23492 calls shared main initializer0x17d22f92 at0x17d23512. Relevant synchronous calls from0x17d22f92 are:

| Call | Target | Consequence |
|---|---|---|
| 0x17d22fe2 | 0xd82e5f5a | SCMI channels, fixed advertisement and standard callbacks |
| 0x17d23026 | 0xd82e5836 | Registers PMU0x86 through0xd82e588a |
| 0x17d23040 | 0xd82e6862 | Registers MEMLAT0x80 through0xd82e687e |

Module-event dispatcher0x17d2355e obtains an event code through0x17d20896 and jumps through the complete13-entry table0x17d0df14. Event6 targets0x17d235b4 and calls DCVS initialization0xd82e7270 at0x17d235b8. DCVS init calls C1 initializer0x17d2067a at0xd82e731a; that registers0x87 at0x17d206fc. Event8 targets0x17d235d8 and calls statistics initialization0xd82e7198 at0x17d235dc; that registers0x84 at0xd82e71aa. The other event targets are retained in graph-analysis.json and init-excerpts.txt; no additional registration call emerged from them.

The registration helper0xd82e75d4 searches for an empty handler slot, then stores protocol/handler at0xd82e7606/0xd82e7608. Every statically resolved call to this helper in all29043 decoded executable instructions is one of these four:

| Call encoding | Call VA | Protocol | Handler |
|---|---|---|---|
| AUIPC/JALR | 0x17d206fc | 0x87 | 0x17d201a8 |
| JAL | 0xd82e588a | 0x86 | 0xd82e5736 |
| JAL | 0xd82e687e | 0x80 | 0xd82e60fe |
| compressed JAL | 0xd82e71aa | 0x84 | 0xd82e714a |

The deeper audit also tracks straight-line register constants, loads and indirect transfers rather than looking only for adjacent AUIPC/JALR. No additional resolved indirect call to the helper was found; no raw literal pointer to0xd82e75d4 or0x17d0d528 appears anywhere in the image. No decoded straight-line materialization of value0x83 was found. Recognized materializations/accesses of the vendor table occur in its lookup dispatcher and registration helper; the only recognized writes are the helper's two stores. Advertisement-array address0x17d28658 is used by its packing/accessor function, not by an identified module-registration path.

These scans are not a formal whole-program pointer proof. Unknown register-only computation, pointers introduced by external runtime state, broad memory copies or additional loaded firmware can remain outside the analysis. In particular, the audit does not claim to have measured the live vendor table.

## Generic positive responses can mislead

Vendor dispatcher0x17d06808 handles commands0,1,2 before looking up a module handler. This happens even for an unregistered vendor ID:

| Command | Response excluding SCMI channel header |
|---|---|
| 0 protocol version | status0; version0x00010000 |
| 1 protocol attributes | status0; attributes0x00000101 |
| 2 message attributes | status0; attributes0 |

For command>=3, the dispatcher must find a matching protocol and non-null handler in0x17d0d528. If it cannot, it writes status-1 (SCMI not supported), returns success to the transport wrapper and leaves channel length8 (header4 + status4). Therefore success of discovery or message attributes would not demonstrate working CPU-control commands for0x83.

Offline namespace reproduction executes the actual SCMI initializer and registration helper, populating the four statically identified vendor registrations. It then executes the actual vendor dispatcher for all256 command IDs at protocol0x83. Commands0/1/2 produce the generic responses above. All253 commands3..255 return status-1 with channel length8. This is a model using the identified registrations, not full-boot emulation, and cannot establish the live table's contents. It confirms the consequence of the registration mismatch rather than inventing an ABI for0x83.

## Stock PLH is protocol0x81, not an alias for0x83

Stock modules plh_vendor.ko and plh_scmi.ko were extracted from the existing host vendor-ramdisk.bin. Their own data proves the ID:

- plh_vendor.ko object scmi_plh_vendor, section.rodata offset0x8, has protocol byte0x81.
- plh_scmi.ko object scmi_id_table, section.rodata offset0x8, has protocol byte0x81.
- Saved device inventory contains a protocol@81 device-tree child and no83 child or binding. It has active PMU86, C1DCVS87, MEMLAT80 and statistics84 clients. This historical saved inventory does not replace a firmware table audit.

These stock values match the immutable primary [PLH header](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/include/linux/scmi_plh.h), [protocol client](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/arm_scmi/plh_vendor.c) and [SCMI device driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/soc/qcom/plh_scmi.c).

Stock PLH code sends scroll commands16..20 and launch commands32..36: table initialization, start, stop, sampling interval and log level. The setter passes a u16 value encoded as one little-endian u32; initialization packs u16 table entries in a bounded payload of at most100bytes. Stock instructions retain these command literals, so this identification does not rely on source names alone. No0x83 translation/alias or independent CPU ceiling ABI was identified. Even protocol0x81 itself is absent from the firmware advertisement and the four resolved vendor registrations; installed client modules alone do not establish firmware support.

## Artifacts and reproduction

Use Capstone5.0.6 and Unicorn2.1.4 from the existing environment:

```text
python3 inspect_graph.py /path/to/cpucp_b.bin /path/to/output
python3 emulate_namespace.py /path/to/cpucp_b.bin
python3 inspect_plh.py /path/to/extracted/plh-modules /path/to/output
```

Bounded deliverables: report.md; inspect_graph.py; graph-analysis.json; init-excerpts.txt; emulate_namespace.py; namespace-emulation.json; inspect_plh.py; stock-plh-analysis.json; stock-plh-excerpts.txt. The extracted stock modules, full downloaded PLH header and firmware image should remain outside committed artifacts.
