package com.aure.clustertune.root

import android.content.Context
import com.aure.clustertune.data.PrivilegedSysfsReader

class PServerSysfsReader(
    context: Context,
    private val executionResolver: PrivilegedExecutionResolver = PrivilegedExecutionResolver.default(context),
) : PrivilegedSysfsReader {

    override fun readText(path: String): String? {
        return executionResolver.readText(path)
    }
}
