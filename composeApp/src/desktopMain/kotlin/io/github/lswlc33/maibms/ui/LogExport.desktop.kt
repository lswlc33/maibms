package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

@Composable
actual fun rememberLogExporter(): (String) -> String? = remember {
    { text ->
        runCatching {
            val dir = File(System.getProperty("user.home"), "maibms-logs").apply { mkdirs() }
            val f = File(dir, "maibms-log-${System.currentTimeMillis()}.txt")
            f.writeText(text)
            f.absolutePath
        }.getOrNull()
    }
}
