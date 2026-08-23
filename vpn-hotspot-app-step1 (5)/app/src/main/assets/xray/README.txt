Xray-core assets
================

Сюда нужно положить:

1. xray           — исполняемый бинарник Xray-core, скомпилированный под Android
                    (arm64-v8a / armeabi-v7a / x86_64). Менеджер XrayCoreManager
                    распакует его в filesDir/xray/ и сделает исполняемым.
                    Скачать/собрать: https://github.com/XTLS/Xray-core
                    (сборка под Android: GOOS=android GOARCH=arm64 CGO_ENABLED=1
                    + NDK, либо взять готовый из v2rayNG/Hiddify).

2. geoip.dat      — гео-база IP-адресов (для routing geoip:ru → direct)
3. geosite.dat    — гео-база доменов  (для routing geosite:ru → direct)
                    Скачать: https://github.com/Loyalsoldier/v2ray-rules-dat

XrayCoreManager.kt ищет их по путям:
  assets/xray/xray
  assets/xray/geoip.dat
  assets/xray/geosite.dat
