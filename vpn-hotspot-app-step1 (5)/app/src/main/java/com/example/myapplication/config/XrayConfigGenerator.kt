package com.example.myapplication.config

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bouncycastle.crypto.generators.X25519PrivateKeyGenerator
import org.bouncycastle.crypto.params.X25519PrivateKeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * Генератор JSON-конфигурации Xray-core.
 *
 * Собирает конфиг с:
 *  - inbound  : VLESS + XTLS-Reality на порту [XrayConfigInput.port] (по умолчанию 10808);
 *  - api      : dokodemo-door inbound на 127.0.0.1:[API_PORT] + api outbound —
 *               нужно, чтобы TrafficMonitor мог опрашивать статистику (`xray api stats`);
 *  - routing  : api-in→api, geosite:ru/geoip:ru→direct, заблокированные→proxy, остальное→direct.
 *
 * email каждого клиента = "key-${uuid}" — уникальный тег для per-key статистики.
 *
 * @author ZPNK666
 */

// ═══════════════════════════════════════════════════════════════════
//  Data-классы для JSON (kotlinx.serialization)
// ═══════════════════════════════════════════════════════════════════

@Serializable
data class XrayConfig(
    val log: XrayLog,
    // inbounds — список разнородных (VLESS + api dokodemo-door), поэтому JsonElement
    val inbounds: List<JsonElement>,
    val outbounds: List<JsonObject>,
    val routing: XrayRouting,
)

@Serializable
data class XrayLog(val loglevel: String = "warning")

@Serializable
data class XrayInboundClient(
    val id: String,
    val level: Int = 0,
    val email: String? = null,
)

@Serializable
data class XrayInboundSettings(
    val clients: List<XrayInboundClient>,
    val decryption: String = "none",
)

@Serializable
data class XrayRealitySettings(
    val show: Boolean = false,
    val dest: String,
    val xver: Int = 0,
    val serverNames: List<String>,
    val privateKey: String,
    val shortIds: List<String>,
)

@Serializable
data class XrayStreamSettings(
    val network: String = "tcp",
    val security: String = "reality",
    val realitySettings: XrayRealitySettings,
)

@Serializable
data class XraySniffing(
    val enabled: Boolean = true,
    val destOverride: List<String> = listOf("http", "tls"),
)

@Serializable
data class XrayInbound(
    val tag: String = "vless-in",
    val listen: String = "0.0.0.0",
    val port: Int,
    val protocol: String = "vless",
    val settings: XrayInboundSettings,
    val streamSettings: XrayStreamSettings,
    val sniffing: XraySniffing = XraySniffing(),
)

@Serializable
data class XrayRule(
    val type: String = "field",
    val domain: List<String>? = null,
    val ip: List<String>? = null,
    val inboundTag: List<String>? = null,   // для маршрутизации api-in → api
    val outboundTag: String,
    val network: String? = null,
)

@Serializable
data class XrayRouting(
    val domainStrategy: String = "IPIfNonMatch",
    val rules: List<XrayRule>,
)

// ═══════════════════════════════════════════════════════════════════
//  Public types
// ═══════════════════════════════════════════════════════════════════

/** Пара ключей X25519 для Reality. */
data class RealityKeyPair(
    val privateKey: String,
    val publicKey: String,
)

/** Описание клиента для inbound (один UUID = одно устройство/ключ). */
data class ClientInfo(
    val uuid: String,
    val email: String? = null,   // если null — генератор поставит "key-${uuid}"
)

/**
 * Апстрим-прокси для заблокированных доменов.
 * Если null — "proxy" outbound = freedom (телефон раздаёт свой прямой интернет).
 */
data class UpstreamProxy(
    val protocol: String,
    val address: String,
    val port: Int,
    val userId: String? = null,
    val encryption: String? = null,
)

/** Входные данные для генерации конфига. */
data class XrayConfigInput(
    val port: Int = 10808,
    val clients: List<ClientInfo>,
    val realitySni: String = "www.google.com",
    val realityKeyPair: RealityKeyPair,
    val shortIds: List<String>,
    val upstreamProxy: UpstreamProxy? = null,
    val apiPort: Int = 10085,   // порт api dokodemo-door для статистики
)

// ═══════════════════════════════════════════════════════════════════
//  Generator
// ═══════════════════════════════════════════════════════════════════

object XrayConfigGenerator {

    /** Порт API для опроса статистики (должен совпадать с TrafficMonitor). */
    const val API_PORT = 10085

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Генерирует пару ключей X25519 для Reality (Bouncy Castle). */
    fun generateRealityKeyPair(): RealityKeyPair {
        val random = SecureRandom()
        val gen = X25519PrivateKeyGenerator().apply {
            init(X25519PrivateKeyGenerationParameters(random))
        }
        val privParams = gen.generatePrivateKey() as X25519PrivateKeyParameters
        val privBytes = privParams.encoded
        val pubParams = privParams.generatePublicKey()
        val pubBytes = pubParams.encoded
        return RealityKeyPair(
            privateKey = base64(privBytes),
            publicKey = base64(pubBytes),
        )
    }

    /** Случайный shortId для Reality (8 hex-символов = 4 байта). */
    fun generateShortId(): String {
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Домены, заблокированные в РФ → через "proxy" outbound. */
    fun blockedDomains(): List<String> = listOf(
        "domain:discord.com", "domain:discord.gg", "domain:discordapp.com",
        "domain:discord.media", "domain:discordapp.net",
        "domain:instagram.com", "domain:cdninstagram.com",
        "domain:twitter.com", "domain:x.com", "domain:twimg.com", "domain:t.co",
        "domain:facebook.com", "domain:fbcdn.net", "domain:fb.com",
        "domain:youtube.com", "domain:googlevideo.com", "domain:ytimg.com",
    )

    /** Собирает итоговый JSON-конфиг Xray. */
    fun generate(input: XrayConfigInput): String {
        // VLESS+Reality inbound (типизированный → сериализуем в JsonElement)
        val vlessInbound = XrayInbound(
            port = input.port,
            settings = XrayInboundSettings(
                clients = input.clients.map {
                    // email = "key-${uuid}" — уникальный тег для per-key статистики
                    XrayInboundClient(
                        id = it.uuid,
                        email = it.email ?: "key-${it.uuid}",
                    )
                },
            ),
            streamSettings = XrayStreamSettings(
                realitySettings = XrayRealitySettings(
                    dest = "${input.realitySni}:443",
                    serverNames = listOf(input.realitySni),
                    privateKey = input.realityKeyPair.privateKey,
                    shortIds = input.shortIds,
                ),
            ),
        )

        // api inbound (dokodemo-door) — для опроса статистики TrafficMonitor'ом
        val apiInbound: JsonObject = buildJsonObject {
            put("tag", "api-in")
            put("listen", "127.0.0.1")
            put("port", input.apiPort)
            put("protocol", "dokodemo-door")
            putJsonObject("settings") {
                put("address", "127.0.0.1")
            }
        }

        val config = XrayConfig(
            log = XrayLog(),
            inbounds = listOf(
                json.encodeToJsonElement(XrayInbound.serializer(), vlessInbound),
                apiInbound,
            ),
            outbounds = buildOutbounds(input.upstreamProxy),
            routing = XrayRouting(rules = buildRules()),
        )

        return json.encodeToString(config)
    }

    // ───────────────────────────────────────────────────────────────
    //  Outbounds
    // ───────────────────────────────────────────────────────────────

    private fun buildOutbounds(upstream: UpstreamProxy?): List<JsonObject> {
        val direct = buildJsonObject {
            put("tag", "direct")
            put("protocol", "freedom")
            putJsonObject("settings") { put("domainStrategy", "UseIPv4") }
        }
        val block = buildJsonObject {
            put("tag", "block")
            put("protocol", "blackhole")
            putJsonObject("settings") {}
        }
        // api outbound — обслуживает запросы `xray api stats`
        val api = buildJsonObject {
            put("tag", "api")
            put("protocol", "api")
            putJsonObject("settings") {}
        }
        val proxy = upstream?.let { buildUpstreamOutbound(it) }
            ?: buildJsonObject {
                put("tag", "proxy")
                put("protocol", "freedom")
                putJsonObject("settings") { put("domainStrategy", "UseIPv4") }
            }
        return listOf(direct, proxy, block, api)
    }

    private fun buildUpstreamOutbound(u: UpstreamProxy): JsonObject = when (u.protocol) {
        "vless" -> buildJsonObject {
            put("tag", "proxy")
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    add(buildJsonObject {
                        put("address", u.address)
                        put("port", u.port)
                        putJsonArray("users") {
                            add(buildJsonObject {
                                put("id", u.userId ?: "")
                                put("encryption", u.encryption ?: "none")
                            })
                        }
                    })
                }
            }
        }
        "socks" -> buildJsonObject {
            put("tag", "proxy")
            put("protocol", "socks")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("address", u.address)
                        put("port", u.port)
                        if (!u.userId.isNullOrEmpty()) {
                            putJsonArray("users") {
                                add(buildJsonObject {
                                    put("user", u.userId)
                                    put("pass", u.encryption ?: "")
                                })
                            }
                        }
                    })
                }
            }
        }
        else -> buildJsonObject {
            put("tag", "proxy")
            put("protocol", "freedom")
            putJsonObject("settings") { put("domainStrategy", "UseIPv4") }
        }
    }

    // ───────────────────────────────────────────────────────────────
    //  Routing rules
    // ───────────────────────────────────────────────────────────────

    private fun buildRules(): List<XrayRule> = listOf(
        // 0) api inbound → api outbound (опрос статистики)
        XrayRule(inboundTag = listOf("api-in"), outboundTag = "api"),
        // 1) Российские домены → напрямую
        XrayRule(domain = listOf("geosite:ru"), outboundTag = "direct"),
        // 2) Российские IP → напрямую
        XrayRule(ip = listOf("geoip:ru"), outboundTag = "direct"),
        // 3) Заблокированные домены → через прокси
        XrayRule(domain = blockedDomains(), outboundTag = "proxy"),
        // 4) Реклама → блок
        XrayRule(domain = listOf("geosite:category-ads"), outboundTag = "block"),
        // 5) Всё остальное → напрямую
        XrayRule(network = "tcp,udp", outboundTag = "direct"),
    )

    // ───────────────────────────────────────────────────────────────
    //  Helpers
    // ───────────────────────────────────────────────────────────────

    private fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}
