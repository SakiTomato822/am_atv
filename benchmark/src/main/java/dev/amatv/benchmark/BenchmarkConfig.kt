package dev.amatv.benchmark

import androidx.test.platform.app.InstrumentationRegistry

/** Same iteration count for a batched before/after run; defaults to the normal three repetitions. */
internal fun configuredBenchmarkIterations(): Int {
    val raw = InstrumentationRegistry.getArguments().getString("benchmarkIterations") ?: return 3
    return raw.toIntOrNull()?.takeIf { it in 1..20 }
        ?: throw IllegalArgumentException("benchmarkIterations must be an integer between 1 and 20")
}
