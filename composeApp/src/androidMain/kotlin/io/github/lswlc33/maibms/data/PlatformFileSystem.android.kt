package io.github.lswlc33.maibms.data

import okio.FileSystem

/** Android：okio 的 JVM 系统文件系统（成员属性，无需额外 import） */
internal actual val platformFileSystem: FileSystem get() = FileSystem.SYSTEM
