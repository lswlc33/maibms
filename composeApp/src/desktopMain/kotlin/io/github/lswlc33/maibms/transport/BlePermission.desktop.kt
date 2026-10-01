package io.github.lswlc33.maibms.transport

/** 桌面端没有蓝牙后端：放行（真正的拦截在 BmsRepository.start() 的 realTransport 判空） */
actual fun canAutoConnect(): Boolean = true
