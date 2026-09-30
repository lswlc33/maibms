package io.github.lswlc33.maibms.protocol

/**
 * 运行权限语义（docs/09 §9.1）：0 只能读实时、≥1 可读参数与身份区、≥3 可写参数与执行控制命令。
 *
 * 这里是全应用唯一的「能不能写」判据——界面拦截与协议层判断都走它，
 * 免得各处各写一遍 `level >= 3` 然后某处漏掉。
 */
object Perm {
    /** 写参数 / 控制命令所需的最低运行权限（真机实测：二级下写与控制均被拒） */
    const val WRITE_MIN_LEVEL = 3

    /** 读参数区所需的最低运行权限（真机实测：一级读回整块 0xFFFF） */
    const val READ_PARAM_MIN_LEVEL = 2

    fun canWrite(level: Int): Boolean = level >= WRITE_MIN_LEVEL
}

/** 配置页写权限状态（顶栏指示与页面拦截共用） */
enum class WriteAccess(val label: String) {
    /** ≥3 级且已连接：可编辑 */
    EDIT("可编辑"),

    /** 1~2 级：参数可读、写被设备拒绝 */
    READ_ONLY("只读"),

    /** 0 级或未连接：连参数区都读不到 */
    DENIED("权限不足");

    companion object {
        /**
         * 未连接时一律按「只读」呈现：页面本来就在展示缓存值，链路问题由各页的未连接横幅说明；
         * 这时候写「权限不足」会与右上角已记住的等级徽标自相矛盾（看着有 3 级却说权限不足）。
         */
        fun of(level: Int, connected: Boolean): WriteAccess = when {
            !connected -> READ_ONLY
            Perm.canWrite(level) -> EDIT
            level > 0 -> READ_ONLY
            else -> DENIED
        }
    }
}

/**
 * 密码槽编码（docs/09 §9.1~9.2）：
 * - 一~四级槽 8 字节、五级与管理员槽 12 字节，ASCII 右补 `0x00`；
 * - 管理员槽（9 级）输入的是**点分十进制**（如 `0.0.0.…`，≥12 段、每段 ≤255），逐段转字节。
 */
object PasswordCodec {
    fun encode(level: Int, password: String): ByteArray = when (level) {
        9 -> admin(password)
        else -> ascii(password, ParamTable.slotLen(level))
    }

    /** ASCII 槽：超出槽长截断，不足补 0x00 */
    fun ascii(password: String, slotLen: Int): ByteArray =
        password.toByteArray(Charsets.US_ASCII).copyOf(slotLen)

    /** 管理员槽：点分十进制 12 段 → 12 字节；不含 `.` 的输入按 ASCII 处理（兼容直填） */
    fun admin(password: String): ByteArray {
        if ('.' !in password) return ascii(password, ParamTable.slotLen(9))
        val bytes = password.split('.')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { it.coerceIn(0, 255).toByte() }
        return bytes.toByteArray().copyOf(ParamTable.slotLen(9))
    }

    /** 输入合法性校验；返回错误文案，null = 可以提交（与写入前校验共用一套话术） */
    fun validate(level: Int, password: String): String? {
        if (password.isEmpty()) return "请输入密码"
        return when (level) {
            9 -> {
                val segs = password.split('.')
                when {
                    segs.size < 12 -> "管理员密码需 12 段点分十进制（当前 ${segs.size} 段）"
                    segs.any { it.trim().toIntOrNull() == null } -> "每段必须是 0~255 的数字"
                    segs.any { (it.trim().toIntOrNull() ?: 0) > 255 } -> "每段不能大于 255"
                    else -> null
                }
            }
            else -> {
                val slot = ParamTable.slotLen(level)
                if (password.toByteArray(Charsets.US_ASCII).size > slot)
                    "$level 级密码槽为 $slot 字节，最多 $slot 个字符" else null
            }
        }
    }
}
