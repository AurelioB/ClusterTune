# Runtime EPSS write experiment: unsafe direct-access route

2026-10-06. **The compatible helper can read the candidate register, but direct runtime writing is unsafe on the tested Thor firmware.** An isolated experiment attempted to write the prime-domain SRB-labelled register's existing value back unchanged (`1 → 1`). It logged the start of the write, never logged successful readback, and Android stopped responding over ADB. The device later returned with a different boot ID. No bit-clearing experiment was attempted, and no SRB suppression or independent-cap fix was demonstrated.

This is a negative result for this particular direct-access transaction, not proof that every firmware-mediated runtime solution is impossible. Without a captured fault stack, we cannot distinguish a blocked MMIO transaction, access protection, invalid runtime sequencing, or another failure within the write/readback path. Whether either reboot involved manual intervention was not established when this report was saved.

## Evidence and isolation

| Stage | Result |
| --- | --- |
| Prior restricted reader | Three load/read/unload cycles passed through PServer, values `0 / 1 / 1`, no reboot. |
| Initial combined measurement/write run | Reboot observed; exact stage unknown because output files were lost and pstore was empty. This run cannot independently establish a write failure. |
| Counter-only control after that reboot | Two seconds, 1,959 CPU7 cycle-counter windows, exit 0, no reboot. |
| Single stock hardware clock read | Passed, same boot ID before/after. |
| Slower measurement control | Two seconds, 1,924 cycle-counter windows and four hardware clock reads at approximately 500 ms spacing, exit 0, no reboot. |
| Isolated same-value write | No load or clock measurement running. Durable marker reached `before_same_value_insmod`. Live kernel log reached `stage=write_begin`, original `1`, target `1`. No `write_confirmed`, restoration or unload record. |
| Recovery | New boot ID, register values `0 / 1 / 1`, helper absent, pstore empty. |
| Final restoration | Original Large Underclock applied through ClusterTune; all CPU minimums, maximums, governors and GPU ceiling verified. Temporary device files removed. |

[Structured result](result.json), [live kernel excerpt](live-kernel-excerpt.txt), [durable stage](write-isolated-stage.txt), [loader output](write-isolated-output.txt), and [post-reboot health](post-reboot-health.txt) retain the evidence. The live stream was captured on the host before loading, so it survives the reboot. The last helper message was at monotonic `555457995359` ns; other kernel activity continued afterward, including RT throttling and Wi-Fi timeouts. This was not an immediately captured kernel panic. There is no saved exception/backtrace establishing the exact failing instruction.

The isolated run started on boot `23997567-8094-4969-9e00-8a4680fe9816` and recovered on `4e04bda7-a156-4cd0-bd13-7437f67c31f3`. The first combined run started on `6cec412a-1148-47ce-8aa8-fb4f2da97729`. Several recently pushed files survived that first reboot as zero-filled files, including the launcher DEX and helper binary. A subsequent launcher failure was therefore a userspace `ClassNotFoundException`, not a kernel-write result. Those inputs were replaced, synced and hash-verified before the isolated test. The original six-second fixture also first encountered an unsupported seek on the hardware clock node and exited before loading; that fixture error is not evidence against register writes either.

## Attempted helper

The archived [source](ct_srb_pulse.c) validates exactly the three CPU EPSS device-tree resources and maps only four bytes at prime base `0x17d93000 + 0xbc`. It refuses a starting word other than `1`. The only executed build used **`CT_CLEAR_BIT=0`, `CT_HOLD_MS=50`**: ordinary `writel(1, reg)`, readback, then a bounded sleep and synchronous cleanup. It has no setting endpoint, arbitrary-address accessor, recurring callback or persistent installation. No clear-bit binary was built or loaded.

Its [16 generated import CRCs](generated-abi-comparison.json) match the actual stock kernel, including the additional MMIO write logging, `ktime_get` and `msleep` imports. [Binary verification](binary-verification.json) records SHA-256 `cc08ea184abb3d7d1566a312b9c715fd09c23b1f0c8e1f10fc612a4aa210bbd6`, size 248,944 bytes and the matching 960-byte module structure. Compilation used the same genuine stock headers, configuration and Clang r450784e workflow as the [reader](../reader/report.md); [ABI derivation units](abi/) extend that workflow. There were no patched CRCs, forced loads or disabled compatibility checks.

The archived [script](run_rewrite_isolated.sh) verifies the binary hash, syncs a stage marker before loading and uses a temporary expiring wake lock. Loading went through `PServerBinder`, executing as `u:r:pservice:s0`; it did not invoke Magisk `su`. Root was used only for diagnostics, evidence collection and cleanup. SELinux remained permissive. There was no flash, firmware update or intentional reboot command.

**Do not rerun this write experiment or enable its clear-bit variant.** These files record a failed experiment, not a supported tool or a safe reproduction recipe. A cleanup trap and restoration inside module initialization cannot recover a write that never returns. The intended 50 ms bound was consequently not an effective bound on the failing hardware access. A userspace timeout likewise cannot cancel a stalled kernel MMIO operation.

## Final state and consequence for ClusterTune

[Verified final state](restored-state.txt): minimums **307200 / 499200 / 595200 kHz**, maximums **1459200 / 1785600 / 1843200 kHz**, `walt` throughout, minimum nodes `0440`, GPU maximum **680 MHz**, SRB-labelled values **0 / 1 / 1**, helper absent. Stock was applied before Large through ClusterTune's existing privileged service. [Cleanup](cleanup.txt) confirms the temporary device directory was removed and the recovery boot ID remained unchanged. The screens were returned to sleep afterward.

No ClusterTune implementation or bundled profile was changed. Read access through a compatible kernel helper does not imply runtime write access, even with root and permissive SELinux. This route fails before an experiment could test the candidate bit's meaning or its effect on overshoot. Factory enforcing-policy eligibility remains a separate, unresolved limitation from the earlier reader investigation.

Further progress needs a documented firmware-mediated operation or evidence of the required hardware access/sequencing, not repeated writes to this address. A firmware's startup store does not establish that Linux may make the same store while the domain is active. Other runtime routes remain possible in principle, but no usable one has been demonstrated.
