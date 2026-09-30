package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable

/**
 * 日志导出：把文本写成本地文件并返回可读的存放位置（或分享后的提示）。
 * Android 写入应用外部可见目录（Download/maibms/），桌面写入用户目录。
 */
@Composable
expect fun rememberLogExporter(): (String) -> String?
