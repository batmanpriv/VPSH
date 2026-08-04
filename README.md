# VPSH - Virtual Private Share Hub

**Version 3.1.0**

VPSH is an Android application that turns your device into a powerful network sharing hub. It supports both **Proxy Mode** (HTTP/SOCKS5) and **Full VPN Mode** (NAT routing), with advanced features like client management, bandwidth limiting, and the innovative **BatProxy** distributed proxy system.

---

## Table of Contents
1. [Overview](#overview)
2. [Installation](#installation)
3. [First Launch](#first-launch)
4. [Quick Start Guide](#quick-start-guide)
5. [Modes of Operation](#modes-of-operation)
   - [Proxy Mode](#proxy-mode)
   - [Full Mode (VPN NAT)](#full-mode-vpn-nat)
6. [Main Dashboard](#main-dashboard)
   - [Status & Controls](#status--controls)
   - [Client Management](#client-management)
   - [QR Code Sharing](#qr-code-sharing)
   - [Tethering Support](#tethering-support)
7. [BatProxy Tab](#batproxy-tab)
   - [What is BatProxy?](#what-is-batproxy)
   - [Adding Workers](#adding-workers)
   - [Worker Health & Stats](#worker-health--stats)
   - [DNS Configuration](#dns-configuration)
8. [Settings](#settings)
   - [Port Configuration](#port-configuration)
   - [Authentication](#authentication)
   - [Upstream Proxy Chaining](#upstream-proxy-chaining)
   - [Health Monitoring](#health-monitoring)
   - [Full Mode Settings](#full-mode-settings)
   - [Interface Overrides](#interface-overrides)
9. [Logs & Health Checks](#logs--health-checks)
10. [Quick Settings Tile](#quick-settings-tile)
11. [Client Setup Guide](#client-setup-guide)
    - [Connecting via HTTP Proxy](#connecting-via-http-proxy)
    - [Connecting via SOCKS5](#connecting-via-socks5)
    - [Using the QR Code](#using-the-qr-code)
    - [Using BatProxy Clients](#using-batproxy-clients)
12. [Troubleshooting](#troubleshooting)
13. [FAQ](#faq)

---

## Overview

VPSH allows you to share your internet connection with other devices on your network. It's ideal for:
- Sharing a VPN connection with other devices
- Creating a secure proxy server on your phone
- Managing and monitoring connected clients
- Using distributed proxy workers via BatProxy

The app requires **root access** for Full Mode (VPN NAT routing). Proxy mode works without root.

---

## Installation

1. Download the APK from the official source
2. Enable "Install from unknown sources" in your Android settings
3. Install the APK
4. Grant notification permission when prompted (Android 13+)

---

## First Launch

When you first open VPSH:
1. The app will check for root access
2. You'll see the Dashboard with status information
3. The app is ready to start in **Proxy Mode** by default

---

## Quick Start Guide

### Proxy Mode (No Root Required)
1. Open the app
2. Ensure you're connected to the internet (Wi-Fi or mobile data)
3. Tap **START** on the Dashboard
4. The HTTP proxy will start on port **8888**
5. Connect other devices using:
   - Proxy: `http://[your-phone-ip]:8888`
   - SOCKS5 (if enabled): `[your-phone-ip]:1080`

### Full Mode (Requires Root)
1. Enable root access
2. Make sure your VPN is connected
3. On the Dashboard, select **FULL** mode
4. Tap **START**
5. The app will route all hotspot traffic through your VPN

---

## Modes of Operation

### Proxy Mode
- **Root required:** No
- **How it works:** Runs an HTTP and/or SOCKS5 proxy server
- **Use case:** Sharing internet with devices on the same network
- **Features:**
  - HTTP proxy on configurable port (default: 8888)
  - Optional SOCKS5 proxy (default: 1080)
  - Authentication support (username/password)
  - Upstream proxy chaining
  - Kill switch (stops proxy when VPN disconnects)
  - Client blocking and bandwidth limiting
- **Limitation:** Cannot share traffic from system-level VPNs (like Viva) because it cannot intercept traffic already routed to the VPN interface. [See FAQ for details](#faq---why-some-vpns-cant-be-shared-without-root).

### Full Mode (VPN NAT)
- **Root required:** Yes
- **How it works:** Routes all traffic from your hotspot through the VPN using iptables
- **Use case:** Sharing a VPN connection with multiple devices
- **Features:**
  - Full NAT routing through VPN interface
  - IPv6 leak protection
  - Client bandwidth limiting via tc (traffic control)
  - Health monitoring and auto-restart
- **Advantage:** Can share traffic from **any** VPN, including system-level apps like Viva, as it manipulates the network routing at the system level.

---

## Main Dashboard

### Status & Controls
- **Status indicator:** Shows current state (Stopped/Starting/Running/Paused/Error)
- **Mode selector:** Switch between PROXY and FULL modes
- **Start/Stop button:** Controls the service
- **Interface info:** Shows detected hotspot and VPN interfaces

### Client Management
When the service is running, connected clients appear in the list with:
- **IP address** and MAC address
- **Nickname** (customizable per device)
- **Block/Unblock** toggle
- **Bandwidth limit** setting (Kbps)

**Client controls:**
- Tap the **pencil icon** to rename a device
- Tap the **clock icon** to set a bandwidth limit
- Tap the **checkmark/block** icon to block or unblock a device

### QR Code Sharing
In Proxy Mode, tap the QR button to display a QR code containing:
```
PROXY http://[username:password@][phone-ip]:[port]
```
Scan this with another device to instantly configure proxy settings.

### Tethering Support
The Dashboard includes a tethering helper section:
- Detects Wi-Fi, USB, or Bluetooth tethering
- USB tether enable button (root required)
- Opens system tethering settings
- Shows USB cable connection status

---

## BatProxy Tab

### What is BatProxy?

BatProxy is an **enterprise-grade, intelligent proxy tunnel** that turns your Android device into a resilient gateway. Instead of relying on a single proxy server, it uses a pool of distributed "workers" – typically deployed as **Cloudflare Workers** – to route your traffic.

The system intelligently:
1. **Routes connections** through the healthiest, fastest available worker.
2. **Monitors worker performance** using metrics like latency (RTT) and success rate via Exponentially Weighted Moving Averages (EWMA).
3. **Automatically fails over** to other workers if one becomes slow or unresponsive.
4. **Reopens connections** through workers that have recovered from failure, thanks to a circuit-breaker pattern with exponential backoff.

For a complete technical deep-dive into the architecture, worker selection algorithms, and deployment, visit the official project repository:  
👉 **[BatProxy on GitHub](https://github.com/batmanpriv/BatProxy)**

**How it Works (Briefly):**

1. **Workers** are servers (running on Cloudflare's edge network) that accept WebSocket connections.
2. **The Android app (Client)** connects to these workers using a secure, HMAC-authenticated handshake.
3. When you or a connected client makes a request, the app selects the optimal worker based on a real-time score.
4. Data is relayed through the worker to the target server, with built-in optimizations like **data coalescing** to reduce overhead.

This setup provides exceptional reliability, low latency through Cloudflare's global network, and automatic recovery from failures.

### Adding Workers
1. Go to the **BatProxy** tab
2. Tap the **+** button
3. Enter worker URL and password (the same `PASSWD` you set on your Cloudflare Worker)
4. Tap Save

**Worker URL format:** `wss://your-worker-name.workers.dev` (Secure WebSocket)

### Worker Health & Stats
Each worker displays:
- **Status:**
  - **Closed:** Healthy and available
  - **Half-open:** Recovering from a failure, under probation
  - **Open:** Failed, in a cooldown period (excluded from selection)
- **Cooldown:** Time remaining before the worker can be retried
- **RTT:** Average round-trip time in milliseconds
- **Score:** A real-time performance score (higher is better)
- **Active connections:** Current connections through this worker
- **OK/Fail:** Success and failure counts

**Health monitoring:**
- Automatic health checks every 30 seconds
- Exponential backoff for failed workers (cooldown doubles on each failure, up to 120s)
- Success rate tracking with EWMA (Exponentially Weighted Moving Average)
- Score-based worker selection, preferring healthy and fast workers
- Circuit breaker pattern prevents cascading failures

### DNS Configuration
- Set custom DNS for DNS-over-proxy resolution
- Default: `1.1.1.1:53`
- DNS queries are sent through the BatProxy tunnel

---

## Settings

### Port Configuration
- **HTTP Proxy Port:** Default 8888
- **Enable SOCKS5:** Toggle SOCKS5 proxy
- **SOCKS5 Port:** Default 1080

### Authentication
- **Require Auth:** Enable username/password authentication
- **Username:** Set proxy username
- **Password:** Set proxy password

### Upstream Proxy Chaining
Chain VPSH through another proxy:
- **None:** Direct connection
- **SOCKS5:** Chain through a SOCKS5 proxy
- **HTTP:** Chain through an HTTP proxy

**Upstream settings:**
- Address: IP or hostname of upstream proxy
- Port: Upstream proxy port
- Username/Password: Optional authentication

### Health Monitoring
- **Auto Restart:** Automatically restart if the service fails
- **Health Interval:** How often to check health (seconds)
- **Kill Switch:** Pause proxy when VPN disconnects

### Full Mode Settings
- **Force VPN Only:** Disconnect clients if VPN drops
- **Block IPv6 Leak:** Block IPv6 traffic to prevent leaks

### Interface Overrides
- **Hotspot Interface:** Force a specific interface (e.g., `wlan0`, `usb0`)
- **VPN Interface:** Force a specific VPN interface (e.g., `tun0`)

---

## Logs & Health Checks

The **Logs** tab shows:
- Service start/stop events
- Client connections
- Errors and warnings
- Health check results

**Health Check button:** Manually triggers a health check and logs the result.

---

## Quick Settings Tile

Add VPSH to your Quick Settings panel for one-tap control:
1. Swipe down twice to open Quick Settings
2. Tap the edit/pencil icon
3. Find "VPSH" and drag it to your active tiles
4. Tap the tile to start/stop the service

The tile shows:
- **Active:** Service is running
- **Inactive:** Service is stopped
- **Subtitle:** Current state (Running/Stopped/Paused/Error)

---

## Client Setup Guide

### Connecting via HTTP Proxy

**Windows:**
```cmd
# System-wide proxy settings
netsh winhttp set proxy [phone-ip]:8888

# Or use a specific app that supports HTTP proxy
```

**Linux/macOS:**
```bash
export http_proxy=http://[phone-ip]:8888
export https_proxy=http://[phone-ip]:8888
```

**Android (manual):**
Settings → Wi-Fi → Tap network → Proxy → Manual
- Proxy hostname: [phone-ip]
- Proxy port: 8888

### Connecting via SOCKS5

**Windows:**
```cmd
# Set SOCKS5 proxy in applications that support it
# e.g., Firefox: Settings → Network Settings → SOCKS5
```

**Linux/macOS:**
```bash
export ALL_PROXY=socks5://[phone-ip]:1080
```

### Using the QR Code

1. On your phone, open the VPSH Dashboard
2. Ensure the service is running in Proxy Mode
3. Tap the QR button
4. Scan the QR code with another device:
   - **Windows/Linux:** Use a QR scanner that can import proxy settings
   - **Android:** Many proxy apps support QR import
   - **Manual:** The QR code also shows the URL in plain text

### Using BatProxy Clients

BatProxy workers are servers that connect to VPSH. To set up a worker:

1. **On the worker server (Cloudflare):**
   - Follow the deployment guide in the [BatProxy repository](https://github.com/batmanpriv/BatProxy).
   - Deploy the `worker.js` code to Cloudflare Workers.
   - Set the `PASSWD` environment variable.

2. **On your VPSH Android app:**
   - Go to BatProxy tab
   - Add the worker URL (e.g., `wss://your-worker.workers.dev`) and the password
   - Start the BatProxy service

3. **Connect clients:**
   - Configure clients to use the VPSH proxy (HTTP/SOCKS5)
   - All traffic will be intelligently routed through healthy workers

---

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| Proxy not starting | Check if port is already in use. Change port in Settings. |
| Clients can't connect | Verify you're on the same network. Check firewall settings. |
| Full Mode not working | Ensure root is available. Check VPN is active. |
| BatProxy workers failing | Verify worker URLs are correct. Check worker password matches. Check your internet connection. |
| Permission errors | Grant all requested permissions (notifications, VPN). |
| USB tether not working | Enable USB tethering in system settings first. |

### Logs
Always check the **Logs** tab for detailed error messages when troubleshooting.

---

## FAQ

**Q: Do I need root?**
A: Only for Full Mode (VPN NAT routing). Proxy Mode works without root.

**Q: What's the difference between Proxy and Full mode?**
A: Proxy mode runs a proxy server. Full mode routes all hotspot traffic through the VPN using system-level routing.

**Q: Why can't I share certain VPNs (like Viva) using Proxy Mode without root?**
A: This is a fundamental limitation of Android's networking architecture. Here's why:

**1. VPNs Work at the System Level**
- Most VPN apps (like Viva, ExpressVPN, NordVPN) create a **virtual network interface** (e.g., `tun0`).
- They use Android's `VpnService` API to route **all** of the device's traffic through this interface.
- This routing happens at the operating system level, **before** any user-space proxy server can intercept the traffic.

**2. Proxy Mode is a User-Space Application**
- VPSH's Proxy Mode runs an HTTP/SOCKS5 proxy server. This is a regular app that listens for incoming connections.
- It can only accept traffic that is **explicitly directed to it** by a client application (e.g., setting a proxy in a browser).
- It **cannot** see or intercept traffic that the Android system has already routed to the VPN interface.

**3. The "Chicken and Egg" Problem**
- When you activate a VPN, the system directs all traffic to the VPN's virtual interface.
- The VPN app then encrypts and forwards this traffic to its own server.
- A proxy server running on the same device is "downstream" of this system-level decision. It only sees traffic that the system sends to it, but the system is sending everything to the VPN.

**The Only Solution: Full Mode (Requires Root)**
- VPSH's **Full Mode** overcomes this by using `iptables` (Linux firewall) and routing rules, which require **root access**.
- With root, VPSH can manipulate the system's routing table and firewall to *force* all traffic (including from the VPN interface) through the NAT and out through the VPN, effectively sharing the VPN connection with other devices on your hotspot.

**What You Can Share Without Root**
- Only applications that support manual proxy configuration (like browsers, some download managers, or apps that respect system proxy settings) can use VPSH's Proxy Mode.
- The proxy mode is perfect for sharing a standard internet connection or a web proxy, but it cannot forcibly redirect traffic from a system-wide VPN.

**In summary:**
- **Without root:** You can only share the internet connection for apps that **choose** to use your proxy.
- **With root (Full Mode):** You can share **any** internet connection, including those from system-level VPNs like Viva, by forcing all traffic through the VPN.

**Q: How do I find my phone's IP address?**
A: Look in the app's Dashboard under hotspot interface info, or check your Wi-Fi settings.

**Q: Can I run both HTTP and SOCKS5 simultaneously?**
A: Yes, enable SOCKS5 in Settings and both will run on their respective ports.

**Q: How do I limit bandwidth per client?**
A: On the Dashboard, tap the clock icon next to any client and set a limit in Kbps.

**Q: What does BatProxy do?**
A: BatProxy routes traffic through a pool of distributed workers (like Cloudflare Workers), automatically selecting the healthiest one for each request. This provides high reliability and performance.

**Q: Why are my workers showing "half_open" status?**
A: A worker is in half-open state if it failed but is being retried. It will either recover (closed) or fail completely (open) and enter a cooldown period.

**Q: How does the health monitor work?**
A: It periodically checks if the service is healthy. If it fails, it attempts to restart up to 5 times before giving up. For BatProxy, it also performs health checks on each worker.

**Q: Can I use my own BatProxy workers?**
A: Yes! You can deploy the BatProxy worker code (available on [GitHub](https://github.com/batmanpriv/BatProxy)) to Cloudflare Workers or any compatible WebSocket server and add the URL to the VPSH app.
