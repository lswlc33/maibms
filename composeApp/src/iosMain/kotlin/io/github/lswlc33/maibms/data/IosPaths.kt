package io.github.lswlc33.maibms.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** iOS 沙盒目录查询（NSSearchPathForDirectoriesInDomains 的包装，仅此一处需要 cinterop 注解） */

/** 文档目录：日志导出落这里（「文件」App 可见，可通过访达取走） */
@OptIn(ExperimentalForeignApi::class)
internal fun iosDocumentsDir(): String? =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String

/** 应用支持目录：日志文件落这里的 maibms-logs（不需要用户直接访问） */
@OptIn(ExperimentalForeignApi::class)
internal fun iosAppSupportDir(): String? =
    NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
