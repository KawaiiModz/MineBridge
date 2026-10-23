/**
 * MineBridge Node.js VPN Server
 * Dedicated UDP Server for MineBridge Android Client
 */

const dgram = require('dgram');
const fs = require('fs');
const path = require('path');

// Packet Type Constants
const PACKET_TYPE_AUTH_REQ  = 0x01;
const PACKET_TYPE_AUTH_RESP = 0x81;
const PACKET_TYPE_DATA      = 0x02;
const PACKET_TYPE_PING      = 0x03;
const PACKET_TYPE_PONG      = 0x83;

// Helper to encrypt/decrypt UDP payload in place using symmetric XOR cipher key
function cryptBuffer(buf, offset, length, passStr) {
    if (!passStr || length <= 0) return;
    const key = Buffer.from(passStr, 'utf8');
    for (let i = 0; i < length; i++) {
        buf[offset + i] ^= (key[i % key.length] ^ (i & 0xFF));
    }
}

// Parse Command Line Arguments
function parseArgs() {
    const args = {
        port: 24710,
        pass: 'minebridge123',
        file: path.join(__dirname, 'clients.json'),
        debug: false
    };

    for (let i = 2; i < process.argv.length; i++) {
        const arg = process.argv[i];
        if ((arg === '-p' || arg === '--port') && process.argv[i + 1]) {
            args.port = parseInt(process.argv[++i], 10);
        } else if ((arg === '-pass' || arg === '--pass') && process.argv[i + 1]) {
            args.pass = process.argv[++i];
        } else if ((arg === '-f' || arg === '--file') && process.argv[i + 1]) {
            args.file = path.resolve(process.argv[++i]);
        } else if (arg === '-d' || arg === '--debug') {
            args.debug = true;
        }
    }
    return args;
}

const config = parseArgs();

class MineBridgeServer {
    constructor(port, password, jsonFile, debug) {
        this.port = port;
        this.password = password;
        this.jsonFile = jsonFile;
        this.debug = debug;

        this.subnetBase = '10.8.0.';
        this.ipCounter = 2; // Start allocating from 10.8.0.2

        this.records = new Map();         // DeviceID -> ClientRecord
        this.sessions = new Map();        // VirtualIP -> ClientSession
        this.addrToIP = new Map();        // "ip:port" -> VirtualIP

        this.authRateLimit = new Map();   // IP -> timestamp
        this.packetRateLimit = new Map(); // IP -> { count, resetTime }

        this.server = dgram.createSocket('udp4');
        this.loadJSON();
    }

    logDebug(...args) {
        if (this.debug) {
            console.log(...args);
        }
    }

    checkAuthRateLimit(ip) {
        const now = Date.now();
        const lastAuth = this.authRateLimit.get(ip) || 0;
        if (now - lastAuth < 3000) { // 3 seconds cooldown per IP for AUTH
            return false;
        }
        this.authRateLimit.set(ip, now);
        return true;
    }

    checkPacketRateLimit(ip) {
        const now = Date.now();
        let rate = this.packetRateLimit.get(ip);
        if (!rate || now > rate.resetTime) {
            rate = { count: 1, resetTime: now + 1000 };
            this.packetRateLimit.set(ip, rate);
            return true;
        }
        rate.count++;
        if (rate.count > 1000) { // Max 1000 packets per second per IP
            return false;
        }
        return true;
    }

    loadJSON() {
        try {
            if (fs.existsSync(this.jsonFile)) {
                const raw = fs.readFileSync(this.jsonFile, 'utf8');
                const list = JSON.parse(raw);
                if (Array.isArray(list)) {
                    for (const rec of list) {
                        this.records.set(rec.device_id, rec);
                    }
                    this.logDebug(`[CONFIG] Loaded ${list.length} saved client records from '${this.jsonFile}'`);
                }
            } else {
                this.logDebug(`[CONFIG] Creating new JSON config file at '${this.jsonFile}'`);
            }
        } catch (err) {
            console.error(`[CONFIG ERROR] Failed to load ${this.jsonFile}:`, err.message);
        }
    }

    saveJSON() {
        try {
            const list = Array.from(this.records.values());
            fs.writeFileSync(this.jsonFile, JSON.stringify(list, null, 2), 'utf8');
        } catch (err) {
            console.error(`[CONFIG ERROR] Failed to save ${this.jsonFile}:`, err.message);
        }
    }

    allocateVirtualIP(deviceId) {
        if (this.records.has(deviceId)) {
            return this.records.get(deviceId).virtual_ip;
        }

        // Collect all currently assigned IPs in clients.json
        const usedIps = new Set();
        for (const rec of this.records.values()) {
            usedIps.add(rec.virtual_ip);
        }

        // Find the first available IP from 10.8.0.2 to 10.8.0.250
        for (let i = 2; i <= 250; i++) {
            const candidateIp = `${this.subnetBase}${i}`;
            if (!usedIps.has(candidateIp)) {
                return candidateIp;
            }
        }

        return `${this.subnetBase}254`;
    }

    start() {
        this.server.on('error', (err) => {
            console.error(`[SERVER ERROR] Socket error:\n${err.stack}`);
            this.server.close();
        });

        this.server.on('message', (msg, rinfo) => {
            if (msg.length < 1) return;

            // Anti-DDoS Packet Rate Limit
            if (!this.checkPacketRateLimit(rinfo.address)) {
                return;
            }

            const packetType = msg[0];
            const senderKey = `${rinfo.address}:${rinfo.port}`;

            // Decrypt UDP payload in place (skipping byte 0 packet header)
            cryptBuffer(msg, 1, msg.length - 1, this.password);

            this.logDebug(`[UDP RECV] ${msg.length} bytes from ${senderKey} (Type: 0x${packetType.toString(16).padStart(2, '0')})`);

            switch (packetType) {
                case PACKET_TYPE_AUTH_REQ:
                    this.handleAuth(msg.subarray(1), rinfo);
                    break;

                case PACKET_TYPE_DATA:
                    this.handleData(msg.subarray(1), rinfo);
                    break;

                case PACKET_TYPE_PING:
                    this.handlePing(msg.subarray(1), rinfo);
                    break;

                default:
                    this.logDebug(`[UDP UNKNOWN] Received unknown packet 0x${packetType.toString(16)} from ${senderKey}`);
                    break;
            }
        });

        this.server.on('listening', () => {
            const addr = this.server.address();
            console.log('==========================================');
            console.log(`  MineBridge Node.js Encrypted VPN Server running on UDP :${addr.port}`);
            console.log(`  Shared Password: '${this.password}'`);
            console.log(`  JSON Storage:    '${this.jsonFile}'`);
            console.log(`  Debug Mode:      ${this.debug ? 'ENABLED (Verbose logs)' : 'DISABLED (Use -d or --debug to show all logs)'}`);
            console.log('==========================================');
        });

        this.server.bind(this.port);

        // Periodically cleanup inactive sessions every 30s
        setInterval(() => this.cleanupRoutine(), 30000);
    }

    handleAuth(data, rinfo) {
        const senderKey = `${rinfo.address}:${rinfo.port}`;

        // Anti-Spam Auth Rate Limit
        if (!this.checkAuthRateLimit(rinfo.address)) {
            console.log(`[RATE LIMIT] Dropped spammed AUTH request from ${rinfo.address}`);
            return;
        }

        if (data.length < 4) {
            this.logDebug(`[AUTH ERROR] Payload too short (${data.length} bytes) from ${senderKey}`);
            return;
        }

        try {
            // Read Username
            const userLen = data.readUInt16BE(0);
            let offset = 2;
            if (data.length < offset + userLen + 2) {
                this.logDebug(`[AUTH ERROR] Invalid username length from ${senderKey}`);
                return;
            }
            const username = data.toString('utf8', offset, offset + userLen);
            offset += userLen;

            // Read Password
            const passLen = data.readUInt16BE(offset);
            offset += 2;
            if (data.length < offset + passLen) {
                this.logDebug(`[AUTH ERROR] Invalid password length from ${senderKey}`);
                return;
            }
            const password = data.toString('utf8', offset, offset + passLen);
            offset += passLen;

            // Read Device ID
            let deviceId = 'unknown_device';
            if (data.length >= offset + 2) {
                const devLen = data.readUInt16BE(offset);
                offset += 2;
                if (data.length >= offset + devLen) {
                    deviceId = data.toString('utf8', offset, offset + devLen);
                }
            }

            this.logDebug(`[AUTH CHECK] User: '${username}' | Pass: '${password}' | Device: '${deviceId}' from ${senderKey}`);

            // Verify Password
            if (password !== this.password) {
                console.log(`[AUTH FAILED] User '${username}' provided WRONG password from ${senderKey}`);
                const failResp = Buffer.from([PACKET_TYPE_AUTH_RESP, 0x01, 0, 0, 0, 0]);
                cryptBuffer(failResp, 1, failResp.length - 1, this.password);
                this.server.send(failResp, rinfo.port, rinfo.address);
                return;
            }

            const virtualIp = this.allocateVirtualIP(deviceId);
            const now = new Date();

            let firstConnected = now.toISOString();
            if (this.records.has(deviceId)) {
                firstConnected = this.records.get(deviceId).first_connected;
            }

            const record = {
                device_id: deviceId,
                username: username,
                virtual_ip: virtualIp,
                first_connected: firstConnected,
                last_seen: now.toISOString()
            };

            this.records.set(deviceId, record);
            this.saveJSON();

            const session = {
                device_id: deviceId,
                username: username,
                virtual_ip: virtualIp,
                address: rinfo.address,
                port: rinfo.port,
                lastSeen: Date.now()
            };

            this.sessions.set(virtualIp, session);
            this.addrToIP.set(senderKey, virtualIp);

            // Always show client connections in normal mode!
            console.log(`[CONNECTED] User '${username}' [ID: ${deviceId}] connected -> Assigned IP: ${virtualIp} (from ${senderKey})`);

            // Build Success Auth Response: [0x81, 0x00, ip1, ip2, ip3, ip4]
            const ipBytes = virtualIp.split('.').map(Number);
            const successResp = Buffer.from([PACKET_TYPE_AUTH_RESP, 0x00, ...ipBytes]);
            cryptBuffer(successResp, 1, successResp.length - 1, this.password);

            this.server.send(successResp, rinfo.port, rinfo.address);

        } catch (err) {
            console.error(`[AUTH EXCEPTION] ${err.message} from ${senderKey}`);
        }
    }

    handleData(data, rinfo) {
        if (data.length < 20) return; // Minimum IPv4 header size

        const senderKey = `${rinfo.address}:${rinfo.port}`;

        // Update lastSeen timestamp
        const vip = this.addrToIP.get(senderKey);
        if (vip) {
            const sess = this.sessions.get(vip);
            if (sess) {
                sess.lastSeen = Date.now();
                if (this.records.has(sess.device_id)) {
                    this.records.get(sess.device_id).last_seen = new Date().toISOString();
                }
            }
        }

        // Extract Destination IPv4 address from IP header (bytes 16..19)
        const dstIp = `${data[16]}.${data[17]}.${data[18]}.${data[19]}`;
        const firstOctet = data[16];

        this.logDebug(`[DATA] ${data.length} bytes from ${senderKey} (${vip || 'Unknown'}) -> Target IP: ${dstIp}`);

        // Wrap packet with Data Packet header: [0x02][raw IP packet]
        const outPacket = Buffer.concat([Buffer.from([PACKET_TYPE_DATA]), data]);

        // Encrypt payload before sending out to target client
        cryptBuffer(outPacket, 1, outPacket.length - 1, this.password);

        // Check for Broadcast (255.255.255.255, 10.8.0.255) or Multicast (224.0.0.0 - 239.255.255.255)
        const isBroadcastOrMulticast = dstIp === '255.255.255.255' || dstIp === '10.8.0.255' || (firstOctet >= 224 && firstOctet <= 239);

        if (isBroadcastOrMulticast) {
            // Broadcast/Multicast packet to all connected clients except sender
            for (const sess of this.sessions.values()) {
                if (`${sess.address}:${sess.port}` !== senderKey) {
                    this.server.send(outPacket, sess.port, sess.address);
                }
            }
        } else {
            // Unicast packet to destination client
            const targetSess = this.sessions.get(dstIp);
            if (targetSess) {
                this.server.send(outPacket, targetSess.port, targetSess.address);
            }
        }
    }

    handlePing(data, rinfo) {
        const senderKey = `${rinfo.address}:${rinfo.port}`;

        // Update lastSeen timestamp
        const vip = this.addrToIP.get(senderKey);
        if (vip) {
            const sess = this.sessions.get(vip);
            if (sess) {
                sess.lastSeen = Date.now();
                if (this.records.has(sess.device_id)) {
                    this.records.get(sess.device_id).last_seen = new Date().toISOString();
                }
            }
        }

        this.logDebug(`[PING] Ping from ${senderKey} (${vip || 'Unknown'})`);

        // Pong response: [0x83][echo payload]
        const pongPacket = Buffer.concat([Buffer.from([PACKET_TYPE_PONG]), data]);
        cryptBuffer(pongPacket, 1, pongPacket.length - 1, this.password);
        this.server.send(pongPacket, rinfo.port, rinfo.address);
    }

    cleanupRoutine() {
        const now = Date.now();

        // Clean stale sessions (60s timeout)
        for (const [vip, sess] of this.sessions.entries()) {
            if (now - sess.lastSeen > 60000) {
                console.log(`[DISCONNECTED] Client '${sess.username}' (${sess.virtual_ip}) timed out / disconnected`);
                this.sessions.delete(vip);
                this.addrToIP.delete(`${sess.address}:${sess.port}`);
            }
        }

        // Clean old rate limit entries
        for (const [ip, timestamp] of this.authRateLimit.entries()) {
            if (now - timestamp > 10000) this.authRateLimit.delete(ip);
        }
        for (const [ip, rate] of this.packetRateLimit.entries()) {
            if (now > rate.resetTime + 5000) this.packetRateLimit.delete(ip);
        }
    }
}

// Start Server
const server = new MineBridgeServer(config.port, config.pass, config.file, config.debug);
server.start();
