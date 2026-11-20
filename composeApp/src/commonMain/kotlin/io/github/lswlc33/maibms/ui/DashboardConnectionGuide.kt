package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.transport.LinkState

/** 只替换尚无读数的空态；连接就立即退出，不等待首帧，也不覆盖快照或断线前的读数。 */
internal fun shouldShowConnectionGuide(link: LinkState, hasData: Boolean, previewing: Boolean): Boolean =
    link != LinkState.Connected && !hasData && !previewing

@Composable
internal fun DashboardConnectionGuide(
    reconnecting: Boolean,
    manuallyDisconnected: Boolean,
    deviceName: String,
    connectHint: String?,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶栏加号中心距右边 33dp；这里扣掉外侧 12dp，箭头落在同一条竖线上。
        Box(Modifier.fillMaxWidth().height(80.dp)) {
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 112.dp)) {
                Text("添加新设备", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primary)
                Text("点右上角「＋」", fontSize = 11.sp, color = secondaryText)
            }
            Canvas(Modifier.align(Alignment.TopEnd).width(112.dp).fillMaxHeight().clearAndSetSemantics {}) {
                val tipX = size.width - 21.dp.toPx()
                val tipY = 2.dp.toPx()
                val curve = Path().apply {
                    moveTo(4.dp.toPx(), size.height - 14.dp.toPx())
                    cubicTo(tipX, size.height - 14.dp.toPx(), tipX, 42.dp.toPx(), tipX, tipY)
                }
                val stroke = 2.dp.toPx()
                drawPath(curve, primary, style = Stroke(width = stroke, cap = StrokeCap.Round))
                drawLine(primary, Offset(tipX - 7.dp.toPx(), tipY + 9.dp.toPx()), Offset(tipX, tipY), stroke, StrokeCap.Round)
                drawLine(primary, Offset(tipX + 7.dp.toPx(), tipY + 9.dp.toPx()), Offset(tipX, tipY), stroke, StrokeCap.Round)
            }
        }
        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ConnectionSkeleton(Modifier.width(96.dp).height(10.dp))
                ConnectionSkeleton(Modifier.width(42.dp).height(10.dp))
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp).semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (reconnecting) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = primary, strokeWidth = 2.dp)
                } else {
                    ConnectionBatteryOutline(Modifier.size(width = 54.dp, height = 32.dp))
                }
                Text(
                    when {
                        reconnecting -> "正在重连设备…"
                        manuallyDisconnected -> "设备已断开"
                        else -> "等待连接设备"
                    },
                    fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                )
                deviceName.takeIf { it.isNotBlank() && it != "--" }?.let {
                    Text(it, fontSize = 12.sp, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    connectHint?.takeIf { it.isNotBlank() } ?: when {
                        reconnecting -> "请保持蓝牙开启，让设备靠近手机"
                        manuallyDisconnected -> "自动重连已暂停，可从右上角选择设备"
                        else -> "保持设备上电，从右上角「＋」扫描添加"
                    },
                    fontSize = 11.5.sp, color = secondaryText, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Text("连接成功后自动显示实时数据", fontSize = 10.5.sp, color = secondaryText, textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { ConnectionSkeleton(Modifier.weight(1f).height(8.dp)) }
            }
        }
        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(2) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConnectionSkeleton(Modifier.width(48.dp).height(8.dp))
                        ConnectionSkeleton(Modifier.fillMaxWidth(.72f).height(22.dp))
                        ConnectionSkeleton(Modifier.fillMaxWidth().height(8.dp))
                    }
                }
            }
        }
        SectionCard {
            ConnectionSkeleton(Modifier.width(64.dp).height(8.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { ConnectionSkeleton(Modifier.weight(1f).height(38.dp)) }
            }
        }
    }
}

@Composable
private fun ConnectionSkeleton(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp)).clearAndSetSemantics {})
}

@Composable
private fun ConnectionBatteryOutline(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.clearAndSetSemantics {}) {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color, topLeft = Offset(stroke, stroke),
            size = androidx.compose.ui.geometry.Size(size.width - 8.dp.toPx(), size.height - 2 * stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
            style = Stroke(stroke),
        )
        drawLine(color, Offset(size.width - stroke, size.height * .35f),
            Offset(size.width - stroke, size.height * .65f), stroke, StrokeCap.Round)
    }
}
