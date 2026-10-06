# Deeper investigation of independent clock controls

2026-10-06. The objective is a real independent CPU ceiling or SRB control, without changing the other clusters' requested caps. This pass used host analysis only: no device access, firmware invocation, setting change, module load, reboot or flashing.

**The strongest remaining candidate is bit 0 at EPSS offset `0xbc`.** Stock CPUCP deliberately clears it for L3/Silver and sets it for Gold/Gold_Plus during guarded domain initialization. The exact register bases match the Linux debug driver's `EPSS_DEBUG_SRB`. This gives us a real firmware owner and writer, rather than an address inferred from a patent. Its precise effect and a safe runtime transaction remain unproved.

| Route | New result | Consequence |
| --- | --- | --- |
| Vendor `0x83` | Advertisement is configured independently of registration. Startup/event tracing finds four registrations, none for `0x83`; the corresponding host model rejects commands 3–255. | Stronger evidence of a fixed advertisement without an identified implementation. No hidden cap ABI found. |
| PLH | Actual stock modules use protocol `0x81`. | PLH is not a resolved alias for `0x83`. |
| EPSS `+0xbc` bit 0 | Exact stock domain objects and initialization stores resolved. | A concrete SRB-related configuration candidate; not yet a verified enable/disable bit. |
| EPSS `+0x88` | C1 uses a file-initialized low-six-bit policy table; no sampled hardware save established. | No established independent cap or guaranteed restoration. |
| Common `17d98000/04/08` | XBL and TZ identify CPUCP boot code/configuration; CPUCP itself changes these during startup. | Not a justified isolated SRB switch. |
| Generic secure register access | Exact-address allowlist excludes the examined CPU EPSS registers. | Root/escalation cannot turn this service into an arbitrary register-access route. |
| Kernel/Linux tables | Existing requests, software OPP tables and cooling pressure do not establish a hardware ceiling. | A custom kernel must control the actual hardware mechanism; merely filtering software frequencies is insufficient. |

## Evidence

- [Vendor registration graph, stock PLH and namespace simulation](vendor83/report.md).
- [EPSS ownership, exact initialization stores and common-block boot context](epss/report.md).
- [Secure-access allowlist, kernel constraints and other hardware-limit routes](access/report.md).

The vendor model is not full-boot emulation and does not exclude externally supplied code or table mutation. The EPSS reference scans are not formal whole-program analysis. The secure-access result applies to the inspected stock image and the exact candidate addresses. These qualifications prevent treating stronger negative evidence as proof that every possible control is absent.

## Next proof required for the candidate

1. Establish the meaning of `EPSS+0xbc[0]`, including whether clearing it affects only shared-rail boosting. The name of the debug register and its boot initialization do not establish that alone.
2. Establish a compatible kernel access path. `/dev/mem` is disabled, the stock debug frontend only reads the register, and the SCM IO allowlist rejects it. Direct kernel mapping/writing permissions remain untested. Module versions, CFI and signature protection also need compatibility checks; Android root is not enough to assume loading will work.
3. Establish runtime ownership and sequencing: whether a domain must be quiesced, which firmware path can rewrite the field during resume/reactivation, and how to preserve all other bits and restore the original configuration. Merely saving a word does not establish safe concurrency.
4. Only then perform a bounded A/B test using the same independent hardware-state and cycle measurements that detected the original overshoot, with unchanged requested cluster caps. Verify restoration and thermal behavior too.

No raw register-write experiment or ClusterTune workaround was added. The boot code is now a concrete place to investigate; the candidate's effect is still a hypothesis.
