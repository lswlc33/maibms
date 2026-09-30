package io.github.lswlc33.maibms.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberLogExporter(): (String) -> String? {
    val ctx = LocalContext.current
    return remember(ctx) {
        { text ->
            runCatching {
                val name = "maibms-log-${System.currentTimeMillis()}.txt"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+：MediaStore 写 Download，无需任何存储权限
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/maibms")
                    }
                    val uri = ctx.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, name)
                        putExtra(Intent.EXTRA_TEXT, "日志已保存到 Download/maibms/$name")
                    }, "日志已保存到 Download/maibms").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    "Download/maibms/$name"
                } else {
                    // Android 8/9：直接写公共 Download 目录（旧版上这是应用可写的）
                    val dir = File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "maibms").apply { mkdirs() }
                    val f = File(dir, name)
                    f.writeText(text)
                    "Download/maibms/$name"
                }
            }.getOrNull()
        }
    }
}
