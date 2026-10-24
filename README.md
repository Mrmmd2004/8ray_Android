<div align="center">

# 8ray for Android

**[English](#english) · [فارسی](#فارسی)**

[![Telegram](https://img.shields.io/badge/Telegram-@Clubapp8-26A5E4?logo=telegram&logoColor=white)](https://t.me/Clubapp8)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)

</div>

---

<a id="english"></a>

# 8ray — English

A V2Ray client for Android with the look of **8 VPN**. V2Ray only: no Aether, Psiphon or Tor. The engine is **sing-box** (libbox), so one app runs VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard and more.

## Features

**Configs**
- Add by paste, QR scan, file, a link tapped in a browser or chat, or "Share to 8ray".
- Protocols: VLESS (TLS, Reality, Vision), VMess, Trojan, Shadowsocks (also 2022), Hysteria2 (port hopping), Hysteria, TUIC, WireGuard, SOCKS5, and a raw sing-box outbound as JSON.
- Transports: TCP (HTTP header), WebSocket (early data), gRPC, HTTP/2, HTTPUpgrade. Not supported by sing-box: xhttp / splithttp and mKCP (such links are rejected with a clear message).
- Search, filter (all / favorites / manual / per subscription), sort, favorites, rename, edit the link, show QR, show the sing-box JSON, copy, delete, remove dead ones.
- **Chain**: dial a config through another config.
- Backup and restore (configs, subscriptions and settings) as one file.

**Subscriptions**
- Plain and base64 lists, usage and expiry bar, auto update on app start every N hours, update through the tunnel when it is up.

**Connection**
- **VPN mode** (whole device, sing-box on a TUN) or **Proxy only** (local SOCKS5 + HTTP on one port, also open in VPN mode; optional LAN / hotspot sharing with username and password).
- **Live switching**: tap another config while connected and it changes at once, no reconnect.
- **Auto (fastest)**: a url-test group that always uses the best of your configs.
- **Failover**: when the current config stops answering, the next best one is used without dropping the VPN.
- Network change (Wi-Fi to mobile data) is handled without a full reconnect.
- Exit IP and country, live speed, session and total traffic, delay test and speed test.

**Delay tests**
- *Real delay* sends a request through the config itself. With the VPN on it uses the running core; with the VPN off a short-lived core without a VPN icon is used. Quick TCP test is also there.

**Routing and DNS**
- Bypass LAN, bypass **Iran / China / Russia** (country domain + IP list + site list, downloaded once and updatable), own proxy / direct / block lists (domain, suffix, keyword, regexp, IP, CIDR).
- Block QUIC, domain sniffing, remote DNS (DoH / DoT / TCP / UDP), direct DNS (the DNS of your network by default, so domestic sites resolve like usual).
- TLS fragment, Mux (h2mux / smux / yamux), IPv6, TUN stack and MTU.
- Split tunneling (only selected apps / all except selected).

**App**
- Dark / light / dynamic color, English and Persian (RTL), Quick Settings tile, start on boot, notification with live speed, logs.

## How it works

```
apps -> VpnService (TUN) -> sing-box -> [chain] -> your V2Ray config -> server
                         \-> direct (LAN, country lists, direct rules)
other apps / PCs -> 127.0.0.1:10808 (mixed SOCKS5 + HTTP) -> same core
```

## Build with GitHub Actions

1. Upload this project to your repository (`Mrmmd2004/8ray_Android`).
2. Open **Actions → Build APKs → Run workflow** (or push to `main`, or push a tag like `v1.0` to also create a release).
3. Download the `8ray-apks` artifact: `8ray-armeabi-v7a.apk`, `8ray-arm64-v8a.apk`, `8ray-universal.apk`.

The first build compiles sing-box (libbox) and takes a few minutes. `SINGBOX_REF` (default `v1.13.21`) picks the sing-box version.

**Signing (optional).** Without secrets the APKs are signed with a debug key. For a stable key add the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Notes

- 8ray is AGPL-3.0 (same as the other Clubapp apps). sing-box is GPL-3.0 with a name-use clause, ZXing is Apache-2.0.
- Country lists come from SagerNet's `sing-geoip` / `sing-geosite` rule sets.
- Shadowsocks plugins (obfs-local, v2ray-plugin) depend on the sing-box build; if one does not work, use another transport.

---

<a id="فارسی"></a>

<div dir="rtl" align="right">

# 8ray — فارسی

کلاینت V2Ray برای اندروید با ظاهر **8 VPN**. فقط V2Ray: بدون Aether و Psiphon و Tor. موتور برنامه **sing-box** است، پس با یک برنامه می‌توانید VLESS، VMess، Trojan، Shadowsocks، Hysteria2، TUIC و WireGuard را اجرا کنید.

## امکانات

**کانفیگ‌ها**
- افزودن با چسباندن، اسکن QR، فایل، لینکی که در مرورگر یا چت می‌زنید، یا «اشتراک‌گذاری به 8ray».
- پروتکل‌ها: VLESS (TLS و Reality و Vision)، VMess، Trojan، Shadowsocks، Hysteria2، Hysteria، TUIC، WireGuard، SOCKS5 و اوت‌باند خام sing-box به‌صورت JSON.
- ترنسپورت‌ها: TCP، WebSocket، gRPC، HTTP/2، HTTPUpgrade. پشتیبانی‌نشده در sing-box: xhttp و mKCP (این لینک‌ها با پیام روشن رد می‌شوند).
- جستجو، فیلتر، مرتب‌سازی، علاقه‌مندی، تغییر نام، ویرایش لینک، نمایش QR، نمایش JSON، کپی، حذف، حذف کانفیگ‌های مرده.
- **زنجیره**: اتصال یک کانفیگ از مسیر کانفیگ دیگر.
- پشتیبان‌گیری و بازیابی کانفیگ‌ها و ساب‌ها و تنظیمات در یک فایل.

**ساب‌ها**
- لیست ساده و base64، نوار مصرف و انقضا، به‌روزرسانی خودکار هنگام باز شدن برنامه، دانلود از مسیر تونل وقتی وصل هستید.

**اتصال**
- **حالت VPN** (کل گوشی) یا **فقط پروکسی** (SOCKS5 و HTTP روی یک پورت؛ در حالت VPN هم باز است؛ اشتراک روی هات‌اسپات با نام کاربری و رمز).
- **تعویض زنده**: وقتی وصل هستید روی کانفیگ دیگر بزنید، بدون اتصال مجدد عوض می‌شود.
- **خودکار (سریع‌ترین)**: گروه url-test که همیشه بهترین کانفیگ را به کار می‌برد.
- **جایگزینی خودکار**: اگر کانفیگ فعلی جواب ندهد، بهترین کانفیگ بعدی بدون قطع VPN جایگزین می‌شود.
- تغییر شبکه (وای‌فای به دیتا) بدون اتصال مجدد کامل مدیریت می‌شود.
- آی‌پی و کشور خروجی، سرعت زنده، ترافیک نشست و کل، تست تأخیر و تست سرعت.

**تست تأخیر**
- *تأخیر واقعی* یک درخواست از خود کانفیگ رد می‌کند. با VPN روشن از هسته‌ی در حال اجرا و با VPN خاموش از یک هسته‌ی کوتاه‌مدت بدون آیکون VPN استفاده می‌شود. تست سریع TCP هم هست.

**مسیریابی و DNS**
- عبور مستقیم LAN و **ایران / چین / روسیه** (دامنه‌ی کشور + لیست آی‌پی + لیست سایت‌ها، یک‌بار دانلود و قابل به‌روزرسانی)، لیست‌های شخصی پروکسی / مستقیم / مسدود.
- مسدود کردن QUIC، تشخیص دامنه، DNS راه‌دور (DoH و DoT و TCP و UDP)، DNS مستقیم (به‌طور پیش‌فرض DNS خود شبکه، تا سایت‌های داخلی مثل همیشه باز شوند).
- TLS fragment، Mux، IPv6، استک و MTU تونل.
- تونل انتخابی (فقط برنامه‌های انتخابی / همه به‌جز انتخابی‌ها).

**برنامه**
- تیره / روشن / رنگ پویا، فارسی (راست‌به‌چپ) و انگلیسی، کاشی تنظیمات سریع، اجرا هنگام بوت، نوتیفیکیشن با سرعت زنده، لاگ.

## ساخت با GitHub Actions

۱. همه‌ی فایل‌ها را در مخزن (`Mrmmd2004/8ray_Android`) آپلود کنید.
۲. **Actions ← Build APKs ← Run workflow**.
۳. فایل‌های `8ray-armeabi-v7a.apk` و `8ray-arm64-v8a.apk` و `8ray-universal.apk` را از `8ray-apks` دانلود کنید.

بیلد اول چند دقیقه طول می‌کشد چون sing-box (libbox) کامپایل می‌شود.

</div>
