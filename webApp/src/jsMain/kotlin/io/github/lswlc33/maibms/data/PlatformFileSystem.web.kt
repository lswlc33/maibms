package io.github.lswlc33.maibms.data

import okio.FileSystem

/**
 * 网页端**没有** `FileSystem.SYSTEM`（okio 的 js/wasm 目标只提供抽象的 FileSystem，
 * 没有落地实现——见 okio wasmMain/jsMain 的 FileSystem.kt）。
 *
 * 于是这里给一个"哪都不支持"的空实现：所有操作直接抛 —— [LogFileStore] 的每个调用点
 * 都已经 runCatching 兜住（见其注释"未注入目录或 IO 失败时全部静默降级为 no-op"），
 * 所以结果是**日志不落盘**，内存环形缓冲照常工作，开发者页看日志、复制日志都正常。
 *
 * 网页端本来也无处可落：浏览器不能写用户磁盘（除非 File System Access API + 用户逐个授权），
 * 而"日志带出浏览器"走的是界面上的复制/下载，与文件系统无关。
 */
internal actual val platformFileSystem: FileSystem = UnsupportedFileSystem

private object UnsupportedFileSystem : FileSystem() {
    private fun no(path: Any?): Nothing =
        throw UnsupportedOperationException("网页端没有可用的文件系统（日志不落盘，仅内存保留）")

    override fun canonicalize(path: okio.Path): okio.Path = no(path)
    override fun metadataOrNull(path: okio.Path): okio.FileMetadata? = null
    override fun list(dir: okio.Path): List<okio.Path> = emptyList()
    override fun listOrNull(dir: okio.Path): List<okio.Path>? = null
    override fun openReadOnly(file: okio.Path): okio.FileHandle = no(file)
    override fun openReadWrite(file: okio.Path, mustCreate: Boolean, mustExist: Boolean): okio.FileHandle = no(file)
    override fun source(file: okio.Path): okio.Source = no(file)
    override fun sink(file: okio.Path, mustCreate: Boolean): okio.Sink = no(file)
    override fun appendingSink(file: okio.Path, mustExist: Boolean): okio.Sink = no(file)
    override fun createDirectory(dir: okio.Path, mustCreate: Boolean) = no(dir)
    override fun atomicMove(source: okio.Path, target: okio.Path) = no(source)
    override fun delete(path: okio.Path, mustExist: Boolean) = no(path)
    override fun createSymlink(source: okio.Path, target: okio.Path) = no(source)
}