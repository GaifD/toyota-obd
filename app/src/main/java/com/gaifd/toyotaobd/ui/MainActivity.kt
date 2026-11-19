package com.gaifd.toyotaobd.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.gaifd.toyotaobd.data.Fuel
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.data.Trips
import com.gaifd.toyotaobd.data.UpdateState
import com.gaifd.toyotaobd.data.Updater
import com.gaifd.toyotaobd.service.Obd
import com.gaifd.toyotaobd.service.ObdService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_FILL = "fill"
    }

    private var tab by mutableIntStateOf(0)
    private var sub by mutableStateOf<String?>(null)        // "cat", "live", "battery", "maint", "trip"
    private var openTrip by mutableStateOf<Long?>(null)
    private var showOdo by mutableStateOf(false)
    private var showDrive by mutableStateOf(false)
    private var showFill by mutableStateOf(false)
    private var picker by mutableStateOf<String?>(null)     // "elm" или "car"
    private var showGps by mutableStateOf(false)
    private var scanProgress by mutableStateOf<Float?>(null)

    private var batteryOk by mutableStateOf(true)
    private var notificationsOk by mutableStateOf(true)
    private var bluetoothOk by mutableStateOf(true)
    private var carLinked by mutableStateOf(false)

    private var afterBt: (() -> Unit)? = null

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val btLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh()
        val next = afterBt
        afterBt = null
        if (ok) next?.invoke() else toast("Без доступа к Bluetooth не получится подключиться к адаптеру")
    }
    private val locLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) showGps = true else toast("Без доступа к геолокации калибровка по GPS невозможна")
    }
    private val trackLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) {
            Prefs.update { it.copy(gpsTrack = true) }
            com.gaifd.toyotaobd.service.Gps.start(this)
            if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                toast("Чтобы трек писался и с выключенным экраном, выбери «Разрешить всегда»")
                bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else toast("Без геолокации трек не записать")
    }
    private val bgLocLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val cdmLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        refresh()
        if (carLinked) toast("Машина привязана")
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            // BOM — чтобы Excel правильно открыл кириллицу.
            contentResolver.openOutputStream(uri)?.use { it.write(("﻿" + Trips.csv()).toByteArray()) }
            toast("Сохранено поездок: ${Trips.items.value.size}")
        } catch (e: Exception) {
            toast("Не удалось сохранить: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF0D1215.toInt()),
        )
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Trips.init(this)
        Fuel.init(this)
        com.gaifd.toyotaobd.data.Riders.init(this)
        com.gaifd.toyotaobd.data.Maint.init(this)
        handleIntent(intent)

        if (System.currentTimeMillis() - Prefs.lastUpdateCheck > 12 * 3600_000L) {
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            lifecycleScope.launch { Updater.check() }
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && !Prefs.askedNotifications) {
            Prefs.askedNotifications = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by Prefs.settings.collectAsState()
            val light = isLight(settings.theme)
            LaunchedEffect(light) {
                if (light) enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
                ) else enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.dark(0xFF0D1215.toInt()),
                )
            }
            AppTheme(light, settings.accent) { AppRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra(EXTRA_TAB)) {
            tab = i.getIntExtra(EXTRA_TAB, 0)
            sub = null
            showDrive = false
        }
        if (i.getBooleanExtra(EXTRA_FILL, false)) showFill = true
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Composable
    private fun isLight(theme: Int): Boolean {
        val systemDark = isSystemInDarkTheme()
        val hour by produceState(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            while (true) {
                value = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                kotlinx.coroutines.delay(60_000)
            }
        }
        return when (theme) {
            com.gaifd.toyotaobd.data.Settings.THEME_LIGHT -> true
            com.gaifd.toyotaobd.data.Settings.THEME_SYSTEM -> !systemDark
            com.gaifd.toyotaobd.data.Settings.THEME_BY_TIME -> hour in 7..18
            else -> false
        }
    }

    // ---------- Система ----------

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        batteryOk = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
        notificationsOk = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        bluetoothOk = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        carLinked = isCarLinked()
    }

    private fun withBt(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            afterBt = action
            btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else action()
    }

    private fun connect() = withBt { ObdService.start(this) }

    @SuppressLint("BatteryLife")
    private fun fixBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppSettings()
            }
        }
    }

    private fun fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<Pair<String, String>> = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.map { (it.name ?: it.address) to it.address }
            ?.sortedBy { it.first.lowercase() }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun choose(kind: String, name: String, mac: String) {
        picker = null
        if (kind == "elm") {
            Prefs.update { it.copy(elmName = name, elmMac = mac) }
            toast("Адаптер выбран — подключаюсь")
            connect()
        } else {
            Prefs.update { it.copy(carName = name, carMac = mac) }
            refresh()
            if (!carLinked) linkCar()
        }
    }

    /** Привязка машины как «сопутствующего устройства» — Android разрешает стартовать из фона при её подключении. */
    private fun linkCar() {
        val mac = Prefs.settings.value.carMac ?: return
        val cdm = try {
            getSystemService(CompanionDeviceManager::class.java)
        } catch (_: Exception) {
            null
        }
        if (cdm == null) {
            toast("Телефон не поддерживает привязку — хватит снятого ограничения батареи")
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(mac).build())
            .setSingleDevice(true)
            .build()
        try {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    cdmLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                }

                override fun onFailure(error: CharSequence?) {
                    toast("Не получилось привязать: ${error ?: "машина не найдена"}. Включи машину и попробуй рядом с ней.")
                }
            }, null)
        } catch (e: Exception) {
            toast("Не получилось привязать: ${e.message}")
        }
    }

    private fun isCarLinked(): Boolean {
        val mac = Prefs.settings.value.carMac ?: return false
        return try {
            val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.any { it.deviceMacAddress?.toString()?.equals(mac, ignoreCase = true) == true }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.any { it.equals(mac, ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun gpsTrack(on: Boolean) {
        if (!on) {
            Prefs.update { it.copy(gpsTrack = false) }
            com.gaifd.toyotaobd.service.Gps.stop()
            return
        }
        trackLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun gpsCalib() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) showGps = true
        else locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun toyotaScan() {
        if (!Obd.online) {
            toast("Сначала подключись к машине и включи READY")
            return
        }
        scanProgress = 0f
        lifecycleScope.launch {
            val text = Obd.toyotaScan { n, total -> scanProgress = n.toFloat() / total }
            scanProgress = null
            if (text == null) {
                toast("Связь с машиной пропала — попробуй ещё раз")
                return@launch
            }
            val f = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "export").apply { mkdirs() }
                File(dir, "toyota-scan.txt").apply { writeText(text) }
            }
            share(f, "Сканер Toyota")
        }
    }

    private fun share(f: File, title: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    title,
                )
            )
        } catch (e: Exception) {
            toast("Не удалось поделиться: ${e.message}")
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ---------- UI ----------

    @Composable
    private fun AppRoot() {
        val live by Obd.live.collectAsState()
        val settings by Prefs.settings.collectAsState()
        val tank by Fuel.tank.collectAsState()
        val trips by Trips.items.collectAsState()
        val updateState by Updater.state.collectAsState()
        val updateVersion = (updateState as? UpdateState.Available)?.info?.version
        val radio = rememberRadio()

        if (showDrive) {
            BackHandler { showDrive = false }
            Box(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
                DriveScreen(live, settings, radio) { showDrive = false }
            }
            return
        }

        Scaffold(containerColor = C.bg, bottomBar = { BottomNav() }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> if (sub == "maint") MaintScreen(onBack = { sub = null }, onSetOdo = { showOdo = true }) else DashScreen(
                        live = live, s = settings, tank = tank, batteryOk = batteryOk, update = updateVersion, radio = radio,
                        onConnect = ::connect,
                        onDisconnect = { ObdService.stop(this@MainActivity) },
                        onDrive = { showDrive = true },
                        onSettings = { tab = 3 },
                        onDiag = { tab = 2; sub = null },
                        onBattery = { tab = 2; sub = "battery" },
                        onMaint = { sub = "maint" },
                        onFixBattery = ::fixBattery,
                        onFill = { showFill = true },
                    )
                    1 -> {
                        val t = openTrip?.let { id -> trips.firstOrNull { it.start == id } }
                        if (sub == "trip" && t != null) TripScreen(t) { sub = null; openTrip = null }
                        else TripsScreen(
                            trips, tank, onFill = { showFill = true }, onExport = { exportLauncher.launch("bortovik-trips.csv") },
                            onOpen = { openTrip = it.start; sub = "trip" },
                        )
                    }
                    2 -> when (sub) {
                        "cat" -> CatScreen(live) { sub = null }
                        "live" -> LiveScreen(live) { sub = null }
                        "battery" -> BatteryScreen(live) { sub = null }
                        else -> DiagScreen(live, onCat = { sub = "cat" }, onLive = { sub = "live" }, onBattery = { sub = "battery" })
                    }
                    else -> SettingsScreen(
                        s = settings,
                        sys = SystemStatus(batteryOk, notificationsOk, bluetoothOk, carLinked),
                        scanProgress = scanProgress,
                        onPickAdapter = { withBt { picker = "elm" } },
                        onPickCar = { withBt { picker = "car" } },
                        onLinkCar = ::linkCar,
                        onFixBattery = ::fixBattery,
                        onFixNotifications = ::fixNotifications,
                        onGpsCalib = ::gpsCalib,
                        onGpsTrack = ::gpsTrack,
                        onToyotaScan = ::toyotaScan,
                        onCheckUpdate = { lifecycleScope.launch { Updater.check() } },
                        onDownloadUpdate = { info -> lifecycleScope.launch { Updater.download(this@MainActivity, info) } },
                        onInstallUpdate = { f -> Updater.install(this@MainActivity, f) },
                    )
                }
            }
        }

        if (showFill) FillDialog(tank.pendingFillL, live.fuelLevelL) { showFill = false }
        if (showGps) GpsCalibDialog { showGps = false }
        if (showOdo) OdoDialog { showOdo = false }
        picker?.let { kind ->
            DevicePicker(
                title = if (kind == "elm") "Адаптер OBD2" else "Bluetooth машины",
                hint = if (kind == "elm") "Обычно называется OBDII, OBD2, V-LINK или ELM327. Нет в списке — сопряги его в настройках Bluetooth телефона."
                else "Магнитола машины — когда телефон к ней подключается, Бортовик начинает работу сам.",
                devices = bondedDevices(),
                current = if (kind == "elm") settings.elmMac else settings.carMac,
                onPick = { n, m -> choose(kind, n, m) },
                onDismiss = { picker = null },
            )
        }
    }

    @Composable
    private fun BottomNav() {
        Column(Modifier.fillMaxWidth().background(C.bar)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                NavItem("Приборы", Ic.gauge, tab == 0, Modifier.weight(1f)) { sub = null; tab = 0 }
                NavItem("Поездки", Ic.route, tab == 1, Modifier.weight(1f)) { sub = null; tab = 1 }
                NavItem("Диагностика", Ic.wrench, tab == 2, Modifier.weight(1f)) { sub = null; tab = 2 }
                NavItem("Настройки", Ic.sliders, tab == 3, Modifier.weight(1f)) { sub = null; tab = 3 }
            }
        }
    }

    @Composable
    private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.heightIn(min = 52.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val color = if (selected) C.accent else C.muted
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Text(label, fontSize = 11.sp, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun DevicePicker(
    title: String,
    hint: String,
    devices: List<Pair<String, String>>,
    current: String?,
    onPick: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, fontSize = 12.sp, color = C.muted, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) Text("Сопряжённых устройств нет", fontSize = 14.sp, color = C.soft)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(devices) { (name, mac) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(name, mac) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(mac, fontSize = 11.sp, color = C.muted)
                            }
                            if (mac.equals(current, ignoreCase = true)) Icon(Ic.check, contentDescription = null, tint = C.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = C.soft) } },
    )
}
