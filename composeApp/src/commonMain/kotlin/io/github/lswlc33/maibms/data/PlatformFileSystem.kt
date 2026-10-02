package io.github.lswlc33.maibms.data

import okio.FileSystem

/**
 * 平台默认文件系统。okio 的 `FileSystem.SYSTEM` 只在各平台源集里声明
 * （common 侧无法精化 expect companion，见 okio nativeMain 的注释），
 * 因此公共代码用这个 expect 原语拿到它——JVM 与 Apple 目标都是 okio 的系统实现。
 */
internal expect val platformFileSystem: FileSystem
