# 🌉 MineBridge - High-Performance Minecraft Virtual LAN VPN

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="120" height="120" alt="MineBridge Logo" />
</p>

<p align="center">
  <b>Ultra-Lightweight (2.11 MB) & High-Speed Virtual LAN Solution for Minecraft Multiplayer & P2P Applications</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-Native_Java_%26_C++-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android Native" />
  <img src="https://img.shields.io/badge/Server-Node.js_UDP-339933?style=for-the-badge&logo=nodedotjs&logoColor=white" alt="Node.js Server" />
  <img src="https://img.shields.io/badge/APK_Size-2.11_MB-brightgreen?style=for-the-badge" alt="APK Size" />
  <img src="https://img.shields.io/badge/Security-Native_C++_Anti--Tamper-red?style=for-the-badge" alt="Security" />
</p>

---

## 🌟 Project Overview

**MineBridge** is a next-generation Virtual LAN VPN solution designed to connect Minecraft players smoothly across any network (4G / Cellular / Public Wi-Fi / Different Subnets) **without Router Port Forwarding** or **CGNAT restrictions**.

Unlike bloated alternatives on the market (30 MB - 80 MB) or initial builds (~40 MB), MineBridge is record-optimized down to **just 2.11 MB** (a **>95%** size reduction), delivering flat ping latency, zero FPS drops, and native C++ anti-tamper security.

---

## 🔥 Key Features

* 🚀 **Ultra-Lightweight Size (1.96 MB)**: Built entirely with Native Java & C++, stripped of x86/x86_64 emulators, ProGuard/R8 optimized without code obfuscation, zero WebView or bloatware libraries.
* ⚡ **Low-Latency UDP Tunneling**: Encapsulates raw IP packets directly over UDP, keeping Minecraft ping latency as low as possible.
* 🔍 **Auto LAN Discovery (Broadcast & Multicast)**: Automatically replicates and broadcasts LAN discovery packets (`255.255.255.255` & `224.0.0.0/4`) so host worlds automatically appear in the Friends tab.
* 🛡️ **Native C++ Security (Anti-Tamper & Anti-Repack)**:
  - Verifies official APK Signature at the Native C++ layer (`native-lib.cpp`), instantly terminating if the APK is decompiled or re-signed.
  - Encrypts sensitive string constants (Passwords, Keys) using Native C++ XOR obfuscation.
* 🔐 **UDP Stream Cipher Encryption**: All network traffic is encrypted using a symmetric stream cipher key derived from the shared password.
* 🛡️ **Anti-Spam & Anti-DDoS**: 5-second Client button cooldown + 1000 pps Server rate-limiting filter.
* 📌 **Persistent Virtual IP Assignment**: Binds static virtual IPs (`10.8.0.x`) to each unique `Device ID`.
* 👁️ **UI Privacy Mode**: Masking for Server IP and Username with an Eye-toggle button to prevent accidental IP leaks on livestreams.

---

## 🏗️ System Architecture

```text
[ Android Device A (10.8.0.2) ]             [ Android Device B (10.8.0.3) ]
  ├── Minecraft App                          ├── Minecraft App
  └── MineBridge App (VpnService)            └── MineBridge App (VpnService)
        │ (UDP Stream Encrypted)                   │ (UDP Stream Encrypted)
        └───────────────────► [ Node.js Server ] ◄──┘
                                (UDP Port 24710)
                                (Rate Limit & Router)
```

---

## 🛠️ Installation & Setup Guide

### 1. Server Side (Node.js)

The `server/` directory contains a zero-dependency, pure Node.js UDP VPN server (No `npm install` required):

```bash
# 1. Navigate to the server folder
cd server

# 2. Launch the server (Default Port: 24710, Password: minebridge123)
node server.js

# Launch with custom arguments or Debug Mode:
node server.js --port 24710 --pass "SecretPassword" --debug
```

> **Tip**: Run 24/7 on a Linux VPS using PM2:
> `npm install -g pm2 && pm2 start server.js --name "minebridge-server"`

### 2. Client Side (Android App)

1. Open the project in **Android Studio**.
2. Connect your Android device or start an emulator.
3. Click **Run** (`Shift + F10`) or generate a Release APK via `Build -> Build APK(s)`.
4. Open the App ➔ Go to **Settings** ➔ Enter Server Host/IP and Password ➔ Click **SAVE SETTINGS** ➔ Click **CONNECT**.

---

## 📡 Protocol Specifications

* **Transport**: UDP (Port `24710`)
* **Virtual Subnet**: `10.8.0.0/24` (Client IPs: `10.8.0.2` - `10.8.0.250`)
* **Payload Encryption**: Symmetric Stream XOR Cipher Keyed with Password

| Packet Type | Name | Payload Structure (Encrypted) |
| :--- | :--- | :--- |
| `0x01` | `AUTH_REQ` | `[0x01] + Encrypt([UserLen][User][PassLen][Pass][DeviceLen][DeviceID])` |
| `0x81` | `AUTH_RESP` | `[0x81] + Encrypt([Status Code][IP_byte1][IP_byte2][IP_byte3][IP_byte4])` |
| `0x02` | `DATA` | `[0x02] + Encrypt([Raw IP Packet Payload])` |
| `0x03` | `PING` | `[0x03] + Encrypt([Echo Payload])` |
| `0x83` | `PONG` | `[0x83] + Encrypt([Echo Payload Reply])` |

---

## 🔒 License & Credits

Developed by **KawaiiModz** for the Minecraft community.
All rights reserved.
