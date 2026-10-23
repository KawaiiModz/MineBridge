# 🌉 MineBridge Encrypted VPN Server (Node.js)

Self-hosted UDP VPN Server for the **MineBridge Android Application**, supporting Virtual LAN Tunneling for Minecraft and P2P networking with **UDP Payload Stream Cipher Encryption**.

---

## 🌟 1. Server Features (`server.js`)

The **MineBridge Node.js Server** is optimized for high throughput, low latency, and security:

* **UDP Stream Cipher Encryption**: All UDP packets (Auth details, Raw IP packets, Pings) are symmetrically encrypted using a secret key derived from the connection password (`password`).
* **Nanosecond Performance & Zero Overhead**: In-place byte array cipher execution ensures nanosecond processing without adding a single extra byte to the packet size (Zero MTU fragmentation).
* **Zero External Dependencies**: Uses built-in Node.js modules (`dgram`, `fs`, `path`). No `npm install` required.
* **Public IP & NAT Traversal**: Handles ICMP Port Unreachable and NAT Traversal seamlessly across Windows & Linux.
* **Persistent Virtual IP Binding**: Binds static virtual IPs (`10.8.0.x`) to unique `Device ID`s stored in `clients.json`.
* **Anti-DDoS & Rate Limiting**: Enforces 3s auth cooldown and 1000 pps packet rate limits per IP.

---

## 📋 2. System Requirements
* **Node.js** v14.0 or higher ([Download Node.js](https://nodejs.org/)).

---

## 🚀 3. How to Run the Server

### A. Default Mode (Connection logs only)
In default mode, the server logs client connections (`[CONNECTED]`), disconnects (`[DISCONNECTED]`), and auth failures (`[AUTH FAILED]`).

```bash
# Navigate to the server folder
cd server

# Launch server
node server.js
# Or:
npm start
```

### B. Debug Mode (Verbose logging)
Add the `-d` or `--debug` flag for verbose packet logging (Encrypted packet info, Data forwarding, Ping/Pong):

```bash
node server.js -d
# Or:
node server.js --debug
```

### C. Full Custom Arguments
```bash
node server.js --port 24710 --pass "SecretPassword123" --file "clients.json" --debug
```

* `--port` / `-p`: UDP listening port (Default: `24710`).
* `--pass` / `-pass`: Shared password for clients (Default: `minebridge123`).
* `--file` / `-f`: Path to JSON record storage (Default: `clients.json`).
* `--debug` / `-d`: Enable verbose packet logging.

---

## 🔄 4. Running 24/7 with PM2 (VPS / Linux Server)

For background 24/7 uptime and auto-restart on system reboot, use **PM2**:

```bash
# Install PM2 globally
npm install -g pm2

# Start server process with PM2
pm2 start server.js --name "minebridge-server" -- --port 24710 --pass "SecretPassword123"

# Save PM2 state & enable startup on reboot
pm2 save
pm2 startup
```

---

## 🛡️ 5. Firewall & Router Port Forwarding

Make sure to open UDP Port `24710` on your server's firewall or router:

### A. Windows Firewall (PowerShell Admin)
```powershell
New-NetFirewallRule -DisplayName "MineBridge UDP 24710" -Direction Inbound -LocalPort 24710 -Protocol UDP -Action Allow
```

### B. Linux VPS Firewall (UFW)
```bash
sudo ufw allow 24710/udp
sudo ufw reload
```

---

## 📜 6. Console Log Output

```text
==========================================
  MineBridge Node.js Encrypted VPN Server running on UDP :24710
  Shared Password: 'minebridge123'
  JSON Storage:    'C:\...\server\clients.json'
  Debug Mode:      DISABLED (Use -d or --debug to show all logs)
==========================================
[CONNECTED] User 'Steve' [ID: d533bfb617df9911] connected -> Assigned IP: 10.8.0.2 (from 113.161.x.x:54321)
[DISCONNECTED] Client 'Steve' (10.8.0.2) timed out / disconnected
```

---

## 📡 7. Protocol Specification

* **Transport Protocol**: UDP (Encrypted Payload using Stream Cipher)
* **Virtual Subnet**: `10.8.0.0/24` (Client IP Range: `10.8.0.2` - `10.8.0.250`)

| Packet Type | Name | Encrypted Payload Structure |
| :--- | :--- | :--- |
| `0x01` | `AUTH_REQ` | `[0x01] + Encrypt([UserLen][User][PassLen][Pass][DeviceLen][DeviceID])` |
| `0x81` | `AUTH_RESP` | `[0x81] + Encrypt([Status Code][IP_byte1][IP_byte2][IP_byte3][IP_byte4])` |
| `0x02` | `DATA` | `[0x02] + Encrypt([Raw IP Packet Payload])` |
| `0x03` | `PING` | `[0x03] + Encrypt([Echo Payload])` |
| `0x83` | `PONG` | `[0x83] + Encrypt([Echo Payload Reply])` |

---

## 📂 8. Record Storage (`clients.json`)

```json
[
  {
    "device_id": "d533bfb617df9911",
    "username": "ItzKawaiiMC",
    "virtual_ip": "10.8.0.2",
    "first_connected": "2026-10-21T15:29:33.885Z",
    "last_seen": "2026-10-21T15:30:12.110Z"
  }
]
```
