package com.example.myapplication.config

import java.net.URLEncoder

/**
 * Генератор VLESS-ссылок для импорта в Hiddify / happ на ПК.
 *
 * Минимальный формат (по умолчанию, как в ТЗ):
 *   vless://uuid@ip:port?type=tcp&security=reality&pbk=...&fp=chrome#MyPhoneVPN
 *
 * Опционально можно добавить sni / sid / flow — для полноценного Reality-handshake'а.
 *
 * @author ZPNK666
 */
object VlessLinkGenerator {

    /**
     * @param ip       локальный IP телефона (например 192.168.1.42)
     * @param port     порт сервера (10808)
     * @param uuid     VLESS UUID ключа
     * @param pbk      Reality public key (base64) — из [XrayConfigGenerator.generateRealityKeyPair]
     * @param sni      SNI маскировки (опционально; null → не добавляется)
     * @param shortId  Reality shortId (опционально)
     * @param flow     Reality flow (опционально; обычно "xtls-rprx-vision" для TCP)
     * @param name     имя профиля в Hiddify (fragment после #)
     *
     * @return готовая vless://-ссылка для вставки в Hiddify/happ на ПК.
     */
    fun generate(
        ip: String,
        port: Int,
        uuid: String,
        pbk: String,
        sni: String? = null,
        shortId: String? = null,
        flow: String? = null,
        name: String = "MyPhoneVPN",
    ): String {
        // Параметры query. pbk оставляем raw-base64 (как в реальных ссылках,
        // Hiddify/happ корректно парсят + и / в значении).
        val params = mutableListOf<Pair<String, String>>()
        params += "type" to "tcp"
        params += "security" to "reality"
        params += "pbk" to pbk
        params += "fp" to "chrome"
        if (!sni.isNullOrEmpty())       params += "sni" to sni
        if (!shortId.isNullOrEmpty())   params += "sid" to shortId
        if (!flow.isNullOrEmpty())      params += "flow" to flow

        val query = params.joinToString("&") { (k, v) -> "$k=$v" }
        // Fragment (#name) кодируем — на случай пробелов/кириллицы
        val tag = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        return "vless://$uuid@$ip:$port?$query#$tag"
    }
}
