package io.github.lswlc33.maibms.data

import okio.FileSystem

/** iOS：okio 的 Apple 系统文件系统（底层是 NSFileManager，公共代码因此不用写互操作） */
internal actual val platformFileSystem: FileSystem get() = FileSystem.SYSTEM
