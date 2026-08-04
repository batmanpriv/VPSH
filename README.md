# VPSH - Virtual Private Share Hub

**Version 3.1.0**

VPSH is an Android application that turns your device into a powerful network sharing hub. It supports both **Proxy Mode** (HTTP/SOCKS5) and **Full VPN Mode** (NAT routing), with advanced features like client management, bandwidth limiting, and the innovative **BatProxy** distributed proxy system.

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

## Installation

1. Download the APK from the official source
2. Enable "Install from unknown sources" in your Android settings
3. Install the APK
4. Grant notification permission when prompted (Android 13+)

## First Launch

When you first open VPSH:
1. The app will check for root access
2. You'll see the Dashboard with status information
3. The app is ready to start in **Proxy Mode** by default

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

### Full Mode (VPN NAT)
- **Root required:** Yes
- **How it works:** Routes all traffic from your hotspot through the VPN using iptables
- **Use case:** Sharing a VPN connection with multiple devices
- **Features:**
  - Full NAT routing through VPN interface
  - IPv6 leak protection
  - Client bandwidth limiting via tc (traffic control)
  - Health monitoring and auto-restart

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

## BatProxy Tab

### What is BatProxy?
BatProxy is a **distributed proxy system** where multiple "worker" servers act as a pool. The app intelligently:
1. Routes connections through the healthiest available worker
2. Monitors worker performance (latency, success rate)
3. Automatically fails over to other workers
4. Reopens connections through workers that recover

### Adding Workers
1. Go to the **BatProxy** tab
2. Tap the **+** button
3. Enter worker URL and password
4. Tap Save

**Worker URL format:** `ws://example.com:8080` or `wss://example.com:8080`

### Worker Health & Stats
Each worker displays:
- **Status:** Closed (healthy), Half-open (recovering), Open (failed/cooldown)
- **Cooldown:** Time remaining before retry
- **RTT:** Average round-trip time in milliseconds
- **Success rate:** OK/Fail ratio
- **Active connections:** Current connections through this worker

**Health monitoring:**
- Automatic health checks every 30 seconds
- Exponential backoff for failed workers
- Success rate tracking with EWMA (Exponentially Weighted Moving Average)
- Score-based worker selection

### DNS Configuration
- Set custom DNS for DNS-over-proxy resolution
- Default: `1.1.1.1:53`
- DNS queries are sent through the BatProxy tunnel

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

## Logs & Health Checks

The **Logs** tab shows:
- Service start/stop events
- Client connections
- Errors and warnings
- Health check results

**Health Check button:** Manually triggers a health check and logs the result.

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

1. **On the worker server:**
   - Run the BatProxy server software
   - Configure the worker URL (e.g., `ws://your-server.com:8080`)
   - Set a password

2. **On your VPSH Android app:**
   - Go to BatProxy tab
   - Add the worker URL and password
   - Start the BatProxy service

3. **Connect clients:**
   - Configure clients to use the VPSH proxy
   - All traffic will be intelligently routed through healthy workers

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| Proxy not starting | Check if port is already in use. Change port in Settings. |
| Clients can't connect | Verify you're on the same network. Check firewall settings. |
| Full Mode not working | Ensure root is available. Check VPN is active. |
| BatProxy workers failing | Verify worker URLs are correct. Check network connectivity. |
| Permission errors | Grant all requested permissions (notifications, VPN). |
| USB tether not working | Enable USB tethering in system settings first. |

### Logs
Always check the **Logs** tab for detailed error messages when troubleshooting.

## FAQ

**Q: Do I need root?**
A: Only for Full Mode (VPN NAT routing). Proxy Mode works without root.

**Q: What's the difference between Proxy and Full mode?**
A: Proxy mode runs a proxy server. Full mode routes all hotspot traffic through the VPN.

**Q: How do I find my phone's IP address?**
A: Look in the app's Dashboard under hotspot interface info, or check your Wi-Fi settings.

**Q: Can I run both HTTP and SOCKS5 simultaneously?**
A: Yes, enable SOCKS5 in Settings and both will run on their respective ports.

**Q: How do I limit bandwidth per client?**
A: On the Dashboard, tap the clock icon next to any client and set a limit in Kbps.

**Q: What does BatProxy do?**
A: BatProxy routes traffic through a pool of distributed workers, automatically selecting the healthiest one.

**Q: Why are my workers showing "half_open" status?**
A: A worker is in half-open state if it failed but is being retried. It will either recover (closed) or fail completely (open).

**Q: How does the health monitor work?**
A: It periodically checks if the service is healthy. If it fails, it attempts to restart up to 5 times before giving up.
