package io.github.lswlc33.maibms.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 内置图标集：24dp 网格、2dp 圆头描边，全站统一。
 * 不引 material-icons（多端体积），也不再拿 › ▾ ＋ ✓ ● ⚡ 等字形当图标。
 */
object BmsIcons {

    private fun imageVector(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(build).build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2 * r, dy1 = 0f)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2 * r, dy1 = 0f)
        close()
    }

    /** 仪表盘：半圆表盘 + 指针 + 轴心 */
    val Gauge: ImageVector by lazy {
        imageVector("BmsGauge") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(3.5f, 16.5f)
                arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.5f, y1 = 16.5f)
                moveTo(12f, 16.5f)
                lineTo(15.7f, 10.2f)
            }
            path(fill = SolidColor(Color.Black)) { circle(12f, 16.5f, 1.5f) }
        }
    }

    /** 配置：三组推子 */
    val Tune: ImageVector by lazy {
        val rows = listOf(Triple(6.5f, 9.5f, 0), Triple(12f, 15.5f, 1), Triple(17.5f, 8f, 2))
        imageVector("BmsTune") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                rows.forEach { (y, kx, _) ->
                    moveTo(3.5f, y); lineTo(kx - 3.5f, y)
                    moveTo(kx + 3.5f, y); lineTo(20.5f, y)
                }
            }
            path(fill = SolidColor(Color.Black)) {
                rows.forEach { (y, kx, _) -> circle(kx, y, 2.4f) }
            }
        }
    }

    /** 设置：齿轮（8 齿轮廓 + 中孔，偶奇填充挖空） */
    val Gear: ImageVector by lazy {
        val teeth = 8
        val step = 2 * PI.toFloat() / teeth
        val tipHalf = 0.2443f    // 齿顶半角 14°
        val rootHalf = 0.1484f   // 齿根半角 8.5°
        val rTip = 8.2f
        val rRoot = 5.7f
        imageVector("BmsGear") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                fun x(a: Float, r: Float) = 12f + r * cos(a)
                fun y(a: Float, r: Float) = 12f + r * sin(a)
                for (i in 0 until teeth) {
                    val a = i * step
                    val a2 = a + step / 2
                    val pts = listOf(
                        x(a - tipHalf, rTip) to y(a - tipHalf, rTip),
                        x(a + tipHalf, rTip) to y(a + tipHalf, rTip),
                        x(a2 - rootHalf, rRoot) to y(a2 - rootHalf, rRoot),
                        x(a2 + rootHalf, rRoot) to y(a2 + rootHalf, rRoot),
                    )
                    pts.forEachIndexed { j, (px, py) ->
                        if (i == 0 && j == 0) moveTo(px, py) else lineTo(px, py)
                    }
                }
                close()
                circle(12f, 12f, 2.7f)
            }
        }
    }
}
