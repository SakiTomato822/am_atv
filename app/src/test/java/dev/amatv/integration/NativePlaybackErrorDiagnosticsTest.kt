package dev.amatv.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackErrorDiagnosticsTest {
    @Test fun logsNativeErrorShapeWithoutVendorMessagesOrCredentialText() {
        val error = FakeNativePlaybackException(
            type = 3,
            errorCode = Int.MIN_VALUE,
            componentErrorCode = Int.MIN_VALUE,
            cause = java.io.IOException(
                "HTTP/1.1 403 Forbidden for https://example.invalid/path?music-user-token=do-not-log",
            ),
        )

        val diagnostic = nativePlaybackErrorDiagnostics(error)

        assertTrue(diagnostic.contains("type=TYPE_PLAYBACK_QUEUE"))
        assertTrue(diagnostic.contains("code=ERROR_UNKNOWN"))
        assertTrue(diagnostic.contains("component=ERROR_UNKNOWN"))
        assertTrue(diagnostic.contains("java.io.IOException"))
        assertTrue(diagnostic.contains("messageHints=[http_status_403]"))
        assertFalse(diagnostic.contains("do-not-log"))
        assertFalse(diagnostic.contains("example.invalid"))
    }

    private class FakeNativePlaybackException(
        private val type: Int,
        private val errorCode: Int,
        private val componentErrorCode: Int,
        cause: Throwable,
    ) : Exception("https://example.invalid/request?token=do-not-log", cause) {
        @Suppress("unused")
        fun getType(): Int = type

        @Suppress("unused")
        fun getErrorCode(): Int = errorCode

        @Suppress("unused")
        fun getComponentErrorCode(): Int = componentErrorCode
    }
}
