#!/data/data/com.termux/files/usr/bin/bash

VERSION="2.0.0"
PROJECT="VPSH"
PROJECT_FULL="VPN Proxy Share Hotspot"
UPDATE_VERSION_URL="https://raw.githubusercontent.com/batmanpriv/VPSH/refs/heads/main/version.txt"
UPDATE_SCRIPT_RAW="https://raw.githubusercontent.com/batmanpriv/VPSH/refs/heads/main/vpsh.sh"

CYAN='\033[96m'
GREEN='\033[92m'
YELLOW='\033[93m'
RED='\033[91m'
MAGENTA='\033[95m'
BOLD='\033[1m'
RESET='\033[0m'

log_info()    { echo -e "${CYAN}▶ $1${RESET}"; }
log_success() { echo -e "${GREEN}✓ $1${RESET}"; }
log_warning() { echo -e "${YELLOW}⚠ $1${RESET}"; }
log_error()   { echo -e "${RED}✗ $1${RESET}"; }
log_status()  { echo "• $1"; }
log_debug()   { [[ "$DEBUG" == "1" ]] && echo -e "${MAGENTA}[DBG] $1${RESET}"; }

PROXY_PORT=8888
SOCKS5_PORT=1080
PORT_SPECIFIED=0
SOCKS5_PORT_SPECIFIED=0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
CONFIG_FILE="$SCRIPT_DIR/tinyproxy.conf"
DANTE_CONFIG="$SCRIPT_DIR/dante.conf"
MICROSOCKS_PID_FILE="$SCRIPT_DIR/microsocks.pid"
PRIVOXY_CONFIG="$SCRIPT_DIR/privoxy.conf"
POLIPO_CONFIG="$SCRIPT_DIR/polipo.conf"
TINYPROXY_LOG="$SCRIPT_DIR/tinyproxy.log"
DANTE_LOG="$SCRIPT_DIR/dante.log"
PRIVOXY_LOG="$SCRIPT_DIR/privoxy.log"
POLIPO_LOG="$SCRIPT_DIR/polipo.log"
PYTHON_PROXY_LOG="$SCRIPT_DIR/python_proxy.log"
TINYPROXY_PID_FILE="$SCRIPT_DIR/tinyproxy.pid"
DANTE_PID_FILE="$SCRIPT_DIR/dante.pid"
PRIVOXY_PID_FILE="$SCRIPT_DIR/privoxy.pid"
POLIPO_PID_FILE="$SCRIPT_DIR/polipo.pid"
PYTHON_PROXY_PID_FILE="$SCRIPT_DIR/python_proxy.pid"
PID_FILE="$SCRIPT_DIR/vpsh.pid"
STATE_FILE="$SCRIPT_DIR/vpsh.state"
OVERRIDE_FILE="$SCRIPT_DIR/vpsh.conf"
AUTH_FILE="$SCRIPT_DIR/vpsh.auth"
HEALTH_LOG="$SCRIPT_DIR/health.log"
PYTHON_PROXY_SCRIPT="$SCRIPT_DIR/http_proxy.py"

RUNNING=1
HOTSPOT_IFACE_OVERRIDE=""
HOTSPOT_IFACE=""
HOTSPOT_IP=""
PROFILE="default"
PROXY_METHOD="python"
ENABLE_SOCKS5=0
ENABLE_AUTH=0
ENABLE_TRANSPARENT=0
ENABLE_TUNNEL=""
AUTO_RESTART=1
HEALTH_INTERVAL=30
MAX_RESTART_ATTEMPTS=5
RESTART_ATTEMPTS=0
IS_ROOT=0
PROXY_USER=""
PROXY_PASS=""
DEBUG=0
MAX_CLIENTS=100
MIN_SPARE=5
MAX_SPARE=20
START_SERVERS=10
PKG_MANAGER=""

[[ -f "$OVERRIDE_FILE" ]] && source "$OVERRIDE_FILE"

detect_root() {
    if [[ "$EUID" -eq 0 ]] || [[ "$(id -u 2>/dev/null)" -eq 0 ]]; then
        IS_ROOT=1
        return
    fi
    if command -v timeout >/dev/null 2>&1; then
        if timeout 2 su -c "id" root >/dev/null 2>&1; then
            IS_ROOT=1
        fi
    fi
}

detect_pkg_manager() {
    if command -v pkg >/dev/null 2>&1; then PKG_MANAGER="pkg"
    elif command -v apt-get >/dev/null 2>&1; then PKG_MANAGER="apt-get"
    elif command -v apt >/dev/null 2>&1; then PKG_MANAGER="apt"
    fi
}

install_if_missing() {
    local bin=$1 pkg=$2
    if command -v "$bin" >/dev/null 2>&1; then
        log_debug "$bin already installed"
        return 0
    fi
    if [[ -z "$PKG_MANAGER" ]]; then
        log_error "Cannot auto-install $pkg: no package manager found"
        return 1
    fi
    log_info "Installing $pkg..."
    if "$PKG_MANAGER" install -y "$pkg" >/dev/null 2>&1; then
        if command -v "$bin" >/dev/null 2>&1; then
            log_success "$pkg installed"
            return 0
        fi
    fi
    log_error "Failed to install $pkg"
    return 1
}

check_and_install_dependencies() {
    log_status "Checking dependencies..."
    detect_pkg_manager
    local failed=0

    if ! command -v ip >/dev/null 2>&1 && ! command -v ifconfig >/dev/null 2>&1; then
        install_if_missing "ip" "iproute2" || failed=1
    fi

    local bin pkg
    for entry in "curl:curl" "pkill:procps" "awk:gawk" "ss:iproute2"; do
        bin="${entry%%:*}"
        pkg="${entry##*:}"
        if ! command -v "$bin" >/dev/null 2>&1; then
            install_if_missing "$bin" "$pkg" || failed=1
        fi
    done

    case "$PROXY_METHOD" in
        tinyproxy)
            install_if_missing "tinyproxy" "tinyproxy" || failed=1
            ;;
        privoxy)
            install_if_missing "privoxy" "privoxy" || failed=1
            ;;
        polipo)
            install_if_missing "polipo" "polipo" || failed=1
            ;;
        python|python3)
            install_if_missing "python3" "python" || failed=1
            PROXY_METHOD="python"
            ;;
        3proxy)
            if ! command -v 3proxy >/dev/null 2>&1; then
                install_if_missing "3proxy" "3proxy" || {
                    log_warning "3proxy not available — falling back to python"
                    PROXY_METHOD="python"
                }
            fi
            ;;
        socat)
            install_if_missing "socat" "socat" || failed=1
            ;;
        *)
            log_warning "Unknown method '$PROXY_METHOD' — using python"
            PROXY_METHOD="python"
            install_if_missing "python3" "python" || failed=1
            ;;
    esac

    if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
        if ! command -v sockd >/dev/null 2>&1 && ! command -v danted >/dev/null 2>&1; then
            if ! command -v microsocks >/dev/null 2>&1; then
                install_if_missing "sockd" "dante-server" 2>/dev/null
            fi
            if ! command -v sockd >/dev/null 2>&1 && ! command -v danted >/dev/null 2>&1; then
                if ! command -v microsocks >/dev/null 2>&1; then
                    log_warning "No SOCKS5 server found — SOCKS5 disabled"
                    ENABLE_SOCKS5=0
                else
                    log_success "Using microsocks for SOCKS5"
                fi
            fi
        fi
    fi

    if [[ "$failed" -eq 1 ]]; then
        log_error "Some required packages could not be installed"
        return 1
    fi

    log_success "All dependencies ready"
    return 0
}

do_update() {
    log_info "Checking for updates..."
    if ! command -v curl >/dev/null 2>&1; then
        log_error "curl is required for update check"
        return 1
    fi

    local remote_ver
    remote_ver=$(curl -fsSL --max-time 10 "$UPDATE_VERSION_URL" 2>/dev/null | tr -d '[:space:]')

    if [[ -z "$remote_ver" ]]; then
        log_error "Could not fetch version info"
        return 1
    fi

    echo
    echo "  Local version:  $VERSION"
    echo "  Remote version: $remote_ver"
    echo

    if [[ "$remote_ver" == "$VERSION" ]]; then
        log_success "Already up to date (v$VERSION)"
        return 0
    fi

    local local_major local_minor local_patch remote_major remote_minor remote_patch
    IFS='.' read -r local_major local_minor local_patch <<< "$VERSION"
    IFS='.' read -r remote_major remote_minor remote_patch <<< "$remote_ver"

    local is_newer=0
    if [[ "$remote_major" -gt "$local_major" ]]; then is_newer=1
    elif [[ "$remote_major" -eq "$local_major" && "$remote_minor" -gt "$local_minor" ]]; then is_newer=1
    elif [[ "$remote_major" -eq "$local_major" && "$remote_minor" -eq "$local_minor" && "$remote_patch" -gt "$local_patch" ]]; then is_newer=1
    fi

    if [[ "$is_newer" -eq 0 ]]; then
        log_warning "Remote version ($remote_ver) is not newer than local ($VERSION)"
        return 0
    fi

    log_info "New version available: v$remote_ver"
    echo
    read -rp "  Download and install v$remote_ver? [y/N] " confirm
    echo

    if [[ ! "$confirm" =~ ^[Yy]$ ]]; then
        log_status "Update cancelled"
        return 0
    fi

    log_info "Downloading v$remote_ver..."
    local tmp_file
    tmp_file=$(mktemp /tmp/vpsh_update.XXXXXX)

    if ! curl -fsSL --max-time 30 "$UPDATE_SCRIPT_RAW" -o "$tmp_file" 2>/dev/null; then
        log_error "Download failed"
        rm -f "$tmp_file"
        return 1
    fi

    if ! bash -n "$tmp_file" 2>/dev/null; then
        log_error "Downloaded file has syntax errors — aborting"
        rm -f "$tmp_file"
        return 1
    fi

    local backup="$SCRIPT_PATH.bak"
    cp "$SCRIPT_PATH" "$backup" 2>/dev/null
    log_status "Backup saved: $backup"

    chmod +x "$tmp_file"
    if mv "$tmp_file" "$SCRIPT_PATH" 2>/dev/null; then
        chmod +x "$SCRIPT_PATH"
        log_success "Updated to v$remote_ver"
        echo
        echo "  Run: ./vpsh.sh start"
    else
        log_error "Could not replace script"
        log_status "New version saved at: $tmp_file"
        return 1
    fi
}

mask_to_prefix() {
    local bits=0 o1 o2 o3 o4 oct
    IFS='.' read -r o1 o2 o3 o4 <<< "$1"
    for oct in $o1 $o2 $o3 $o4; do
        case $oct in
            255) bits=$((bits+8));; 254) bits=$((bits+7));; 252) bits=$((bits+6));;
            248) bits=$((bits+5));; 240) bits=$((bits+4));; 224) bits=$((bits+3));;
            192) bits=$((bits+2));; 128) bits=$((bits+1));; *) ;;
        esac
    done
    echo "$bits"
}

list_interfaces() {
    local output=""

    if command -v ip >/dev/null 2>&1; then
        output=$(ip -o addr show 2>/dev/null | awk '$3=="inet" {
            split($4, a, "/")
            if (a[1] != "127.0.0.1") print $2, a[1], a[2]
        }')
        if [[ -n "$output" ]]; then
            echo "$output"
            return 0
        fi
    fi

    if command -v ifconfig >/dev/null 2>&1; then
        local cur=""
        while IFS= read -r line; do
            if [[ "$line" =~ ^[A-Za-z0-9] ]]; then
                cur=$(echo "$line" | awk '{print $1}' | tr -d ':')
            elif [[ "$line" == *"inet "* && -n "$cur" ]]; then
                local ipaddr mask prefix
                ipaddr=$(echo "$line" | grep -oE 'inet [0-9.]+' | awk '{print $2}')
                mask=$(echo "$line" | grep -oE 'netmask [0-9.]+' | awk '{print $2}')
                [[ -z "$ipaddr" || "$ipaddr" == "127.0.0.1" ]] && continue
                prefix=24
                [[ -n "$mask" ]] && prefix=$(mask_to_prefix "$mask")
                echo "$cur $ipaddr $prefix"
            fi
        done < <(ifconfig 2>/dev/null)
        return 0
    fi

    if [[ -f /proc/net/dev ]]; then
        tail -n +3 /proc/net/dev | while read -r line; do
            local iface
            iface=$(echo "$line" | awk -F: '{print $1}' | tr -d ' ')
            [[ -z "$iface" ]] && continue
            local ip
            ip=$(ip route 2>/dev/null | grep "$iface" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' | head -1)
            if [[ -n "$ip" && "$ip" != "127.0.0.1" ]]; then
                echo "$iface $ip 24"
            fi
        done
        return 0
    fi

    log_error "No method available to list interfaces"
    return 1
}

calc_network() {
    local ip=$1 prefix=${2:-24}
    [[ "$prefix" =~ ^[0-9]+$ ]] || prefix=24
    local i1 i2 i3 i4
    IFS='.' read -r i1 i2 i3 i4 <<< "$ip"
    local ip_int=$(( (i1<<24) + (i2<<16) + (i3<<8) + i4 ))
    local mask=0
    [[ "$prefix" -gt 0 ]] && mask=$(( (0xFFFFFFFF << (32-prefix)) & 0xFFFFFFFF ))
    local net=$(( ip_int & mask ))
    echo "$(( (net>>24)&255 )).$(( (net>>16)&255 )).$(( (net>>8)&255 )).$(( net&255 ))/$prefix"
}

find_share_interface() {
    if [[ -n "$HOTSPOT_IFACE_OVERRIDE" ]]; then
        local line
        line=$(list_interfaces | awk -v n="$HOTSPOT_IFACE_OVERRIDE" '$1==n{print; exit}')
        if [[ -n "$line" ]]; then
            echo "$line"
            return 0
        fi
        log_error "Interface '$HOTSPOT_IFACE_OVERRIDE' not found or has no IP"
        return 1
    fi

    local ifaces
    ifaces=$(list_interfaces)

    if [[ -z "$ifaces" ]]; then
        log_error "No interfaces found with IP addresses"
        return 1
    fi

    local name line

    for name in swlan0 softap0 ap0 wlan1; do
        line=$(echo "$ifaces" | awk -v n="$name" '$1==n{print; exit}')
        [[ -n "$line" ]] && echo "$line" && return 0
    done

    for name in usb0 rndis0 usb_rndis0 usb_rndis usb1 ncm0; do
        line=$(echo "$ifaces" | awk -v n="$name" '$1==n{print; exit}')
        [[ -n "$line" ]] && echo "$line" && return 0
    done

    line=$(echo "$ifaces" | awk '$1 ~ /^ap_br/{print; exit}')
    [[ -n "$line" ]] && echo "$line" && return 0

    line=$(echo "$ifaces" | awk '$1 ~ /softap|swlan|^ap[0-9]/{print; exit}')
    [[ -n "$line" ]] && echo "$line" && return 0

    line=$(echo "$ifaces" | awk '$1=="wlan0"{print; exit}')
    [[ -n "$line" ]] && echo "$line" && return 0

    line=$(echo "$ifaces" | awk '$1 !~ /lo|docker|veth|br-|tun|tap/ {print; exit}')
    if [[ -n "$line" ]]; then
        log_warning "No known interface found — using first available: $line"
        echo "$line"
        return 0
    fi

    log_error "No suitable sharing interface found"
    return 1
}

detect_share_mode() {
    case "$1" in
        usb*|rndis*|ncm*)    echo "USB Tethering" ;;
        softap*|swlan*|ap*)  echo "WiFi Hotspot" ;;
        wlan*)               echo "WiFi (shared)" ;;
        *)                   echo "Network Share" ;;
    esac
}

save_state() {
    {
        echo "HOTSPOT_IFACE=$HOTSPOT_IFACE"
        echo "HOTSPOT_IP=$HOTSPOT_IP"
        echo "PROFILE=$PROFILE"
        echo "PROXY_METHOD=$PROXY_METHOD"
        echo "ENABLE_SOCKS5=$ENABLE_SOCKS5"
        echo "ENABLE_AUTH=$ENABLE_AUTH"
        echo "ENABLE_TRANSPARENT=$ENABLE_TRANSPARENT"
        echo "ENABLE_TUNNEL=$ENABLE_TUNNEL"
        echo "PROXY_PORT=$PROXY_PORT"
        echo "SOCKS5_PORT=$SOCKS5_PORT"
    } > "$STATE_FILE"
}

load_state() {
    [[ -f "$STATE_FILE" ]] || return
    while IFS='=' read -r key value; do
        [[ "$key" =~ ^[A-Z_0-9]+$ ]] || continue
        printf -v "$key" '%s' "$value"
    done < <(grep -E '^[A-Z_0-9]+=.' "$STATE_FILE")
}

clear_state() { rm -f "$STATE_FILE"; }

is_port_free() {
    local port=$1
    if ss -tlnp 2>/dev/null | grep -q ":${port} "; then
        return 1
    fi
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "
import socket
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(('0.0.0.0', $port))
    s.close()
    exit(0)
except OSError:
    s.close()
    exit(1)
" 2>/dev/null
        return $?
    fi
    return 0
}

kill_port_users() {
    local port=$1
    local pids
    pids=$(ss -tlnp 2>/dev/null | grep ":${port} " | grep -oE 'pid=[0-9]+' | cut -d= -f2)
    if [[ -n "$pids" ]]; then
        for pid in $pids; do
            log_debug "Killing pid $pid using port $port"
            kill "$pid" 2>/dev/null
        done
        sleep 1
        pids=$(ss -tlnp 2>/dev/null | grep ":${port} " | grep -oE 'pid=[0-9]+' | cut -d= -f2)
        for pid in $pids; do
            kill -9 "$pid" 2>/dev/null
        done
        sleep 0.5
    fi
}

find_free_port() {
    local preferred=$1
    local min=8000
    local max=9999

    if [[ -n "$preferred" ]] && is_port_free "$preferred"; then
        echo "$preferred"
        return 0
    fi

    if [[ -n "$preferred" ]]; then
        log_warning "Port $preferred is busy — searching for free port..."
        kill_port_users "$preferred"
        sleep 1
        if is_port_free "$preferred"; then
            log_success "Port $preferred freed successfully"
            echo "$preferred"
            return 0
        fi
    fi

    local attempt port
    for attempt in $(seq 1 30); do
        port=$(( 8000 + RANDOM % 2000 ))
        if is_port_free "$port"; then
            log_info "Selected random port: $port"
            echo "$port"
            return 0
        fi
    done

    log_error "Could not find a free port in range 8000-9999"
    return 1
}

apply_profile() {
    case "$PROFILE" in
        gaming)
            ENABLE_SOCKS5=1
            HEALTH_INTERVAL=15
            MAX_CLIENTS=150
            MIN_SPARE=8
            MAX_SPARE=30
            START_SERVERS=15
            log_info "Profile: Gaming (SOCKS5 + UDP priority)"
            ;;
        streaming)
            MAX_CLIENTS=200
            MIN_SPARE=10
            MAX_SPARE=50
            START_SERVERS=20
            HEALTH_INTERVAL=30
            log_info "Profile: Streaming (high capacity)"
            ;;
        secure)
            ENABLE_AUTH=1
            ENABLE_SOCKS5=1
            HEALTH_INTERVAL=20
            log_info "Profile: Secure (auth + SOCKS5)"
            ;;
        default|*)
            PROFILE="default"
            ;;
    esac
}

setup_auth() {
    [[ "$ENABLE_AUTH" -ne 1 ]] && return 0

    if [[ -f "$AUTH_FILE" ]]; then
        source "$AUTH_FILE" 2>/dev/null
        if [[ -n "$PROXY_USER" && -n "$PROXY_PASS" ]]; then
            log_success "Auth loaded: user=$PROXY_USER"
            return 0
        fi
    fi

    echo
    log_info "Set up proxy authentication:"
    read -rp "  Username: " PROXY_USER
    read -rsp "  Password: " PROXY_PASS
    echo

    if [[ -z "$PROXY_USER" || -z "$PROXY_PASS" ]]; then
        log_error "Username/Password cannot be empty"
        return 1
    fi

    printf 'PROXY_USER="%s"\nPROXY_PASS="%s"\n' "$PROXY_USER" "$PROXY_PASS" > "$AUTH_FILE"
    chmod 600 "$AUTH_FILE"
    log_success "Auth credentials saved"
    return 0
}

generate_python_proxy() {
    local auth_code=""
    if [[ "$ENABLE_AUTH" -eq 1 && -n "$PROXY_USER" && -n "$PROXY_PASS" ]]; then
        auth_code="
import base64
AUTH_USER = '${PROXY_USER}'
AUTH_PASS = '${PROXY_PASS}'

def check_auth(headers):
    auth = headers.get('Proxy-Authorization', '')
    if not auth.startswith('Basic '):
        return False
    try:
        decoded = base64.b64decode(auth[6:]).decode()
        user, _, passwd = decoded.partition(':')
        return user == AUTH_USER and passwd == AUTH_PASS
    except Exception:
        return False
"
    fi

    cat > "$PYTHON_PROXY_SCRIPT" << PYEOF
#!/usr/bin/env python3
import socket
import threading
import sys
import os
import signal
import select
import logging
${auth_code}

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s %(message)s',
    handlers=[
        logging.FileHandler('${PYTHON_PROXY_LOG}'),
        logging.StreamHandler(sys.stdout)
    ]
)
log = logging.getLogger('proxy')

BIND_HOST = '0.0.0.0'
BIND_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else ${PROXY_PORT}
BUFFER = 65536
TIMEOUT = 60

def forward(src, dst):
    try:
        while True:
            r, _, _ = select.select([src, dst], [], [], TIMEOUT)
            if not r:
                break
            for s in r:
                d = dst if s is src else src
                try:
                    data = s.recv(BUFFER)
                    if not data:
                        return
                    d.sendall(data)
                except Exception:
                    return
    except Exception:
        pass
    finally:
        for s in (src, dst):
            try:
                s.close()
            except Exception:
                pass

def handle_connect(client, host, port):
    try:
        remote = socket.create_connection((host, port), timeout=TIMEOUT)
        client.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
        t1 = threading.Thread(target=forward, args=(client, remote), daemon=True)
        t1.start()
        forward(remote, client)
    except Exception as e:
        try:
            client.sendall(b'HTTP/1.1 502 Bad Gateway\r\n\r\n')
        except Exception:
            pass
        try:
            client.close()
        except Exception:
            pass

def handle_http(client, method, url, version, headers, body):
    try:
        if url.startswith('http://'):
            url = url[7:]
        slash = url.find('/')
        if slash == -1:
            host_port = url
            path = '/'
        else:
            host_port = url[:slash]
            path = url[slash:]
        if ':' in host_port:
            host, port = host_port.rsplit(':', 1)
            port = int(port)
        else:
            host = host_port
            port = 80
        remote = socket.create_connection((host, port), timeout=TIMEOUT)
        req_line = f'{method} {path} {version}\r\n'
        hdr_str = ''
        for k, v in headers.items():
            if k.lower() in ('proxy-authorization', 'proxy-connection'):
                continue
            hdr_str += f'{k}: {v}\r\n'
        hdr_str += 'Connection: close\r\n'
        full_req = req_line + hdr_str + '\r\n'
        if body:
            full_req = full_req.encode() + body
        else:
            full_req = full_req.encode()
        remote.sendall(full_req)
        forward(client, remote)
    except Exception as e:
        try:
            client.sendall(b'HTTP/1.1 502 Bad Gateway\r\n\r\n')
        except Exception:
            pass
        try:
            client.close()
        except Exception:
            pass

def parse_request(data):
    try:
        if b'\r\n\r\n' not in data:
            return None
        header_part, _, body = data.partition(b'\r\n\r\n')
        lines = header_part.decode(errors='replace').split('\r\n')
        if not lines:
            return None
        parts = lines[0].split(' ', 2)
        if len(parts) < 3:
            return None
        method, url, version = parts
        headers = {}
        for line in lines[1:]:
            if ':' in line:
                k, _, v = line.partition(':')
                headers[k.strip()] = v.strip()
        return method, url, version, headers, body
    except Exception:
        return None

def handle_client(client, addr):
    try:
        client.settimeout(TIMEOUT)
        data = b''
        while b'\r\n\r\n' not in data:
            chunk = client.recv(BUFFER)
            if not chunk:
                return
            data += chunk
            if len(data) > 65536:
                break

        result = parse_request(data)
        if not result:
            client.close()
            return

        method, url, version, headers, body = result

        if '${ENABLE_AUTH}' == '1':
            if not check_auth(headers):
                client.sendall(
                    b'HTTP/1.1 407 Proxy Authentication Required\r\n'
                    b'Proxy-Authenticate: Basic realm="VPSH"\r\n'
                    b'Content-Length: 0\r\n\r\n'
                )
                client.close()
                return

        if method == 'CONNECT':
            if ':' in url:
                host, port = url.rsplit(':', 1)
                port = int(port)
            else:
                host = url
                port = 443
            log.info(f'CONNECT {host}:{port} from {addr[0]}')
            handle_connect(client, host, port)
        else:
            log.info(f'{method} {url} from {addr[0]}')
            handle_http(client, method, url, version, headers, body)
    except Exception as e:
        log.debug(f'Client error {addr}: {e}')
        try:
            client.close()
        except Exception:
            pass

def try_bind(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except (AttributeError, OSError):
        pass
    try:
        s.bind((BIND_HOST, port))
        return s
    except OSError:
        s.close()
        return None

def run():
    import random
    server = try_bind(BIND_PORT)
    actual_port = BIND_PORT
    if server is None:
        log.warning(f'Port {BIND_PORT} is busy, searching for free port...')
        for _ in range(50):
            p = random.randint(8000, 9999)
            server = try_bind(p)
            if server:
                actual_port = p
                log.warning(f'Using random port: {actual_port}')
                break
    if server is None:
        log.error('Could not bind to any port in range 8000-9999')
        sys.exit(1)
    with open('${PYTHON_PROXY_PID_FILE}.port', 'w') as f:
        f.write(str(actual_port))
    server.listen(256)
    log.info(f'HTTP Proxy listening on {BIND_HOST}:{actual_port}')

    def shutdown(sig, frame):
        log.info('Shutting down...')
        server.close()
        sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    while True:
        try:
            client, addr = server.accept()
            t = threading.Thread(target=handle_client, args=(client, addr), daemon=True)
            t.start()
        except OSError:
            break

if __name__ == '__main__':
    run()
PYEOF
    chmod +x "$PYTHON_PROXY_SCRIPT"
    log_success "Python proxy script generated"
}

start_proxy_python() {
    log_status "Starting Python HTTP proxy on port $PROXY_PORT..."
    generate_python_proxy

    if [[ -f "$PYTHON_PROXY_PID_FILE" ]]; then
        local old
        old=$(cat "$PYTHON_PROXY_PID_FILE" 2>/dev/null)
        if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
            kill "$old" 2>/dev/null; sleep 1
            kill -0 "$old" 2>/dev/null && kill -9 "$old" 2>/dev/null
        fi
        rm -f "$PYTHON_PROXY_PID_FILE" "${PYTHON_PROXY_PID_FILE}.port"
    fi
    pkill -f "python3.*http_proxy.py" 2>/dev/null
    sleep 0.5

    python3 "$PYTHON_PROXY_SCRIPT" "$PROXY_PORT" >> "$PYTHON_PROXY_LOG" 2>&1 &
    local pid=$!
    echo "$pid" > "$PYTHON_PROXY_PID_FILE"

    local i actual_port=""
    for i in 1 2 3 4 5 6 7 8; do
        sleep 1
        if ! kill -0 "$pid" 2>/dev/null; then
            log_error "Python proxy process died"
            [[ -f "$PYTHON_PROXY_LOG" ]] && tail -5 "$PYTHON_PROXY_LOG" | sed 's/^/  /'
            rm -f "$PYTHON_PROXY_PID_FILE"
            return 1
        fi
        if [[ -f "${PYTHON_PROXY_PID_FILE}.port" ]]; then
            actual_port=$(cat "${PYTHON_PROXY_PID_FILE}.port" 2>/dev/null)
            [[ -n "$actual_port" ]] && break
        fi
        log_debug "Waiting for proxy to start... ($i/8)"
    done

    if ! kill -0 "$pid" 2>/dev/null; then
        log_error "Python proxy failed to start"
        [[ -f "$PYTHON_PROXY_LOG" ]] && tail -5 "$PYTHON_PROXY_LOG" | sed 's/^/  /'
        rm -f "$PYTHON_PROXY_PID_FILE"
        return 1
    fi

    if [[ -n "$actual_port" && "$actual_port" != "$PROXY_PORT" ]]; then
        log_warning "Port $PROXY_PORT was busy — proxy running on port $actual_port"
        PROXY_PORT=$actual_port
    fi

    log_success "Python proxy started (pid=$pid port=$PROXY_PORT)"
    return 0
}

stop_proxy_python() {
    if [[ -f "$PYTHON_PROXY_PID_FILE" ]]; then
        local pid
        pid=$(cat "$PYTHON_PROXY_PID_FILE" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null; sleep 1
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$PYTHON_PROXY_PID_FILE" "${PYTHON_PROXY_PID_FILE}.port"
    fi
    pkill -f "python3.*http_proxy.py" 2>/dev/null
}

generate_tinyproxy_config() {
    local ip=$1
    local net
    net=$(calc_network "$ip" 24)
    log_status "Generating tinyproxy config..."
    {
        echo "Port $PROXY_PORT"
        echo "Listen 0.0.0.0"
        echo "Timeout 60"
        echo "MaxClients $MAX_CLIENTS"
        echo "Allow 127.0.0.1"
        echo "Allow $net"
        echo "Allow 192.168.0.0/16"
        echo "Allow 10.0.0.0/8"
        echo "Allow 172.16.0.0/12"
        echo "DisableViaHeader Yes"
        echo "LogLevel Info"
        echo "LogFile \"$TINYPROXY_LOG\""
        if [[ "$ENABLE_AUTH" -eq 1 && -n "$PROXY_USER" && -n "$PROXY_PASS" ]]; then
            echo "BasicAuth $PROXY_USER $PROXY_PASS"
        fi
    } > "$CONFIG_FILE"
    log_success "Tinyproxy config ready"
}

start_proxy_tinyproxy() {
    log_status "Starting tinyproxy on port $PROXY_PORT..."
    generate_tinyproxy_config "$HOTSPOT_IP"

    if [[ -f "$TINYPROXY_PID_FILE" ]]; then
        local old
        old=$(cat "$TINYPROXY_PID_FILE" 2>/dev/null)
        if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
            kill "$old" 2>/dev/null; sleep 1
        fi
        rm -f "$TINYPROXY_PID_FILE"
    fi
    pkill -f "tinyproxy.*$CONFIG_FILE" 2>/dev/null
    sleep 0.5

    tinyproxy -c "$CONFIG_FILE" -d >> "$TINYPROXY_LOG" 2>&1 &
    local pid=$!
    echo "$pid" > "$TINYPROXY_PID_FILE"

    local i
    for i in 1 2 3 4 5; do
        sleep 1
        if kill -0 "$pid" 2>/dev/null; then
            if ss -tlnp 2>/dev/null | grep -q ":$PROXY_PORT"; then
                log_success "Tinyproxy started (pid=$pid port=$PROXY_PORT)"
                return 0
            fi
            log_debug "Waiting for port... ($i/5)"
        else
            break
        fi
    done

    if kill -0 "$pid" 2>/dev/null; then
        log_success "Tinyproxy started (pid=$pid)"
        return 0
    fi

    log_error "tinyproxy failed to start — trying python fallback"
    [[ -f "$TINYPROXY_LOG" ]] && tail -5 "$TINYPROXY_LOG" | sed 's/^/  /'
    rm -f "$TINYPROXY_PID_FILE"
    PROXY_METHOD="python"
    start_proxy_python
}

stop_proxy_tinyproxy() {
    if [[ -f "$TINYPROXY_PID_FILE" ]]; then
        local pid
        pid=$(cat "$TINYPROXY_PID_FILE" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null; sleep 1
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$TINYPROXY_PID_FILE"
    fi
    pkill -f "tinyproxy.*$CONFIG_FILE" 2>/dev/null
}

generate_privoxy_config() {
    log_status "Generating privoxy config..."
    {
        echo "listen-address  0.0.0.0:$PROXY_PORT"
        echo "toggle  1"
        echo "enable-remote-toggle  0"
        echo "enable-remote-http-toggle  0"
        echo "enable-edit-actions  0"
        echo "enforce-blocks  0"
        echo "buffer-limit  4096"
        echo "forwarded-connect-retries  0"
        echo "accept-intercepted-requests  1"
        echo "allow-cgi-request-crunching  0"
        echo "split-large-forms  0"
        echo "keep-alive-timeout  5"
        echo "socket-timeout  60"
        echo "logfile $PRIVOXY_LOG"
        echo "logdir $SCRIPT_DIR"
        echo "actionsfile match-all.action"
        echo "actionsfile default.action"
        echo "filterfile default.filter"
    } > "$PRIVOXY_CONFIG"
    log_success "Privoxy config ready"
}

start_proxy_privoxy() {
    log_status "Starting privoxy on port $PROXY_PORT..."
    generate_privoxy_config

    if [[ -f "$PRIVOXY_PID_FILE" ]]; then
        local old
        old=$(cat "$PRIVOXY_PID_FILE" 2>/dev/null)
        if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
            kill "$old" 2>/dev/null; sleep 1
        fi
        rm -f "$PRIVOXY_PID_FILE"
    fi
    pkill -f "privoxy.*$PRIVOXY_CONFIG" 2>/dev/null
    sleep 0.5

    privoxy --no-daemon "$PRIVOXY_CONFIG" >> "$PRIVOXY_LOG" 2>&1 &
    local pid=$!
    echo "$pid" > "$PRIVOXY_PID_FILE"

    local i
    for i in 1 2 3 4 5; do
        sleep 1
        if kill -0 "$pid" 2>/dev/null; then
            if ss -tlnp 2>/dev/null | grep -q ":$PROXY_PORT"; then
                log_success "Privoxy started (pid=$pid port=$PROXY_PORT)"
                return 0
            fi
        else
            break
        fi
    done

    log_error "Privoxy failed — falling back to python"
    [[ -f "$PRIVOXY_LOG" ]] && tail -5 "$PRIVOXY_LOG" | sed 's/^/  /'
    rm -f "$PRIVOXY_PID_FILE"
    PROXY_METHOD="python"
    start_proxy_python
}

stop_proxy_privoxy() {
    if [[ -f "$PRIVOXY_PID_FILE" ]]; then
        local pid
        pid=$(cat "$PRIVOXY_PID_FILE" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null; sleep 1
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$PRIVOXY_PID_FILE"
    fi
    pkill -f "privoxy.*$PRIVOXY_CONFIG" 2>/dev/null
}

generate_3proxy_config() {
    log_status "Generating 3proxy config..."
    local auth_line="auth none"
    local users_line=""
    if [[ "$ENABLE_AUTH" -eq 1 && -n "$PROXY_USER" && -n "$PROXY_PASS" ]]; then
        auth_line="auth strong"
        users_line="users ${PROXY_USER}:CL:${PROXY_PASS}"
    fi
    {
        echo "nscache 65536"
        echo "nscache6 65536"
        echo "timeouts 1 5 30 60 180 1800 15 60"
        echo "log $SCRIPT_DIR/3proxy.log D"
        echo "logformat \"- +_L%t.%. %N.%p %E %U %C:%c %R:%r %O %I %h %T\""
        [[ -n "$users_line" ]] && echo "$users_line"
        echo "$auth_line"
        echo "allow *"
        echo "proxy -p$PROXY_PORT -i0.0.0.0 -e0.0.0.0"
        if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
            echo "socks -p$SOCKS5_PORT -i0.0.0.0 -e0.0.0.0"
        fi
    } > "$SCRIPT_DIR/3proxy.cfg"
    log_success "3proxy config ready"
}

start_proxy_3proxy() {
    log_status "Starting 3proxy on port $PROXY_PORT..."
    generate_3proxy_config

    local pid_file="$SCRIPT_DIR/3proxy.pid"
    if [[ -f "$pid_file" ]]; then
        local old
        old=$(cat "$pid_file" 2>/dev/null)
        if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
            kill "$old" 2>/dev/null; sleep 1
        fi
        rm -f "$pid_file"
    fi

    3proxy "$SCRIPT_DIR/3proxy.cfg" >> "$SCRIPT_DIR/3proxy.log" 2>&1 &
    local pid=$!
    echo "$pid" > "$pid_file"

    sleep 2
    if kill -0 "$pid" 2>/dev/null; then
        log_success "3proxy started (pid=$pid port=$PROXY_PORT)"
        return 0
    fi

    log_error "3proxy failed — falling back to python"
    PROXY_METHOD="python"
    start_proxy_python
}

stop_proxy_3proxy() {
    local pid_file="$SCRIPT_DIR/3proxy.pid"
    if [[ -f "$pid_file" ]]; then
        local pid
        pid=$(cat "$pid_file" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null; sleep 1
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$pid_file"
    fi
    pkill -f "3proxy" 2>/dev/null
}

start_proxy_socat() {
    log_status "Starting socat HTTP proxy on port $PROXY_PORT..."
    log_warning "socat mode: basic TCP forward only — use python for full HTTP proxy"

    if [[ -f "$SCRIPT_DIR/socat.pid" ]]; then
        local old
        old=$(cat "$SCRIPT_DIR/socat.pid" 2>/dev/null)
        if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
            kill "$old" 2>/dev/null; sleep 1
        fi
        rm -f "$SCRIPT_DIR/socat.pid"
    fi

    socat TCP-LISTEN:$PROXY_PORT,fork,reuseaddr TCP:localhost:$PROXY_PORT >> "$SCRIPT_DIR/socat.log" 2>&1 &
    local pid=$!
    echo "$pid" > "$SCRIPT_DIR/socat.pid"

    sleep 1
    if kill -0 "$pid" 2>/dev/null; then
        log_warning "socat relay started — recommend using --method=python instead"
        log_success "socat started (pid=$pid)"
        return 0
    fi

    log_error "socat failed — falling back to python"
    PROXY_METHOD="python"
    start_proxy_python
}

stop_proxy_socat() {
    if [[ -f "$SCRIPT_DIR/socat.pid" ]]; then
        local pid
        pid=$(cat "$SCRIPT_DIR/socat.pid" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null; sleep 1
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$SCRIPT_DIR/socat.pid"
    fi
    pkill -f "socat TCP-LISTEN:$PROXY_PORT" 2>/dev/null
}

start_proxy() {
    case "$PROXY_METHOD" in
        tinyproxy) start_proxy_tinyproxy ;;
        privoxy)   start_proxy_privoxy ;;
        3proxy)    start_proxy_3proxy ;;
        socat)     start_proxy_socat ;;
        python|*)  start_proxy_python ;;
    esac
}

stop_proxy() {
    stop_proxy_python
    stop_proxy_tinyproxy
    stop_proxy_privoxy
    stop_proxy_3proxy
    stop_proxy_socat
}

generate_dante_config() {
    local iface=$1
    log_status "Generating dante SOCKS5 config..."
    {
        echo "logoutput: $DANTE_LOG"
        echo "internal: 0.0.0.0 port = $SOCKS5_PORT"
        echo "external: $iface"
        echo "clientmethod: none"
        echo "user.notprivileged: nobody"
        echo ""
        echo "client pass {"
        echo "    from: 0.0.0.0/0 to: 0.0.0.0/0"
        echo "    log: error"
        echo "}"
        echo ""
        echo "socks pass {"
        echo "    from: 0.0.0.0/0 to: 0.0.0.0/0"
        echo "    command: bind connect udpassociate"
        echo "    log: error"
        if [[ "$ENABLE_AUTH" -eq 1 && -n "$PROXY_USER" ]]; then
            echo "    socksmethod: username"
        else
            echo "    socksmethod: none"
        fi
        echo "}"
    } > "$DANTE_CONFIG"
    log_success "SOCKS5 config ready"
}

start_socks5() {
    [[ "$ENABLE_SOCKS5" -ne 1 ]] && return 0

    if command -v microsocks >/dev/null 2>&1; then
        log_status "Starting microsocks on port $SOCKS5_PORT..."
        local args=("-p" "$SOCKS5_PORT")
        [[ "$ENABLE_AUTH" -eq 1 && -n "$PROXY_USER" ]] && args+=("-u" "$PROXY_USER" "-P" "$PROXY_PASS")

        if [[ -f "$MICROSOCKS_PID_FILE" ]]; then
            local old
            old=$(cat "$MICROSOCKS_PID_FILE" 2>/dev/null)
            if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
                kill "$old" 2>/dev/null; sleep 1
            fi
            rm -f "$MICROSOCKS_PID_FILE"
        fi

        microsocks "${args[@]}" >> "$DANTE_LOG" 2>&1 &
        local pid=$!
        echo "$pid" > "$MICROSOCKS_PID_FILE"
        sleep 1
        if kill -0 "$pid" 2>/dev/null; then
            log_success "microsocks SOCKS5 started (pid=$pid port=$SOCKS5_PORT)"
            return 0
        fi
        rm -f "$MICROSOCKS_PID_FILE"
    fi

    local sockd_bin
    sockd_bin=$(command -v sockd 2>/dev/null || command -v danted 2>/dev/null)
    if [[ -z "$sockd_bin" ]]; then
        log_warning "No SOCKS5 server available — SOCKS5 disabled"
        ENABLE_SOCKS5=0
        return 0
    fi

    if [[ -f "$DANTE_PID_FILE" ]]; then
        local old
        old=$(cat "$DANTE_PID_FILE" 2>/dev/null)
        if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
            kill "$old" 2>/dev/null; sleep 1
        fi
        rm -f "$DANTE_PID_FILE"
    fi

    generate_dante_config "$HOTSPOT_IFACE"
    log_status "Starting dante on port $SOCKS5_PORT..."
    "$sockd_bin" -f "$DANTE_CONFIG" -D >> "$DANTE_LOG" 2>&1 &
    local pid=$!
    echo "$pid" > "$DANTE_PID_FILE"
    sleep 2

    if kill -0 "$pid" 2>/dev/null; then
        log_success "dante SOCKS5 started (pid=$pid port=$SOCKS5_PORT)"
        return 0
    fi

    log_warning "SOCKS5 failed to start"
    [[ -f "$DANTE_LOG" ]] && tail -3 "$DANTE_LOG" | sed 's/^/  /'
    rm -f "$DANTE_PID_FILE"
    ENABLE_SOCKS5=0
    return 0
}

stop_socks5() {
    if [[ -f "$MICROSOCKS_PID_FILE" ]]; then
        local pid
        pid=$(cat "$MICROSOCKS_PID_FILE" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null; sleep 1
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$MICROSOCKS_PID_FILE"
    fi
    if [[ -f "$DANTE_PID_FILE" ]]; then
        local pid
        pid=$(cat "$DANTE_PID_FILE" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null; sleep 1
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$DANTE_PID_FILE"
    fi
    pkill -f "microsocks" 2>/dev/null
}

setup_transparent_proxy() {
    [[ "$ENABLE_TRANSPARENT" -ne 1 ]] && return 0
    if [[ "$IS_ROOT" -ne 1 ]]; then
        log_warning "Transparent proxy requires root — skipping"
        ENABLE_TRANSPARENT=0; return 0
    fi
    if ! command -v iptables >/dev/null 2>&1; then
        log_warning "iptables not available — transparent proxy skipped"
        ENABLE_TRANSPARENT=0; return 0
    fi

    log_status "Setting up transparent proxy (iptables)..."
    iptables -t nat -N VPSH 2>/dev/null
    iptables -t nat -F VPSH 2>/dev/null

    local net
    for net in 0.0.0.0/8 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 \
               172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
        iptables -t nat -A VPSH -d "$net" -j RETURN
    done

    iptables -t nat -A VPSH -p tcp -j REDIRECT --to-ports "$PROXY_PORT"
    iptables -t nat -A PREROUTING -i "$HOTSPOT_IFACE" -p tcp -j VPSH
    log_success "Transparent proxy active on $HOTSPOT_IFACE"
    [[ "$PROFILE" == "gaming" ]] && setup_gaming_qos
}

teardown_transparent_proxy() {
    [[ "$IS_ROOT" -ne 1 ]] && return
    command -v iptables >/dev/null 2>&1 || return
    log_status "Clearing iptables rules..."
    iptables -t nat -D PREROUTING -i "$HOTSPOT_IFACE" -p tcp -j VPSH 2>/dev/null
    iptables -t nat -F VPSH 2>/dev/null
    iptables -t nat -X VPSH 2>/dev/null
    [[ "$PROFILE" == "gaming" ]] && teardown_gaming_qos
    log_success "iptables cleared"
}

setup_gaming_qos() {
    [[ "$IS_ROOT" -ne 1 ]] && return
    command -v tc >/dev/null 2>&1 || return
    local wan
    wan=$(ip route 2>/dev/null | awk '/^default/{print $5; exit}')
    [[ -z "$wan" ]] && return
    log_status "Applying QoS: UDP priority for gaming..."
    tc qdisc del dev "$wan" root 2>/dev/null
    tc qdisc add dev "$wan" root handle 1: prio bands 3 \
        priomap 0 0 0 0 1 1 1 1 2 2 2 2 2 2 2 2 2>/dev/null
    tc filter add dev "$wan" protocol ip parent 1:0 prio 1 u32 \
        match ip protocol 17 0xff flowid 1:1 2>/dev/null
    tc filter add dev "$wan" protocol ip parent 1:0 prio 2 u32 \
        match ip dport 443 0xffff flowid 1:2 2>/dev/null
    log_success "QoS applied (UDP > HTTPS > rest)"
}

teardown_gaming_qos() {
    [[ "$IS_ROOT" -ne 1 ]] && return
    command -v tc >/dev/null 2>&1 || return
    local wan
    wan=$(ip route 2>/dev/null | awk '/^default/{print $5; exit}')
    [[ -n "$wan" ]] && tc qdisc del dev "$wan" root 2>/dev/null
}

setup_tunnel() {
    [[ -z "$ENABLE_TUNNEL" ]] && return 0
    local tpid="$SCRIPT_DIR/tunnel.pid"

    case "$ENABLE_TUNNEL" in
        cloudflare)
            if ! command -v cloudflared >/dev/null 2>&1; then
                log_warning "cloudflared not found — skipping"
                return 0
            fi
            log_status "Starting Cloudflare tunnel..."
            cloudflared tunnel --url "http://localhost:$PROXY_PORT" \
                --no-autoupdate >/dev/null 2>&1 &
            echo "$!" > "$tpid"; sleep 3
            log_success "Cloudflare tunnel started"
            ;;
        ngrok)
            if ! command -v ngrok >/dev/null 2>&1; then
                log_warning "ngrok not found — skipping"
                return 0
            fi
            log_status "Starting ngrok..."
            ngrok http "$PROXY_PORT" --log=stdout > "$SCRIPT_DIR/ngrok.log" 2>&1 &
            echo "$!" > "$tpid"; sleep 3
            local url
            url=$(curl -s --max-time 3 http://localhost:4040/api/tunnels 2>/dev/null | \
                grep -oE '"public_url":"https://[^"]+' | head -1 | cut -d'"' -f4)
            [[ -n "$url" ]] && log_success "ngrok: $url" || log_warning "ngrok started (URL pending)"
            ;;
        tailscale)
            if ! command -v tailscale >/dev/null 2>&1; then
                log_warning "tailscale not found — skipping"
                return 0
            fi
            local ts_ip
            ts_ip=$(tailscale ip -4 2>/dev/null)
            [[ -n "$ts_ip" ]] && log_success "Tailscale: $ts_ip:$PROXY_PORT" || \
                log_warning "Tailscale not connected — run: tailscale up"
            ;;
        zerotier)
            if ! command -v zerotier-cli >/dev/null 2>&1; then
                log_warning "zerotier-cli not found — skipping"
                return 0
            fi
            local zt_ip
            zt_ip=$(ip addr 2>/dev/null | awk '/inet /{ip=$2; sub("/.*","",ip)} /zt[a-z0-9]/{print ip; exit}')
            [[ -n "$zt_ip" ]] && log_success "ZeroTier: $zt_ip" || \
                log_warning "ZeroTier not connected"
            ;;
        *)
            log_warning "Unknown tunnel: $ENABLE_TUNNEL"
            ;;
    esac
}

stop_tunnel() {
    local tpid="$SCRIPT_DIR/tunnel.pid"
    [[ -f "$tpid" ]] || return
    local pid
    pid=$(cat "$tpid" 2>/dev/null)
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null
    rm -f "$tpid"
}

test_proxy() {
    local ip=$1
    log_status "Testing proxy..."
    local curl_args=(-x "http://$ip:$PROXY_PORT" -s --max-time 8 -o /dev/null -w "%{http_code}")
    [[ "$ENABLE_AUTH" -eq 1 && -n "$PROXY_USER" ]] && \
        curl_args+=(--proxy-user "$PROXY_USER:$PROXY_PASS")

    local code
    code=$(curl "${curl_args[@]}" https://www.google.com 2>/dev/null)
    if [[ "$code" =~ ^(200|301|302|307|308)$ ]]; then
        log_success "HTTP proxy OK (HTTP $code)"
    else
        log_warning "HTTP proxy test inconclusive (code=$code) — may still work on LAN"
    fi

    if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
        if curl -x "socks5h://$ip:$SOCKS5_PORT" -s --max-time 8 \
               https://www.google.com >/dev/null 2>&1; then
            log_success "SOCKS5 proxy OK"
        else
            log_warning "SOCKS5 test failed"
        fi
    fi
}

health_check() {
    local ts
    ts=$(date '+%Y-%m-%d %H:%M:%S')

    local active_pid=""
    case "$PROXY_METHOD" in
        python|*)  [[ -f "$PYTHON_PROXY_PID_FILE" ]] && active_pid=$(cat "$PYTHON_PROXY_PID_FILE" 2>/dev/null) ;;
        tinyproxy) [[ -f "$TINYPROXY_PID_FILE" ]] && active_pid=$(cat "$TINYPROXY_PID_FILE" 2>/dev/null) ;;
        privoxy)   [[ -f "$PRIVOXY_PID_FILE" ]] && active_pid=$(cat "$PRIVOXY_PID_FILE" 2>/dev/null) ;;
        3proxy)    [[ -f "$SCRIPT_DIR/3proxy.pid" ]] && active_pid=$(cat "$SCRIPT_DIR/3proxy.pid" 2>/dev/null) ;;
    esac

    if [[ -z "$active_pid" ]] || ! kill -0 "$active_pid" 2>/dev/null; then
        echo "[$ts] HTTP proxy down (pid=$active_pid method=$PROXY_METHOD)" >> "$HEALTH_LOG"
        if [[ "$AUTO_RESTART" -eq 1 ]]; then
            if [[ "$RESTART_ATTEMPTS" -lt "$MAX_RESTART_ATTEMPTS" ]]; then
                RESTART_ATTEMPTS=$((RESTART_ATTEMPTS + 1))
                log_warning "HTTP proxy down — restarting ($RESTART_ATTEMPTS/$MAX_RESTART_ATTEMPTS)"
                start_proxy
                echo "[$ts] HTTP proxy restarted" >> "$HEALTH_LOG"
            else
                log_error "Max restarts reached ($MAX_RESTART_ATTEMPTS)"
            fi
        fi
    else
        RESTART_ATTEMPTS=0
        log_debug "Health OK: proxy pid=$active_pid method=$PROXY_METHOD"
    fi

    if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
        local dp=""
        [[ -f "$MICROSOCKS_PID_FILE" ]] && dp=$(cat "$MICROSOCKS_PID_FILE" 2>/dev/null)
        [[ -z "$dp" && -f "$DANTE_PID_FILE" ]] && dp=$(cat "$DANTE_PID_FILE" 2>/dev/null)
        if [[ -z "$dp" ]] || ! kill -0 "$dp" 2>/dev/null; then
            echo "[$ts] SOCKS5 proxy down" >> "$HEALTH_LOG"
            if [[ "$AUTO_RESTART" -eq 1 ]]; then
                log_warning "SOCKS5 proxy down — restarting"
                start_socks5
                echo "[$ts] SOCKS5 restarted" >> "$HEALTH_LOG"
            fi
        fi
    fi
}

generate_qr() {
    local url=$1
    if command -v qrencode >/dev/null 2>&1; then
        qrencode -t ANSIUTF8 "$url" 2>/dev/null
    elif python3 -c "import qrcode" 2>/dev/null; then
        python3 - "$url" <<'PYEOF'
import sys, qrcode
qr = qrcode.QRCode(border=1)
qr.add_data(sys.argv[1])
qr.make(fit=True)
qr.print_ascii(invert=True)
PYEOF
    else
        log_status "(install qrencode or: pip install qrcode — for QR support)"
    fi
}

display_info() {
    local mode auth_str=""
    mode=$(detect_share_mode "$HOTSPOT_IFACE")
    [[ "$ENABLE_AUTH" -eq 1 && -n "$PROXY_USER" ]] && auth_str="$PROXY_USER:$PROXY_PASS@"
    local proxy_url="http://${auth_str}${HOTSPOT_IP}:${PROXY_PORT}"

    echo
    echo -e "${BOLD}╔══════════════════════════════════════════════════════════╗${RESET}"
    printf "${BOLD}║${RESET}  ${GREEN}%-56s${RESET}${BOLD}║${RESET}\n" "$PROJECT_FULL  v$VERSION"
    printf "${BOLD}║${RESET}  %-56s${BOLD}║${RESET}\n" "Mode: $mode  |  Interface: $HOTSPOT_IFACE"
    printf "${BOLD}║${RESET}  %-56s${BOLD}║${RESET}\n" "Method: $PROXY_METHOD"
    echo -e "${BOLD}╠══════════════════════════════════════════════════════════╣${RESET}"
    printf "${BOLD}║${RESET}  ${CYAN}%-56s${RESET}${BOLD}║${RESET}\n" "HTTP Proxy:  $HOTSPOT_IP:$PROXY_PORT"
    if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
        printf "${BOLD}║${RESET}  ${CYAN}%-56s${RESET}${BOLD}║${RESET}\n" "SOCKS5:      $HOTSPOT_IP:$SOCKS5_PORT"
    fi
    [[ "$ENABLE_AUTH" -eq 1 ]]        && printf "${BOLD}║${RESET}  %-56s${BOLD}║${RESET}\n" "Auth:        user=$PROXY_USER"
    [[ "$ENABLE_TRANSPARENT" -eq 1 ]] && printf "${BOLD}║${RESET}  %-56s${BOLD}║${RESET}\n" "Transparent: active (iptables)"
    [[ -n "$ENABLE_TUNNEL" ]]         && printf "${BOLD}║${RESET}  %-56s${BOLD}║${RESET}\n" "Tunnel:      $ENABLE_TUNNEL"
    echo -e "${BOLD}╠══════════════════════════════════════════════════════════╣${RESET}"
    echo -e "${BOLD}║${RESET}  Android / iOS — WiFi Settings > Proxy > Manual          ${BOLD}║${RESET}"
    printf "${BOLD}║${RESET}    %-54s${BOLD}║${RESET}\n" "Host: $HOTSPOT_IP   Port: $PROXY_PORT"
    [[ "$ENABLE_AUTH" -eq 1 ]] && printf "${BOLD}║${RESET}    %-54s${BOLD}║${RESET}\n" "User: $PROXY_USER   Pass: $PROXY_PASS"
    echo -e "${BOLD}║${RESET}                                                          ${BOLD}║${RESET}"
    echo -e "${BOLD}║${RESET}  Linux / macOS:                                          ${BOLD}║${RESET}"
    printf "${BOLD}║${RESET}    %-54s${BOLD}║${RESET}\n" "export http_proxy=$proxy_url"
    printf "${BOLD}║${RESET}    %-54s${BOLD}║${RESET}\n" "export https_proxy=$proxy_url"
    if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
        echo -e "${BOLD}║${RESET}                                                          ${BOLD}║${RESET}"
        echo -e "${BOLD}║${RESET}  SOCKS5 — for games / SSH / all traffic:                 ${BOLD}║${RESET}"
        printf "${BOLD}║${RESET}    %-54s${BOLD}║${RESET}\n" "Host: $HOTSPOT_IP   Port: $SOCKS5_PORT"
    fi
    echo -e "${BOLD}║${RESET}                                                          ${BOLD}║${RESET}"
    echo -e "${BOLD}║${RESET}  Commands: stop | restart | status | health | logs       ${BOLD}║${RESET}"
    echo -e "${BOLD}╚══════════════════════════════════════════════════════════╝${RESET}"
    echo
    echo "  Proxy URL: $proxy_url"
    echo
    generate_qr "$proxy_url"
    echo
}

get_proxy_clients() {
    ss -tn 2>/dev/null | awk -v p=":$PROXY_PORT" \
        'NR>1 && $1=="ESTAB" && $4~p { split($5,a,":"); if(a[1]!="") print a[1] }' | sort -u
}

on_signal() { RUNNING=0; }

run_loop() {
    trap on_signal SIGINT SIGTERM
    local last_health=0 now

    while [[ "$RUNNING" -eq 1 ]]; do
        now=$(date +%s)
        if (( now - last_health >= HEALTH_INTERVAL )); then
            health_check
            last_health=$now
        fi
        sleep 2
    done

    trap '' SIGINT SIGTERM
    do_stop
}

do_start() {
    echo
    echo -e "${BOLD}  $PROJECT — $PROJECT_FULL  v$VERSION${RESET}"
    echo

    if [[ -f "$PID_FILE" ]]; then
        local old_pid
        old_pid=$(cat "$PID_FILE" 2>/dev/null)
        if [[ -n "$old_pid" ]] && kill -0 "$old_pid" 2>/dev/null; then
            log_warning "Already running (pid $old_pid) — run: ./vpsh.sh stop"
            return 1
        fi
        log_warning "Removing stale pid file"
        rm -f "$PID_FILE"
        clear_state
    elif [[ -f "$STATE_FILE" ]]; then
        clear_state
    fi

    detect_root
    if [[ "$IS_ROOT" -eq 1 ]]; then
        log_success "Root access — all features available"
    else
        log_warning "Non-root mode — iptables/QoS unavailable"
        ENABLE_TRANSPARENT=0
    fi

    apply_profile
    check_and_install_dependencies || return 1
    [[ "$ENABLE_AUTH" -eq 1 ]] && { setup_auth || return 1; }

    local iface_line
    if ! iface_line=$(find_share_interface); then
        log_error "No sharing interface found"
        echo
        echo "  Possible reasons:"
        echo "    1. Hotspot or USB tethering is not active"
        echo "    2. No IP address assigned to the interface"
        echo "    3. Permission issue (try: su -c ./vpsh.sh)"
        echo
        echo "  Available interfaces:"
        list_interfaces | sed 's/^/    /'
        echo
        echo "  Force with: --hotspot=wlan0"
        return 1
    fi

    read -r HOTSPOT_IFACE HOTSPOT_IP _ <<< "$iface_line"
    local mode
    mode=$(detect_share_mode "$HOTSPOT_IFACE")
    log_success "Detected: $mode via $HOTSPOT_IFACE ($HOTSPOT_IP)"
    log_info "Proxy method: $PROXY_METHOD"

    if [[ "$PORT_SPECIFIED" -eq 0 ]]; then
        PROXY_PORT=$((8000 + RANDOM % 2000))
        log_info "Random HTTP port selected: $PROXY_PORT"
        if ! is_port_free "$PROXY_PORT"; then
            log_warning "Port $PROXY_PORT is busy, finding free port..."
            PROXY_PORT=$(find_free_port)
        fi
    else
        local free_port
        free_port=$(find_free_port "$PROXY_PORT") || return 1
        PROXY_PORT=$free_port
    fi

    if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
        if [[ "$SOCKS5_PORT_SPECIFIED" -eq 0 ]]; then
            SOCKS5_PORT=$((1080 + RANDOM % 1000))
            log_info "Random SOCKS5 port selected: $SOCKS5_PORT"
            if ! is_port_free "$SOCKS5_PORT"; then
                log_warning "Port $SOCKS5_PORT is busy, finding free port..."
                SOCKS5_PORT=$(find_free_port)
            fi
        else
            local free_socks_port
            free_socks_port=$(find_free_port "$SOCKS5_PORT") || return 1
            SOCKS5_PORT=$free_socks_port
        fi
    fi

    start_proxy || return 1

    if [[ "$ENABLE_SOCKS5" -eq 1 ]]; then
        start_socks5
    fi

    [[ "$ENABLE_TRANSPARENT" -eq 1 ]] && setup_transparent_proxy
    [[ -n "$ENABLE_TUNNEL" ]]         && setup_tunnel

    test_proxy "$HOTSPOT_IP"
    save_state
    display_info

    echo "$$" > "$PID_FILE"
    run_loop
}

do_stop() {
    log_info "Stopping $PROJECT..."
    load_state
    [[ "$ENABLE_TRANSPARENT" -eq 1 ]] && teardown_transparent_proxy
    stop_tunnel
    stop_socks5
    stop_proxy
    rm -f "$PID_FILE"
    clear_state
    log_success "$PROJECT stopped"
}

do_status() {
    if [[ ! -f "$STATE_FILE" ]]; then
        log_error "$PROJECT is not running"
        return 1
    fi
    load_state
    display_info

    local clients
    clients=$(get_proxy_clients)
    echo "  Connected clients:"
    if [[ -n "$clients" ]]; then
        echo "$clients" | while read -r c; do echo "    $c"; done
    else
        echo "    (none)"
    fi
    echo
}

do_health() {
    if [[ ! -f "$STATE_FILE" ]]; then
        log_error "Not running"
        return 1
    fi
    load_state
    log_info "Running health check..."
    health_check
    echo
    if [[ -f "$HEALTH_LOG" ]]; then
        echo "  Health log (last 20 entries):"
        tail -20 "$HEALTH_LOG" | sed 's/^/    /'
    else
        log_success "No issues recorded"
    fi
}

do_logs() {
    echo
    log_info "=== Active Method: $PROXY_METHOD ==="
    case "$PROXY_METHOD" in
        python|"")
            [[ -f "$PYTHON_PROXY_LOG" ]] && { log_info "=== Python Proxy Log ==="; tail -30 "$PYTHON_PROXY_LOG"; } || echo "  (no log yet)"
            ;;
        tinyproxy)
            [[ -f "$TINYPROXY_LOG" ]] && { log_info "=== Tinyproxy Log ==="; tail -30 "$TINYPROXY_LOG"; } || echo "  (no log yet)"
            ;;
        privoxy)
            [[ -f "$PRIVOXY_LOG" ]] && { log_info "=== Privoxy Log ==="; tail -30 "$PRIVOXY_LOG"; } || echo "  (no log yet)"
            ;;
        3proxy)
            [[ -f "$SCRIPT_DIR/3proxy.log" ]] && { log_info "=== 3proxy Log ==="; tail -30 "$SCRIPT_DIR/3proxy.log"; } || echo "  (no log yet)"
            ;;
    esac

    if [[ "$ENABLE_SOCKS5" -eq 1 || -f "$DANTE_LOG" ]]; then
        echo
        log_info "=== SOCKS5 Log ==="
        [[ -f "$DANTE_LOG" ]] && tail -20 "$DANTE_LOG" || echo "  (no log)"
    fi

    if [[ -f "$HEALTH_LOG" ]]; then
        echo
        log_info "=== Health Log ==="
        tail -20 "$HEALTH_LOG"
    fi
}

print_usage() {
    cat <<USAGE

  $PROJECT — $PROJECT_FULL  v$VERSION

Usage: ./vpsh.sh [command] [options]

Commands:
  start     Start proxy (default)
  stop      Stop proxy
  restart   Restart proxy
  status    Show status and clients
  health    Run health check
  logs      Show logs
  update    Check and install updates

Options:
  --method=METHOD       Proxy method: python (default) | tinyproxy | privoxy | 3proxy | socat
  --hotspot=IFACE       Force interface (e.g. wlan0, usb0, ap0)
  --port=PORT           HTTP proxy port (default: 8888)
  --socks5-port=PORT    SOCKS5 port (default: 1080)
  --profile=PROFILE     default | gaming | streaming | secure
  --socks5              Enable SOCKS5
  --auth                Enable authentication
  --transparent         Transparent proxy via iptables (root only)
  --tunnel=TYPE         cloudflare | ngrok | tailscale | zerotier
  --no-auto-restart     Disable crash auto-restart
  --debug               Verbose output

Proxy Methods:
  python      Built-in Python3 proxy — no install needed (default)
  tinyproxy   Lightweight C proxy — pkg install tinyproxy
  privoxy     Feature-rich proxy — pkg install privoxy
  3proxy      Multi-protocol proxy — pkg install 3proxy
  socat       TCP relay only (limited) — pkg install socat

Profiles:
  default    HTTP proxy, standard settings
  gaming     + SOCKS5, UDP QoS, faster health check
  streaming  High capacity
  secure     Auth + SOCKS5

Persistent config ($OVERRIDE_FILE):
  PROXY_METHOD=python
  PROXY_PORT=8888
  SOCKS5_PORT=1080
  PROFILE=default
  ENABLE_SOCKS5=0
  ENABLE_AUTH=0
  PROXY_USER=""
  PROXY_PASS=""
  ENABLE_TRANSPARENT=0
  ENABLE_TUNNEL=""
  AUTO_RESTART=1
  HEALTH_INTERVAL=30
  HOTSPOT_IFACE_OVERRIDE=""

USAGE
}

parse_args() {
    for arg in "$@"; do
        case "$arg" in
            --method=*)
                PROXY_METHOD="${arg#*=}"
                ;;
            --hotspot=*)
                HOTSPOT_IFACE_OVERRIDE="${arg#*=}"
                ;;
            --port=*)
                PROXY_PORT="${arg#*=}"
                PORT_SPECIFIED=1
                ;;
            --socks5-port=*)
                SOCKS5_PORT="${arg#*=}"
                SOCKS5_PORT_SPECIFIED=1
                ;;
            --profile=*)
                PROFILE="${arg#*=}"
                ;;
            --tunnel=*)
                ENABLE_TUNNEL="${arg#*=}"
                ;;
            --socks5)
                ENABLE_SOCKS5=1
                ;;
            --auth)
                ENABLE_AUTH=1
                ;;
            --transparent)
                ENABLE_TRANSPARENT=1
                ;;
            --no-auto-restart)
                AUTO_RESTART=0
                ;;
            --debug)
                DEBUG=1
                ;;
        esac
    done
}

ACTION="${1:-start}"
shift 2>/dev/null || true
parse_args "$@"

case "$ACTION" in
    start)          do_start ;;
    stop)           do_stop ;;
    restart)        do_stop; sleep 2; do_start ;;
    status)         do_status ;;
    health)         do_health ;;
    logs)           do_logs ;;
    update)         do_update ;;
    help|-h|--help) print_usage ;;
    *)              log_error "Unknown command: $ACTION"; print_usage ;;
esac
