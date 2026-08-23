# 📱 VpnHotspotApp

Android-приложение на Kotlin + Jetpack Compose, которое работает как **локальный VPN-сервер** (Xray-core, VLESS + XTLS-Reality) и раздаёт интернет на ПК. Ключ вставляется в Hiddify/happ на компьютере.

## Архитектура

```
 ПК (Hiddify/happ)  ── VLESS+Reality ──►  Телефон (Xray inbound :10808)  ──►  Интернет
                                            ▲
                          ForegroundService + WakeLock + WifiLock держат процесс живым
```

## Что готово (Шаг 1)

- ✅ Структура Gradle-проекта (`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`)
- ✅ Зависимости: Compose BOM + Material3, Lifecycle, **Room (KSP)**, Coroutines, Navigation, Serialization, DataStore, WorkManager
- ✅ `AndroidManifest.xml` со всеми разрешениями: `INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, BIND_VPN_SERVICE, ACCESS_WIFI_STATE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED`
- ✅ ForegroundService-компонент (`foregroundServiceType="dataSync"` для Android 14+)
- ✅ BootReceiver для авто-старта
- ✅ Базовая Compose-тема + каркас `MainActivity`
- ✅ Иконки (adaptive launcher + monochrome notification icon)

## Как открыть

1. Скачай и распакуй архив.
2. `Android Studio → Open →` выбери папку `VpnHotspotApp/`.
3. Дождись Gradle Sync (скачает Gradle 8.9 и зависимости).
4. `Run ▶` — приложение соберётся и покажет экран-заглушку «Шаг 1: каркас готов».

> ⚠️ Native Xray-core (`libxray.so`) и гео-базы (`geoip.dat`, `geosite.dat`) будут добавлены на Шаге 3. Положи их в `app/src/main/jniLibs/<abi>/` и `app/src/main/assets/xray/` соответственно.

## Roadmap

| Шаг | Содержание | Статус |
|-----|------------|--------|
| 1 | Структура + Gradle + Manifest | ✅ готово |
| 2 | Room: entities, DAOs, AppDatabase, repositories | ⏳ следующий |
| 3 | ForegroundService, XrayCoreRunner, TCP-сервер, TrafficMonitor, WakeLock/WifiLock | ⏳ |
| 4 | UI: Dashboard, экран ключей, компоненты, навигация | ⏳ |
| 5 | Генератор конфига Xray + VLESS-ссылки + Routing (geosite:ru/geoip:ru) | ⏳ |

## Стек

- Kotlin 2.0.21 / Coroutines 1.9
- Jetpack Compose (BOM 2024.10.01) + Material3
- Room 2.6.1 (KSP)
- Navigation-Compose 2.8.4
- DataStore-Preferences 1.1.1
- WorkManager 2.10
- kotlinx-serialization-json 1.7.3
- compileSdk 35 / minSdk 26 / targetSdk 35
