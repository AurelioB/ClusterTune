package com.aure.clustertune.root

import com.aure.clustertune.model.CpuPolicyInfo

/** Builds the small, fail-closed sysfs transaction used by every privileged executor. */
class PerformanceCommandBuilder {

    companion object {
        const val COMPLETION_MARKER = "clustertune-script-complete"
    }

    fun buildApplyScript(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
    ): String {
        // Lower every policy floor before touching a ceiling. This is important when a previous
        // app version left scaling_min_freq above the newly requested maximum.
        val lines = mutableListOf("set -e")
        policies.forEach { policy ->
            val value = selectedValues[policy.id] ?: return@forEach
            require(policy.hardwareMinFreq <= value) {
                "Requested maximum $value is below the hardware floor ${policy.hardwareMinFreq}"
            }
            // Leave the repaired floor writable so vendor services can manage it again. This
            // also repairs installations from versions that permanently locked it at 0444.
            lines += writeMinimumNodeCommand(
                path = policy.scalingMinPath,
                candidates = policy.minimumCandidates,
                maximum = value,
            )
        }
        policies.forEach { policy ->
            val value = selectedValues[policy.id] ?: return@forEach
            // Some vendor kernels report a stock ceiling above the frequencies that can be
            // written to scaling_max_freq (the hidden bin is managed by the firmware). Keep the
            // observed stock value as the logical target, but write the highest selectable bin.
            // This lets the firmware restore the hidden ceiling without making the transaction
            // fail on an otherwise valid Stock reset.
            val writableValue = if (
                value == policy.observedMaxFreq &&
                value > policy.selectableMaxFreq
            ) {
                policy.selectableMaxFreq
            } else {
                value
            }
            // Isolate each policy. With a blanket `set -e`, ONE failing policy
            // aborted the whole script — later policies were never written and,
            // on the no-root path (no stdout), nothing reported which one failed.
            // Wrapping in a subshell lets the remaining policies still apply; the
            // read-back verification is what decides overall success, and it now
            // names the specific policy that did not take.
            lines += "( " +
                writeNodeCommand(policy.scalingMaxPath, writableValue, preserveOwnerWrite = isReset) +
                " ) || printf '%s\\n' 'ct-policy-failed:${policy.id}' >&2"
        }
        lines += "printf '%s\\n' '$COMPLETION_MARKER'"

        return buildString {
            appendLine("#!/system/bin/sh")
            lines.forEach(::appendLine)
        }
    }

    fun buildMinimumRepairScript(policies: List<CpuPolicyInfo>): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("set -e")
        policies.forEach { policy ->
            require(policy.hardwareMinFreq > 0) { "No safe minimum for policy ${policy.id}" }
            // A repair must not raise a policy floor above the ceiling currently exposed by
            // the kernel. If every safe candidate is rejected, keep the node writable and
            // continue: some vendor kernels reject all scaling_min_freq writes even though
            // chmod is required to release a stale read-only node.
            appendLine(
                writeMinimumNodeCommand(
                    path = policy.scalingMinPath,
                    candidates = policy.minimumCandidates,
                    maximum = policy.currentMaxFreq,
                ),
            )
        }
        appendLine("printf '%s\\n' '$COMPLETION_MARKER'")
    }

    /** Writes one maximum node, restoring its original mode if the write fails. */
    private fun writeNodeCommand(path: String, value: Int, preserveOwnerWrite: Boolean): String {
        val quotedPath = shellQuote(path)
        return "mode=\$(stat -c %a $quotedPath) || exit 1; " +
            "chmod u+w $quotedPath || exit 1; " +
            "if ! echo '$value' > $quotedPath; then " +
            "chmod \"\$mode\" $quotedPath; " +
            "printf '%s\\n' 'Failed to write $value to $path' >&2; exit 1; fi; " +
            if (preserveOwnerWrite) {
                "chmod u+w $quotedPath"
            } else {
                "chmod a-w $quotedPath"
            }
    }

    private fun writeMinimumNodeCommand(path: String, candidates: List<Int>, maximum: Int?): String {
        val usableCandidates = candidates
            .filter { it > 0 && (maximum == null || it <= maximum) }
            .distinct()
            .sorted()
        require(usableCandidates.isNotEmpty()) { "No safe minimum candidate for $path" }
        val quotedPath = shellQuote(path)
        val values = usableCandidates.joinToString(" ")
        // Keep this as one shell command: the no-stdout PServer executor dispatches script lines
        // independently and cannot carry shell variables between lines. Candidate writes are
        // best-effort because several vendor kernels reject this node with EINVAL; the writable
        // mode is the important repair and must remain in place even when every write is rejected.
        return "stat -c %a $quotedPath >/dev/null || exit 1; " +
            "chmod u+w $quotedPath || exit 1; " +
            "for candidate in $values; do " +
            "if echo \"\$candidate\" > $quotedPath 2>/dev/null; then break; fi; " +
            "done; " +
            "chmod u+w $quotedPath"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
