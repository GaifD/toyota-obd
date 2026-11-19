package com.gaifd.toyotaobd.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.BuildConfig
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.data.Settings
import com.gaifd.toyotaobd.data.Trips
import com.gaifd.toyotaobd.data.UpdateInfo
import com.gaifd.toyotaobd.data.UpdateState
import com.gaifd.toyotaobd.data.Updater
import com.gaifd.toyotaobd.obd.Pids
import com.gaifd.toyotaobd.service.Obd
import java.io.File

data class SystemStatus(val batteryOk: Boolean, val notificationsOk: Boolean, val bluetoothOk: Boolean, val carLinked: Boolean)

@Composable
fun SettingsScreen(
    s: Settings,
    sys: SystemStatus,
    scanProgress: Float?,
    onPickAdapter: () -> Unit,
    onPickCar: () -> Unit,
    onLinkCar: () -> Unit,
    onFixBattery: () -> Unit,
    onFixNotifications: () -> Unit,
    onGpsCalib: () -> Unit,
    onGpsTrack: (Boolean) -> Unit,
    onToyotaScan: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (UpdateInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
) {
    var edit by remember { mutableStateOf<String?>(null) }
    var showJournal by remember { mutableStateOf(false) }
    val update by Updater.state.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
    ) {
        ScreenTitle("Настройки")

        SectionTitle("Подключение")
        Card {
            ActionRow(
                "Адаптер OBD2", s.elmName?.let { "$it · ${s.elmMac}" } ?: "Не выбран — сначала сопряги ELM327 в настройках Bluetooth (PIN 1234 или 0000)",
                if (s.elmMac == null) Ic.alert else Ic.chevronRight, if (s.elmMac == null) C.amber else C.muted, onClick = onPickAdapter,
            )
            ActionRow(
                "Машина (магнитола)", s.carName?.let { "$it — когда телефон к ней подключается, Бортовик подключается к адаптеру сам" }
                    ?: "Не выбрана — выбери Bluetooth магнитолы, чтобы подключаться автоматически",
                onClick = onPickCar,
            )
            if (s.carMac != null && !sys.carLinked) {
                ActionRow("Разрешить запуск из фона", "Привязать машину — тогда Android даст подключаться без открытия приложения", Ic.alert, C.amber, onClick = onLinkCar)
            }
            ToggleRow("Подключаться автоматически", "Сел в машину — поездка пишется сама", s.autoStart) { v -> Prefs.update { it.copy(autoStart = v) } }
            ActionRow(
                "Работа в фоне", if (sys.batteryOk) "Без ограничений батареи" else "Android может оборвать запись поездки — сними ограничение",
                if (sys.batteryOk) Ic.check else Ic.alert, if (sys.batteryOk) C.accent else C.amber, onClick = onFixBattery,
            )
            ActionRow(
                "Уведомления", if (sys.notificationsOk) "Включены — предупреждения и итоги поездок" else "Выключены — предупреждения не придут",
                if (sys.notificationsOk) Ic.check else Ic.alert, if (sys.notificationsOk) C.accent else C.amber, divider = false, onClick = onFixNotifications,
            )
        }

        SectionTitle("Топливо")
        Card {
            ActionRow("Объём бака", "${fmt(s.tankL, 0)} л — для пересчёта процентов в литры", onClick = { edit = "tank" })
            ActionRow("Цена по умолчанию", "${fmt(s.defaultPrice, 2)} грн/л — пока не указана ни одна заправка", onClick = { edit = "price" })
            ActionRow(
                "Поправка расхода",
                (if (s.fuelK == 1.0) "Без поправки. " else "×${fmt(s.fuelK, 3)}. ") + "Уточняется сама по заправкам до полного; можно задать вручную",
                onClick = { edit = "fuelK" },
            )
            val tank = com.gaifd.toyotaobd.data.Fuel.tank.collectAsState().value
            ActionRow(
                "Порог лампочки", tank.lampL?.let { "Лампочка горит при ${fmt(it, 1)} л — нажми, чтобы сбросить" }
                    ?: "Пока примерно ${fmt(com.gaifd.toyotaobd.data.Range.DEFAULT_LAMP_L, 1)} л. Загорится — нажми «Лампочка» на «Приборах»",
                onClick = { if (tank.lampL != null) com.gaifd.toyotaobd.data.Fuel.setLamp(null) },
            )
            val m = Obd.live.collectAsState().value.info.fuelMethod
            Text("Расход считается $m.", fontSize = 12.sp, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }

        SectionTitle("Скорость и пробег")
        Card {
            ActionRow(
                "Поправка спидометра",
                if (s.speedK == 1.0) "Без поправки — как показывает машина" else "${if (s.speedK > 1) "+" else ""}${fmt((s.speedK - 1) * 100, 1)}%",
                onClick = { edit = "speedK" },
            )
            ActionRow("Откалибровать по GPS", "Едь ровно 50+ км/ч пару минут. GPS включается только на это время", onClick = onGpsCalib)
            ToggleRow(
                "Трек поездки на карте", "GPS во время поездок. При глушилках прыжки отбрасываются по скорости машины. " +
                    "Чтобы писалось с выключенным экраном, разреши геолокацию «Всегда»",
                s.gpsTrack, onChange = onGpsTrack,
            )
            val odo = s.odoKm?.let { it + (Trips.totalKm - s.odoAtTripsKm) }
            ActionRow(
                "Пробег машины", odo?.let { "${fmt(it, 0)} км (≈ ${fmt(it / 1.609344, 0)} миль)" } ?: "Не задан — введи с приборки, дальше считается сам",
                divider = false, onClick = { edit = "odo" },
            )
        }

        SectionTitle("Предупреждения")
        Card {
            ToggleRow("Перегрев", "Температура ОЖ 108 °C и выше", s.warnCoolant) { v -> Prefs.update { it.copy(warnCoolant = v) } }
            ToggleRow("Новые ошибки", "Появился код — сразу уведомление с расшифровкой", s.warnDtc) { v -> Prefs.update { it.copy(warnDtc = v) } }
            ToggleRow("Аккумулятор 12 В", "Разряжен на стоянке или не заряжается на ходу", s.warnVolt) { v -> Prefs.update { it.copy(warnVolt = v) } }
            ToggleRow("Мало топлива", "До лампочки меньше 60 км", s.warnFuel) { v -> Prefs.update { it.copy(warnFuel = v) } }
            ToggleRow("Гибридная батарея не заряжается", "Заряд ниже 35% и не растёт, хотя двигатель работает", s.warnSoc, divider = false) { v -> Prefs.update { it.copy(warnSoc = v) } }
        }

        SectionTitle("Попутчики")
        Card {
            ActionRow("Износ для «Пассажиров»", "${fmt(s.wearRate, 2)} грн/км — с «Друзей» только топливо", divider = false, onClick = { edit = "wear" })
        }

        SectionTitle("Экран")
        Card {
            ToggleRow("HUD зеркально", "В режиме HUD картинка отражается, чтобы читаться в лобовом стекле", s.hudMirror) { v -> Prefs.update { it.copy(hudMirror = v) } }
            Text("Тема", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Settings.THEME_DARK to "Тёмная", Settings.THEME_LIGHT to "Светлая", Settings.THEME_SYSTEM to "Как в системе", Settings.THEME_BY_TIME to "По времени")
                    .take(3).forEach { (v, t) -> Chip(t, s.theme == v) { Prefs.update { it.copy(theme = v) } } }
            }
            Row(Modifier.padding(top = 8.dp)) {
                Chip("По времени", s.theme == Settings.THEME_BY_TIME) { Prefs.update { it.copy(theme = Settings.THEME_BY_TIME) } }
            }
            Text("Цвет", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                Accent.PRESETS.forEach { c ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .then(if (s.accent == c) Modifier.border(3.dp, C.text, CircleShape) else Modifier)
                            .clickable { Prefs.update { it.copy(accent = c) } },
                    )
                }
            }
        }

        SectionTitle("Для разработки")
        Card {
            ActionRow("Журнал адаптера", "Последние команды и ответы ELM327 — пригодится, если что-то не читается", onClick = { showJournal = true })
            ActionRow(
                "Сканер Toyota",
                if (scanProgress != null) "Сканирую… ${(scanProgress * 100).toInt()}%"
                else "Перебирает расширенные параметры гибрида (2–4 мин, машина в READY) и сохраняет файл — пришли его, по нему сделаем блоки батареи",
                Ic.share, divider = scanProgress != null, onClick = { if (scanProgress == null) onToyotaScan() },
            )
            if (scanProgress != null) {
                LinearProgressIndicator(progress = { scanProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), color = C.accent, trackColor = C.line)
            }
        }

        SectionTitle("Обновления", "версия ${BuildConfig.VERSION_NAME}")
        Card {
            when (val u = update) {
                is UpdateState.Available -> ActionRow("Доступна версия ${u.info.version}", "Скачать и установить", Ic.download, C.accent, divider = false) { onDownloadUpdate(u.info) }
                is UpdateState.Downloading -> ActionRow("Скачиваю ${u.info.version}…", "${(u.progress * 100).toInt()}%", null, divider = false) {}
                is UpdateState.Ready -> ActionRow("Версия ${u.info.version} скачана", "Установить", Ic.download, C.accent, divider = false) { onInstallUpdate(u.file) }
                is UpdateState.Checking -> ActionRow("Проверяю…", "", null, divider = false) {}
                is UpdateState.UpToDate -> ActionRow("Установлена последняя версия", "Проверить ещё раз", Ic.check, C.accent, divider = false, onClick = onCheckUpdate)
                is UpdateState.Failed -> ActionRow(u.message, "Повторить", Ic.refresh, divider = false, onClick = onCheckUpdate)
                else -> ActionRow("Проверить обновления", "Новые версии выходят на GitHub", Ic.refresh, divider = false, onClick = onCheckUpdate)
            }
        }
    }

    when (edit) {
        "tank" -> NumberDialog("Объём бака, л", fmt(s.tankL, 0), null, { edit = null }) { v -> Prefs.update { it.copy(tankL = v.coerceIn(10.0, 200.0)) } }
        "price" -> NumberDialog("Цена по умолчанию, грн/л", fmt(s.defaultPrice, 2), null, { edit = null }) { v -> Prefs.update { it.copy(defaultPrice = v) } }
        "fuelK" -> NumberDialog(
            "Поправка расхода (множитель)", fmt(s.fuelK, 3),
            "1 — без поправки. Если приложение занижает расход на 5%, поставь 1,05.", { edit = null },
        ) { v -> Prefs.update { it.copy(fuelK = v.coerceIn(0.5, 2.0)) } }
        "speedK" -> NumberDialog(
            "Поправка спидометра, %", fmt((s.speedK - 1) * 100, 1),
            "Например, −3 — если машина завышает на 3%. 0 — без поправки.", { edit = null }, allowMinus = true,
        ) { v -> Prefs.update { it.copy(speedK = (1 + v / 100).coerceIn(0.8, 1.2)) } }
        "odo" -> OdoDialog(onDismiss = { edit = null })
        "wear" -> NumberDialog("Износ, грн за км", fmt(s.wearRate, 2), "Шины, масло, амортизация. Берётся только с «Пассажиров».", { edit = null }) { v ->
            Prefs.update { it.copy(wearRate = v.coerceIn(0.0, 100.0)) }
        }
    }

    if (showJournal) {
        val text = remember { Obd.journal() }
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { showJournal = false },
            containerColor = C.card,
            title = { Text("Журнал адаптера") },
            text = {
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = C.soft) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("journal", text))
                    showJournal = false
                }) { Text("Копировать", color = C.accent) }
            },
            dismissButton = { TextButton(onClick = { showJournal = false }) { Text("Закрыть", color = C.soft) } },
        )
    }
}

@Composable
fun NumberDialog(title: String, initial: String, hint: String?, onDismiss: () -> Unit, allowMinus: Boolean = false, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val v = text.replace(',', '.').replace('−', '-').trim().toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowMinus) {
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { t -> text = t.filter { it.isDigit() || it in ",.-−" } },
                        singleLine = true, label = { Text("Значение") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    NumField("Значение", text) { text = it }
                }
                if (hint != null) Text(hint, fontSize = 12.sp, color = C.muted, lineHeight = 16.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = v != null, onClick = { onSave(v!!); onDismiss() }) { Text("Сохранить", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = C.soft) } },
    )
}

@Composable
fun OdoDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var miles by remember { mutableStateOf(false) }
    val v = text.replace(',', '.').trim().toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Пробег с приборки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Километры", !miles) { miles = false }
                    Chip("Мили", miles) { miles = true }
                }
                NumField(if (miles) "Пробег, миль" else "Пробег, км", text) { text = it }
                if (v != null && miles) Text("= ${fmt(v * 1.609344, 0)} км", fontSize = 13.sp, color = C.muted)
            }
        },
        confirmButton = {
            TextButton(enabled = v != null, onClick = {
                val km = if (miles) v!! * 1.609344 else v!!
                Prefs.update { it.copy(odoKm = km, odoAtTripsKm = Trips.totalKm) }
                onDismiss()
            }) { Text("Сохранить", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = C.soft) } },
    )
}

/** Калибровка спидометра: сравниваем скорость GPS со скоростью машины на ровном ходу. */
@SuppressLint("MissingPermission")
@Composable
fun GpsCalibDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ratios = remember { mutableStateListOf<Double>() }
    var gps by remember { mutableStateOf<Double?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val live by Obd.live.collectAsState()
    DisposableEffect(Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (!loc.hasSpeed()) return
                val g = loc.speed * 3.6
                gps = g
                val raw = Obd.live.value.values[Pids.SPEED]
                if (raw != null && raw > 40 && g > 40 && loc.accuracy < 20) {
                    ratios += g / raw
                    if (ratios.size > 120) ratios.removeAt(0)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        } catch (e: Exception) {
            err = "GPS недоступен: ${e.message}"
        }
        onDispose { lm.removeUpdates(l) }
    }
    val k = if (ratios.size >= 15) ratios.sorted()[ratios.size / 2] else null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Калибровка по GPS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Едь ровно быстрее 40 км/ч. Нужно 15+ замеров.", fontSize = 13.sp, color = C.soft)
                KeyValue("Машина", live.values[Pids.SPEED]?.let { "${it.toInt()} км/ч" } ?: "—")
                KeyValue("GPS", gps?.let { "${it.toInt()} км/ч" } ?: "ищу спутники…")
                KeyValue("Замеров", "${ratios.size}", divider = false)
                if (k != null) Text("Поправка: ${if (k >= 1) "+" else ""}${fmt((k - 1) * 100, 1)}%", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (err != null) Text(err!!, fontSize = 12.sp, color = C.amber)
            }
        },
        confirmButton = {
            TextButton(enabled = k != null, onClick = {
                Prefs.update { it.copy(speedK = k!!.coerceIn(0.8, 1.2)) }
                onDismiss()
            }) { Text("Применить", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = C.soft) } },
    )
}
