package tv.xbox.music.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** 只响应点击、不参与焦点（虚拟遥控器的按键用：按了不能把焦点抢走） */
fun Modifier.clickableNoFocus(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/** 配色：照 MuYin 的 TvColors（文档 4.3），只有这几个够首页用 */
object C {
    val Bg = Color(0xFF05060A)
    val Surface = Color(0xFF0E1118)
    val Elevated = Color(0xFF151924)
    val Text = Color(0xFFF5F7FB)
    val Text2 = Color(0xFFA3ABBA)
    val Text3 = Color(0xFF6C7586)
    val Accent = Color(0xFF6E8BFF)
}

/**
 * MuYin 的 TvSurface 的简化版：焦点只做「缩放 + 描边 + 外发光」，不动底色
 * （文档 4.2 的三条硬性规则：不用 elevation、不用焦点态底色、滚动容器留 padding）。
 */
@Composable
fun TvSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    base: Color = Color.Transparent,
    focusedScale: Float = 1.05f,
    focusKey: () -> Unit = {},
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "s")
    Box(
        modifier
            .scale(scale)
            .background(base, shape)
            .then(
                if (focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.92f), shape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
                if (it.isFocused) focusKey()
            }
            // clickable 必须在 focusable 之前：反过来的话 TV 上按键/触摸会先被焦点层吞掉，
            // onClick 永远不触发（首页「播放全部」点了没反应就是这个）
            .clickable { onClick() }
            .focusable()
            // 遥控器的 OK 键必须显式映射：部分 Compose 版本里 clickable 只认真实点击，
            // DPAD_CENTER 到不了 onClick（搜索结果行按 OK 没反应就是这么来的）
            .onKeyEvent { e ->
                if ((e.key == Key.DirectionCenter || e.key == Key.Enter)
                    && e.type == KeyEventType.KeyUp
                ) {
                    onClick()
                    true
                } else {
                    e.key == Key.DirectionCenter || e.key == Key.Enter
                }
            },
        content = content,
    )
}
