package io.github.lswlc33.maibms.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用更新检查：读取 GitHub Releases 的最新版本，与 [AppVersion] 比较。
 *
 * 更新渠道（[UpdateChannel]）：
 * - 稳定版 = GitHub「最新正式版」（`/releases/latest`，官方接口天然排除预发布）；
 * - 预览版 = 最近一次发布的 release（含 Prerelease，如 `0.1.1-beta.9`），适合想尝鲜的用户。
 *
 * 链路与镜像回退（国内直连 github.com 经常超时，逐个源尝试直到拿到结果）：
 * 1. ghproxy（静态资源加速，透传 api.github.com）
 * 2. gh-proxy（同类的另一个公共实例）
 * 3. GitHub API 直连（兜底：能直连的用户最快最准）
 *
 * 只读公开 API，不需要任何令牌；超时短（5s/源），全失败返回失败原因而不是卡死。
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/lswlc33/maibms"
    private const val API_LATEST = "https://api.github.com/repos/lswlc33/maibms/releases/latest"
    private const val API_LIST = "https://api.github.com/repos/lswlc33/maibms/releases?per_page=10"

    /** 更新渠道：稳定版=正式 Release；预览版=含 Prerelease 的最近一次发布 */
    enum class UpdateChannel(val key: String, val label: String) {
        STABLE("stable", "稳定版"),
        PREVIEW("preview", "预览版");

        companion object {
            fun fromKey(key: String?): UpdateChannel =
                entries.firstOrNull { it.key == key } ?: STABLE
        }
    }

    /** 渠道 → 请求的 API 地址（独立出来便于单测锁定） */
    internal fun apiUrlFor(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> API_LATEST
        UpdateChannel.PREVIEW -> API_LIST
    }

    /** 顺序即优先级：前面是直连 github 困难地区的加速镜像，最后一个兜底直连 */
    private val sources: List<(String) -> String> = listOf(
        { "https://ghproxy.net/$it" },
        { "https://gh-proxy.com/$it" },
        { it },
    )

    @Serializable
    data class ReleaseInfo(
        /** GitHub API 是 snake_case：tag_name / html_url，必须用 @SerialName 显式映射 */
        @kotlinx.serialization.SerialName("tag_name") val tagName: String = "",
        @kotlinx.serialization.SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
        val prerelease: Boolean = false,
    )

    /** 检查结果：upToDate=已是最新；update=有新版（带版本号与 release 页）；failed=全部源失败 */
    sealed class Result {
        data object UpToDate : Result()
        data class Update(val latest: String, val info: ReleaseInfo) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(channel: UpdateChannel): Result = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val url = apiUrlFor(channel)
        var latestInfo: ReleaseInfo? = null
        for (mirror in sources) {
            if (latestInfo != null) break   // 拿到结果即停（continue 在 inline lambda 里是 2.2 语法）
            try {
                latestInfo = fetchRelease(mirror(url), channel)
                if (latestInfo == null) errors.add("${mirror(url)}: 响应为空")
            } catch (e: Exception) {
                errors.add("${mirror(url)}: ${e.message ?: e::class.simpleName}")
            }
        }
        val info = latestInfo ?: return@withContext Result.Failed("所有源都失败了：" + errors.joinToString("；"))
        val latest = info.tagName.removePrefix("v").removePrefix("V")
        return@withContext if (isNewer(latest, AppVersion.name)) Result.Update(latest, info) else Result.UpToDate
    }

    /** GET 一次 release JSON（单对象或列表按渠道区分）；非 200 或 body 为空返回 null */
    private fun fetchRelease(url: String, channel: UpdateChannel): ReleaseInfo? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true   // 镜像多为 302 透传
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "maibms-app/${AppVersion.name}")
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            if (body.isBlank()) return null
            // 稳定版=单对象；预览版=release 数组取第一个（GitHub 按 created_at 倒序，即最近一次发布）
            return when (channel) {
                UpdateChannel.STABLE -> json.decodeFromString(ReleaseInfo.serializer(), body)
                UpdateChannel.PREVIEW -> json.decodeFromString(
                    ListSerializer(ReleaseInfo.serializer()), body).firstOrNull()
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 语义化版本比较：把 "0.2.1-beta.3" 拆成数字段逐段比，预发布（-beta.N）视为比同号正式版小。
     * 段数不同时短的补 0（0.2 == 0.2.0）。
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest); val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val ln = l.getOrNull(i) ?: Num(0, false)
            val cn = c.getOrNull(i) ?: Num(0, false)
            if (ln != cn) return ln > cn
        }
        return false
    }

    /** 版本的一段：数字值 + 是否预发布段。预发布段排在同值正式段之前（0.1.1-beta < 0.1.1） */
    private data class Num(val value: Int, val pre: Boolean) : Comparable<Num> {
        override fun compareTo(other: Num): Int = when {
            value != other.value -> value - other.value
            pre == other.pre -> 0
            pre -> -1   // beta < 正式
            else -> 1
        }
    }

    /**
     * "0.2.1-beta.3" → [Num(0), Num(2), Num(1,pre)]。
     * 预发布标记挂在**最后一段**：逐段比较先比数值，走完所有段再落到末段的 pre 上，
     * 这样 0.1.2-beta > 0.1.1（数值段先分出胜负），而 0.1.1-beta < 0.1.1（数值全平，pre 生效）。
     */
    private fun parse(version: String): List<Num> {
        val core = version.substringBefore('-').substringBefore('+')
        val pre = version.contains('-')
        val segs = core.split('.')
        return segs.mapIndexed { i, seg -> Num(seg.trim().toIntOrNull() ?: 0, pre && i == segs.lastIndex) }
    }
}
