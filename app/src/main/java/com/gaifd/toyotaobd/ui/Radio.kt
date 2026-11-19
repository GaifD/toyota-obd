package com.gaifd.toyotaobd.ui

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

private const val RADIO_PKG = "com.gaifd.carradio"

/** Связь с «Авторадио»: станция, трек, кнопки. Само радио остаётся отдельным приложением. */
class RadioState {
    var installed by mutableStateOf(false)
    var controller by mutableStateOf<MediaController?>(null)
    var station by mutableStateOf("")
    var track by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)

    fun update(p: Player) {
        val m = p.mediaMetadata
        val st = m.station?.toString() ?: m.title?.toString() ?: ""
        station = st
        val t = m.title?.toString()
        val a = m.artist?.toString()
        track = if (t != null && t != st) listOfNotNull(a?.takeIf { it != st && it != "Авторадио" }, t).joinToString(" — ") else null
        playing = p.playWhenReady
    }

    fun toggle() {
        controller?.let { if (it.playWhenReady) it.pause() else it.play() }
    }

    fun next() {
        controller?.let { it.seekToNextMediaItem(); it.play() }
    }

    fun prev() {
        controller?.let { it.seekToPreviousMediaItem(); it.play() }
    }
}

@Composable
fun rememberRadio(): RadioState {
    val ctx = LocalContext.current
    val state = remember { RadioState() }
    DisposableEffect(Unit) {
        val installed = try {
            ctx.packageManager.getPackageInfo(RADIO_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
        state.installed = installed
        if (!installed) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = state.update(player)
        }
        val future = try {
            MediaController.Builder(ctx, SessionToken(ctx, ComponentName(RADIO_PKG, "$RADIO_PKG.playback.PlaybackService"))).buildAsync()
        } catch (_: Exception) {
            null
        }
        future?.addListener({
            try {
                val c = future.get()
                state.controller = c
                c.addListener(listener)
                state.update(c)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            state.controller?.removeListener(listener)
            future?.let { MediaController.releaseFuture(it) }
            state.controller = null
        }
    }
    return state
}

/** Полоса радио: станция, трек, ⏮ ⏯ ⏭. [big] — для экрана «на ходу». */
@Composable
fun RadioBar(r: RadioState, big: Boolean, fg: Color = C.text, muted: Color = C.muted, bg: Color = C.card) {
    val ctx = LocalContext.current
    if (!r.installed) return
    val size = if (big) 56 else 40
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).padding(horizontal = 14.dp, vertical = if (big) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).clickable {
                ctx.packageManager.getLaunchIntentForPackage(RADIO_PKG)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        ) {
            Text(
                r.station.ifBlank { "Авторадио" }, fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.Bold,
                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = r.track ?: if (r.controller == null) "подключаюсь…" else if (r.playing) "в эфире" else "пауза"
            Text(sub, fontSize = if (big) 14.sp else 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RadioBtn(Ic.prev, size, fg) { r.prev() }
        RadioBtn(if (r.playing) Ic.pause else Ic.play, size, fg) { r.toggle() }
        RadioBtn(Ic.next, size, fg) { r.next() }
    }
}

@Composable
private fun RadioBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Int, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(99.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.48).dp))
    }
}
