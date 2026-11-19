package tv.xbox.music.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 屏上虚拟遥控器：用来在没有实物遥控器的情况下验证焦点路由。
 *
 * <p>做法是往窗口的 decorView 派发真实 KeyEvent —— 走的是和遥控器完全同一条链路
 * （Activity → ComposeView → Compose 焦点系统），不是直接调焦点 API，
 * 所以它验证到的行为和真实按键一致。
 */
@Composable
fun VirtualRemote(dispatch: (Int) -> Unit, onHide: () -> Unit) {
    Column(
        Modifier.padding(16.dp).clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC0B0E15)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RemoteRow(listOf("MENU" to KeyEvent.KEYCODE_MENU, "BACK" to KeyEvent.KEYCODE_BACK), dispatch)
        Spacer(Modifier.height(6.dp))
        RemoteKey("↑", KeyEvent.KEYCODE_DPAD_UP, dispatch)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RemoteKey("←", KeyEvent.KEYCODE_DPAD_LEFT, dispatch)
            RemoteKey("OK", KeyEvent.KEYCODE_DPAD_CENTER, dispatch)
            RemoteKey("→", KeyEvent.KEYCODE_DPAD_RIGHT, dispatch)
        }
        Spacer(Modifier.height(6.dp))
        RemoteKey("↓", KeyEvent.KEYCODE_DPAD_DOWN, dispatch)
        Spacer(Modifier.height(8.dp))
        Text("隐藏 (菜单键)", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
    }
}

@Composable
private fun RemoteRow(items: List<Pair<String, Int>>, dispatch: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, code) -> RemoteKey(label, code, dispatch) }
    }
}

@Composable
private fun RemoteKey(label: String, code: Int, dispatch: (Int) -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 34.dp).clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickableNoFocus { dispatch(code) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
