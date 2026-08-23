package com.example.myapplication

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.config.VlessLinkGenerator
import com.example.myapplication.config.XrayConfigGenerator
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.db.entity.KeyEntity
import com.example.myapplication.service.VpnServerService
import com.example.myapplication.ui.theme.VpnHotspotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

/**
 * Главный экран приложения VpnHotspotApp (ZPNK666).
 *
 * Dashboard:
 *  - локальный IP телефона (для подключения ПК);
 *  - заряд батареи + статус зарядки;
 *  - кнопка "Старт/Стоп сервер" (запускает [VpnServerService]).
 *
 * Управление ключами:
 *  - FAB "Создать ключ" → диалог (имя, лимит ГБ / ∞, лимит дней / навсегда);
 *  - список ключей из Room: имя, трафик, остаток, VLESS-ссылка + "Скопировать";
 *  - toggle активен/выключен, удаление.
 *
 * Вся логика связана с [AppDatabase] (KeyDao).
 *
 * @author ZPNK666
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VpnHotspotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen()
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ViewModel
// ═══════════════════════════════════════════════════════════════════

/**
 * Хранит состояние Dashboard'а и управляет ключами через Room.
 * Здесь же — генерация серверной пары ключей Reality (одна на все клиенты),
 * генерация VLESS-ссылок и пересборка JSON-конфига Xray.
 */
class DashboardViewModel(private val application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).keyDao()

    /** Все ключи из БД, обновляются реактивно (Flow → Compose). */
    val keys: StateFlow<List<KeyEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _localIp = MutableStateFlow(NetworkUtils.detectLocalIp())
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _battery = MutableStateFlow(BatteryInfo(-1, isCharging = false, isFull = false))
    val battery: StateFlow<BatteryInfo> = _battery.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { _battery.value = BatteryUtils.parse(it) }
        }
    }

    init {
        // ACTION_BATTERY_CHANGED — sticky broadcast, сразу вернёт последнее состояние
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    override fun onCleared() {
        runCatching { application.unregisterReceiver(batteryReceiver) }
        super.onCleared()
    }

    fun refreshIp() {
        _localIp.value = NetworkUtils.detectLocalIp()
    }

    // ── Сервер ──

    fun startServer(context: Context) {
        runCatching { VpnServerService.start(context) }
        _serverRunning.value = true
    }

    fun stopServer(context: Context) {
        runCatching { VpnServerService.stop(context) }
        _serverRunning.value = false
    }

    // ── Ключи ──

    fun createKey(name: String, country: String, device: String, trafficGb: Long?, timeDays: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            val reality = ensureServerReality()
            val uuid = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val expiresAt = timeDays?.let { now + it.toLong() * 86_400_000L }
            dao.insert(
                KeyEntity(
                    uuid = uuid,
                    name = name.ifBlank { "Ключ" },
                    country = country,
                    device = device,
                    pbk = reality.publicKey,
                    trafficLimitGb = trafficGb,
                    timeLimitDays = timeDays,
                    createdAt = now,
                    expiresAt = expiresAt,
                )
            )
            applyConfig()
        }
    }

    fun toggleKey(key: KeyEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setActive(key.uuid, !key.isActive)
            applyConfig()
        }
    }

    fun deleteKey(key: KeyEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(key)
            applyConfig()
        }
    }

    /** Сборка VLESS-ссылки для импорта в Hiddify/happ на ПК.
     *  В #name вшивается страна + устройство + имя — это Hiddify показывает как заголовок профиля. */
    fun buildVlessLink(key: KeyEntity): String {
        val reality = ensureServerReality()
        val displayName = "${key.country} · ${key.device} · ${key.name}"
        return VlessLinkGenerator.generate(
            ip = _localIp.value,
            port = 10808,
            uuid = key.uuid,
            pbk = key.pbk,
            sni = "www.google.com",
            shortId = reality.shortId,
            name = displayName,
        )
    }

    // ── Серверная пара ключей Reality (одна на все клиенты, в SharedPreferences) ──

    private fun ensureServerReality(): ServerReality {
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pub = prefs.getString(KEY_PBK, null)
        val priv = prefs.getString(KEY_PRIV, null)
        val sid = prefs.getString(KEY_SID, null)
        if (pub != null && priv != null && sid != null) {
            return ServerReality(priv, pub, sid)
        }
        val pair = XrayConfigGenerator.generateRealityKeyPair()
        val newSid = XrayConfigGenerator.generateShortId()
        prefs.edit()
            .putString(KEY_PRIV, pair.privateKey)
            .putString(KEY_PBK, pair.publicKey)
            .putString(KEY_SID, newSid)
            .apply()
        return ServerReality(pair.privateKey, pair.publicKey, newSid)
    }

    /** Если сервер запущен — перезагрузить конфиг Xray (новый список клиентов).
     *  Если не запущен — конфиг соберётся из свежей БД при следующем старте. */
    private fun applyConfig() {
        if (!_serverRunning.value) {
            Log.i(TAG, "Сервер не запущен — конфиг соберётся при следующем старте")
            return
        }
        runCatching {
            val intent = Intent(application, VpnServerService::class.java)
                .setAction(VpnServerService.ACTION_RELOAD)
            application.startService(intent)
        }.onFailure { Log.w(TAG, "reload не отправлен: ${it.message}") }
    }

    companion object {
        private const val TAG = "DashboardVM"
        private const val PREFS = "vpn_server_prefs"
        private const val KEY_PRIV = "reality_private_key"
        private const val KEY_PBK = "reality_public_key"
        private const val KEY_SID = "reality_short_id"
    }
}

/** Серверная пара ключей Reality (приватный — только сервер, публичный — в ссылки). */
data class ServerReality(val privateKey: String, val publicKey: String, val shortId: String)

/** Снимок состояния батареи. */
data class BatteryInfo(val level: Int, val isCharging: Boolean, val isFull: Boolean)

// ═══════════════════════════════════════════════════════════════════
//  Утилиты
// ═══════════════════════════════════════════════════════════════════

object NetworkUtils {
    /** Локальный IPv4 телефона (для подключения ПК). */
    fun detectLocalIp(): String = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress ?: "—"
    } catch (e: Exception) {
        "—"
    }
}

object BatteryUtils {
    fun parse(intent: Intent): BatteryInfo {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level in 0..100 && scale > 0) (level * 100) / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(pct, isCharging, isFull)
    }
}

/** Человекочитаемый размер: 12 Б / 4.1 МБ / 2.34 ГБ. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f КБ".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f МБ".format(mb)
    val gb = mb / 1024.0
    return "%.2f ГБ".format(gb)
}

/** "2.34 ГБ / 10 ГБ" или "2.34 ГБ / ∞". */
fun formatTraffic(used: Long, limitGb: Long?): String {
    val usedStr = formatBytes(used)
    val limitStr = limitGb?.let { "${it} ГБ" } ?: "∞"
    return "$usedStr / $limitStr"
}

/** Остаток трафика и срока: "осталось 7.66 ГБ · осталось 25 дн" / "трафик ∞ · срок ∞". */
fun formatRemaining(used: Long, limitGb: Long?, expiresAt: Long?): String {
    val parts = mutableListOf<String>()
    parts += if (limitGb != null) {
        val usedGb = used.toDouble() / 1_073_741_824.0
        val rem = (limitGb.toDouble() - usedGb).coerceAtLeast(0.0)
        "осталось %.2f ГБ".format(rem)
    } else {
        "трафик ∞"
    }
    parts += if (expiresAt != null) {
        val msLeft = expiresAt - System.currentTimeMillis()
        if (msLeft <= 0) "истёк" else "осталось ${msLeft / 86_400_000L} дн"
    } else {
        "срок ∞"
    }
    return parts.joinToString(" · ")
}

// ═══════════════════════════════════════════════════════════════════
//  Compose UI
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: DashboardViewModel = viewModel()) {
    val keys by vm.keys.collectAsStateWithLifecycle()
    val serverRunning by vm.serverRunning.collectAsStateWithLifecycle()
    val localIp by vm.localIp.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var copiedKeyId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vpn Hotspot", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "ZPNK666",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Создать ключ") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Dashboard ──
            item {
                DashboardCard(
                    localIp = localIp,
                    battery = battery,
                    serverRunning = serverRunning,
                    onStart = { vm.startServer(context) },
                    onStop = { vm.stopServer(context) },
                )
            }

            // ── Заголовок списка ──
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Ключи (${keys.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Список / пустое состояние ──
            if (keys.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Ключей пока нет.\nНажмите «Создать ключ».",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                items(keys, key = { it.id }) { key ->
                    val link = remember(key.id, key.uuid, key.pbk, localIp) {
                        vm.buildVlessLink(key)
                    }
                    KeyItemCard(
                        key = key,
                        vlessLink = link,
                        copied = copiedKeyId == key.id,
                        onCopy = {
                            clipboard.setText(AnnotatedString(link))
                            copiedKeyId = key.id
                            scope.launch { delay(2000); copiedKeyId = null }
                        },
                        onToggle = { vm.toggleKey(key) },
                        onDelete = { vm.deleteKey(key) },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateKeyDialog(
            onCreate = { name, country, device, gb, days ->
                vm.createKey(name, country, device, gb, days)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
}

// ── Dashboard ──

@Composable
fun DashboardCard(
    localIp: String,
    battery: BatteryInfo,
    serverRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Заголовок + батарея
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ZPNK666 · VPN-сервер",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                BatteryPill(battery)
            }
            HorizontalDivider()

            // Локальный IP
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Локальный IP (для ПК)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        localIp,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Кнопка Старт/Стоп
            Button(
                onClick = if (serverRunning) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = if (serverRunning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(
                    if (serverRunning) Icons.Filled.PowerSettingsNew else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (serverRunning) "Остановить сервер" else "Запустить сервер")
            }
        }
    }
}

@Composable
fun BatteryPill(battery: BatteryInfo) {
    val icon = if (battery.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull
    val pctText = if (battery.level >= 0) "${battery.level}%" else "—"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = "Батарея",
            modifier = Modifier.size(18.dp),
            tint = if (battery.isCharging || battery.level in 0..15) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.width(4.dp))
        Text(
            pctText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Карточка ключа ──

@Composable
fun KeyItemCard(
    key: KeyEntity,
    vlessLink: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Имя + страна + устройство + toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (key.isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        key.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${key.country} · ${key.device}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = key.isActive, onCheckedChange = { onToggle() })
            }

            // Трафик + остаток
            Text(
                "Трафик: ${formatTraffic(key.bytesUsed, key.trafficLimitGb)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                formatRemaining(key.bytesUsed, key.trafficLimitGb, key.expiresAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // VLESS-ссылка + копировать
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(8.dp),
                ) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            vlessLink,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onCopy) {
                        Icon(
                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = "Скопировать VLESS-ссылку",
                            tint = if (copied) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Удалить
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Удалить")
                }
            }
        }
    }
}

// ── Диалог создания ключа ──

@Composable
fun CreateKeyDialog(
    onCreate: (name: String, country: String, device: String, trafficGb: Long?, timeDays: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val countries = listOf("🇷🇺 RU", "🇺🇸 US", "🇩🇪 DE", "🇫🇷 FR", "🇳🇱 NL", "🇬🇧 GB")
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("🇷🇺 RU") }
    var device by remember { mutableStateOf("Телефон") }
    var unlimitedTraffic by remember { mutableStateOf(false) }
    var trafficGb by remember { mutableStateOf("10") }
    var forever by remember { mutableStateOf(false) }
    var timeDays by remember { mutableStateOf("30") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый ключ VLESS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя ключа") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = device,
                    onValueChange = { device = it },
                    label = { Text("Устройство (для какого ПК)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()

                Text("Страна (показывается в Hiddify):", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    countries.forEach { c ->
                        Text(
                            text = c,
                            modifier = Modifier
                                .background(
                                    if (c == country) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50),
                                )
                                .clickable { country = c }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            color = if (c == country) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                HorizontalDivider()

                Text("Лимит трафика", style = MaterialTheme.typography.labelLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Switch(checked = unlimitedTraffic, onCheckedChange = { unlimitedTraffic = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (unlimitedTraffic) "Бесконечно" else "Ограничить, ГБ")
                }
                if (!unlimitedTraffic) {
                    OutlinedTextField(
                        value = trafficGb,
                        onValueChange = { trafficGb = it.filter { c -> c.isDigit() } },
                        label = { Text("ГБ") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider()

                Text("Срок действия", style = MaterialTheme.typography.labelLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Switch(checked = forever, onCheckedChange = { forever = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (forever) "Навсегда" else "Ограничить, дней")
                }
                if (!forever) {
                    OutlinedTextField(
                        value = timeDays,
                        onValueChange = { timeDays = it.filter { c -> c.isDigit() } },
                        label = { Text("Дней") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val gb = if (unlimitedTraffic) null else trafficGb.toLongOrNull()
                    val days = if (forever) null else timeDays.toIntOrNull()
                    onCreate(name.trim().ifBlank { "Ключ" }, country, device, gb, days)
                },
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
