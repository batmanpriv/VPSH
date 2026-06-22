# 🚀 VPSH - VPN Proxy Share Hotspot

[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)](https://github.com/batmanpriv/VPSH)
[![Platform](https://img.shields.io/badge/platform-Termux%20%7C%20Linux%20%7C%20Windows-brightgreen.svg)](https://github.com/batmanpriv/VPSH)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](https://github.com/batmanpriv/VPSH)

> **Turn your device into a powerful proxy server and share internet with ease!** 🌐

VPSH is a versatile proxy server solution that transforms your device into a fully-featured proxy gateway. Perfect for sharing internet connections via WiFi hotspot, USB tethering, or network interfaces with HTTP/SOCKS5 support.

## ✨ Features

- 🔥 **Multiple Proxy Methods** - Python, Tinyproxy, Privoxy, 3proxy, Socat
- 🔒 **SOCKS5 Support** - Compatible with games, SSH, and all traffic
- 📱 **Mobile Friendly** - Optimized for Termux on Android
- 💻 **Cross-Platform** - Works on Linux, Termux, and Windows (client)
- 🎮 **Gaming Profile** - UDP priority QoS for better gaming experience
- 🔐 **Authentication** - Username/Password protection
- 🚀 **Transparent Proxy** - iptables support for seamless redirection
- 🌐 **Tunnel Support** - Cloudflare, ngrok, Tailscale, ZeroTier
- ♻️ **Auto-Restart** - Self-healing with health monitoring
- 📊 **Client Management** - Track connected clients
- 🎨 **Beautiful UI** - Colorful terminal output with QR codes

## 📸 Screenshots

<div align="center">
  <img src="https://github.com/user-attachments/assets/553c71f5-5039-4a11-a1ec-ddb2c5f45647" width="45%" alt="Android Proxy Setup">
  <img src="https://github.com/user-attachments/assets/d233a910-615c-4f20-9705-8b4c2cd0d5c7" width="45%" alt="Proxy Code Setup">
</div>

## 🚀 Quick Start

### 📦 Installation

#### Termux (Android)
```bash
pkg update && pkg upgrade
pkg install curl -y
curl -o vpsh.sh https://raw.githubusercontent.com/batmanpriv/VPSH/main/vpsh.sh
chmod +x vpsh.sh
./vpsh.sh start
```

#### Linux
```bash
sudo apt update
sudo apt install curl -y
curl -o vpsh.sh https://raw.githubusercontent.com/batmanpriv/VPSH/main/vpsh.sh
chmod +x vpsh.sh
./vpsh.sh start
```

## 📖 Usage Examples

### Basic Commands

```bash
# Start proxy with default settings
./vpsh.sh start

# Stop proxy
./vpsh.sh stop

# Restart proxy
./vpsh.sh restart

# Check status
./vpsh.sh status

# Show health check
./vpsh.sh health

# View logs
./vpsh.sh logs

# Update to latest version
./vpsh.sh update
```

### Advanced Examples

```bash
# Start with specific method and port
./vpsh.sh start --method=tinyproxy --port=8080

# Enable SOCKS5 with authentication
./vpsh.sh start --socks5 --auth --profile=secure

# Gaming profile with transparent proxy (root required)
sudo ./vpsh.sh start --profile=gaming --transparent

# Force specific interface and tunnel
./vpsh.sh start --hotspot=wlan0 --tunnel=cloudflare

# Start with custom SOCKS5 port
./vpsh.sh start --socks5 --socks5-port=1080

# Disable auto-restart for debugging
./vpsh.sh start --no-auto-restart --debug
```

### Windows Client Setup

```cmd
# Connect to proxy
SetProxy.bat connect 192.168.1.100 8888

# Connect with authentication
SetProxy.bat connect 192.168.1.100 8888 myuser mypass

# Disconnect
SetProxy.bat disconnect

# Check status
SetProxy.bat status

# Test connection
SetProxy.bat test

# Interactive mode
SetProxy.bat
```

## ⚙️ Configuration

### Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--method=METHOD` | Proxy method (python/tinyproxy/privoxy/3proxy/socat) | python |
| `--port=PORT` | HTTP proxy port | Random (8000-9999) |
| `--socks5` | Enable SOCKS5 proxy | Disabled |
| `--socks5-port=PORT` | SOCKS5 port | Random (1080-2079) |
| `--profile=PROFILE` | Profile (default/gaming/streaming/secure) | default |
| `--auth` | Enable authentication | Disabled |
| `--transparent` | Enable transparent proxy (root only) | Disabled |
| `--tunnel=TYPE` | Tunnel (cloudflare/ngrok/tailscale/zerotier) | None |
| `--hotspot=IFACE` | Force interface | Auto-detect |
| `--debug` | Enable debug output | Disabled |
| `--no-auto-restart` | Disable auto-restart | Enabled |

### Persistent Configuration

Create `vpsh.conf` in the script directory:

```bash
PROXY_METHOD=python
PROXY_PORT=8888
SOCKS5_PORT=1080
PROFILE=default
ENABLE_SOCKS5=1
ENABLE_AUTH=1
ENABLE_TRANSPARENT=0
ENABLE_TUNNEL=""
AUTO_RESTART=1
HEALTH_INTERVAL=30
HOTSPOT_IFACE_OVERRIDE=""
PROXY_USER="myuser"
PROXY_PASS="mypass"
```

## 🎯 Proxy Methods

| Method | Description | Use Case |
|--------|-------------|----------|
| **python** | Built-in Python proxy, no install needed | General use, quick setup |
| **tinyproxy** | Lightweight C proxy | Low-resource devices |
| **privoxy** | Feature-rich proxy with filtering | Privacy & filtering |
| **3proxy** | Multi-protocol proxy | Advanced configurations |
| **socat** | TCP relay only (limited) | Simple forwarding |

## 📱 How to Use on Devices

### Android/iOS

1. Open **WiFi Settings**
2. Long press your hotspot network
3. Select **Modify Network** or **Proxy Settings**
4. Choose **Manual Proxy Configuration**
5. Enter:
   - **Host**: IP address from VPSH display
   - **Port**: HTTP proxy port
   - **Username/Password**: If authentication is enabled

### Linux/macOS

```bash
# Set environment variables
export http_proxy=http://192.168.1.100:8888
export https_proxy=http://192.168.1.100:8888

# With authentication
export http_proxy=http://user:pass@192.168.1.100:8888
export https_proxy=http://user:pass@192.168.1.100:8888
```

### Windows GUI

1. Open **Internet Options** (inetcpl.cpl)
2. Go to **Connections** tab
3. Click **LAN settings**
4. Enable **Use a proxy server**
5. Enter:
   - **Address**: IP address from VPSH
   - **Port**: HTTP proxy port
6. Click **OK**

### Browser Setup

#### Chrome/Edge
- Settings → Advanced → System → Open proxy settings
- Or use command line: `--proxy-server=http://IP:PORT`

#### Firefox
- Settings → Network Settings → Manual proxy configuration
- HTTP Proxy: IP, Port: PORT
- Also use for HTTPS and FTP

## 🎮 Profiles

| Profile | Features | Best For |
|---------|----------|----------|
| **default** | HTTP proxy, standard settings | General browsing |
| **gaming** | SOCKS5 + UDP QoS + fast health | Online gaming |
| **streaming** | High capacity | Video streaming |
| **secure** | Auth + SOCKS5 | Privacy & security |

## 🔧 Dependencies

### Auto-Installed Dependencies
- curl
- pkill/procps
- awk/gawk
- iproute2/ss
- Python3 (for python method)

### Optional Dependencies
- tinyproxy - `pkg install tinyproxy` (Termux) / `apt install tinyproxy` (Linux)
- privoxy - `pkg install privoxy`
- 3proxy - `pkg install 3proxy`
- socat - `pkg install socat`
- microsocks - For SOCKS5 support
- qrencode - For QR codes

## 🚑 Troubleshooting

### Common Issues

#### Port Already in Use
```bash
# Kill process using port
sudo kill -9 $(sudo lsof -t -i:8888)

# Or use different port
./vpsh.sh start --port=9999
```

#### Permission Denied
```bash
# Make script executable
chmod +x vpsh.sh

# Run with root for transparent proxy
sudo ./vpsh.sh start --transparent
```

#### Interface Not Found
```bash
# List available interfaces
./vpsh.sh --debug start

# Force specific interface
./vpsh.sh start --hotspot=wlan0
```

#### Proxy Not Working
```bash
# Check status
./vpsh.sh status

# View logs
./vpsh.sh logs

# Run health check
./vpsh.sh health

# Restart with debug
./vpsh.sh restart --debug
```

## 📊 Monitoring

### View Connected Clients
```bash
./vpsh.sh status
```

### Health Check
```bash
./vpsh.sh health
```

### Logs
```bash
# View all logs
./vpsh.sh logs

# Tail specific logs
tail -f tinyproxy.log
tail -f dante.log
tail -f python_proxy.log
```

## 🔐 Security Notes

- 🔒 Auth credentials are stored in `vpsh.auth` with 600 permissions
- 🛡️ Firewall rules can be added for additional security
- 🌐 Transparent proxy requires root privileges
- 🔑 Change default credentials immediately
- 📍 Proxy is accessible from all interfaces by default

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- All the amazing open-source proxy projects
- Termux community for Android support
- Contributors and users of VPSH

## 📞 Support

- 📧 Issues: [GitHub Issues](https://github.com/batmanpriv/VPSH/issues)
- 🌐 Repository: [GitHub](https://github.com/batmanpriv/VPSH)
- 📖 Wiki: [Documentation](https://github.com/batmanpriv/VPSH/wiki)

---

<div align="center">
  Made with ❤️ by BatmanPriv
</div>
