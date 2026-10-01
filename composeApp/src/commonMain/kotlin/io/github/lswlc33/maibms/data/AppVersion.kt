package io.github.lswlc33.maibms.data

/** 应用自身版本（「关于」与「检查更新」用），平台各自注入 */
expect object AppVersion {
    val name: String
    val code: Int
}
