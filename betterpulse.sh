#!/bin/bash
set -euo pipefail

# Constants
CONFIG_FILE="$HOME/.config/vpn_config.ini"
COOKIE_FILE="/tmp/cookievpn"
VPNC_SCRIPT="$PWD/vpnc-script"
PREFIX_FILE="$HOME/.mobilepassprefix"

# Function to read INI file
get_ini_value() {
    local section=$1
    local key=$2
    sed -n "/^\[$section\]/,/^\[/p" "$CONFIG_FILE" | grep "^$key = " | head -n 1 | cut -d'=' -f2- | sed 's/^[ ]*//'
}

# Check if config file exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Configuration file not found at $CONFIG_FILE"
    exit 1
fi

# Load configuration
VPN_HOST=$(get_ini_value "vpn" "host")
VPN_URL=$(get_ini_value "vpn" "url")
VPN_REALM=$(get_ini_value "vpn" "realm")
VPN_USER_AGENT=$(get_ini_value "vpn" "user_agent")
SSH_HOST=$(get_ini_value "ssh_tunnel" "host")
SSH_NS=$(get_ini_value "ssh_tunnel" "nameserver")

# Load routes from config
IFS=',' read -ra ROUTES_TO_REPLACE <<< "$(get_ini_value "routes" "routes_to_replace")"
export ROUTES_TO_REPLACE

# Save routes to temp file
echo "${ROUTES_TO_REPLACE[*]}" | tr ' ' '\n' > /tmp/vpn_routes
chmod 600 /tmp/vpn_routes

spinner() {
    local pid=$1
    local delay=0.1
    local spinstr='|/-\'
    while ps a | awk '{print $1}' | grep -q "$pid"; do
        local temp=${spinstr#?}
        printf " [%c] " "$spinstr"
        local spinstr=$temp${spinstr%"$temp"}
        sleep $delay
        printf "\b\b\b\b\b\b"
    done
    printf " \b\b\b\b"
}

log() {
    echo -e "\n🔔 [$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

separator() {
    echo -e "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
}

cleanup() {
    separator
    log "🧹 Cleaning up..."
    if [[ -n "${PIDTOKILL:-}" ]]; then
        sudo kill -SIGTERM "$PIDTOKILL" 2>/dev/null || true
    fi
    rm -f "$COOKIE_FILE"
    rm -f /tmp/vpn_routes  # Ajout de cette ligne
}

trap cleanup EXIT

echo -e "\n🚀 Starting VPN Connection Script"
separator

log "🌐 VPN URL = $VPN_URL"

if [ -f "$COOKIE_FILE" ]; then
    log "🍪 Cookie file already exists. Using it to retrieve the DSID cookie"
else
    # Get CUID from username
    CUID=$(echo $USERNAME | tr '[:lower:]' '[:upper:]')
    echo -e "\n🔑 Please enter your credentials:"
    echo -n "Mobile pass token : "

    # Get prefix from file
    MOBILEPASSTOKEN_PREFIX=""
    if [ -f "$PREFIX_FILE" ]; then
        read -s MOBILEPASSTOKEN_PREFIX < "$PREFIX_FILE"
    fi

    read -s MOBILEPASSTOKEN
    echo
    separator
    log "🔄 Getting DSID cookie..."
    
    curl -L -v -k --cookie-jar "$COOKIE_FILE" "$VPN_URL" -X POST \
        -H "User-Agent: $VPN_USER_AGENT" \
        -H "Accept: $(get_ini_value "vpn" "accept")" \
        -H "Accept-Language: $(get_ini_value "vpn" "accept_language")" \
        -H "Accept-Encoding: $(get_ini_value "vpn" "accept_encoding")" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -H "Origin: $(get_ini_value "vpn" "origin")" \
        -H 'Connection: keep-alive' \
        -H "Referer: $VPN_URL" \
        -H 'Upgrade-Insecure-Requests: 1' \
        -H 'Sec-Fetch-Dest: document' \
        -H 'Sec-Fetch-Mode: navigate' \
        -H 'Sec-Fetch-Site: same-origin' \
        -H 'Sec-Fetch-User: ?1' \
        --data-raw "tz_offset=60&clientMAC=&username=$CUID&password=$MOBILEPASSTOKEN_PREFIX$MOBILEPASSTOKEN&realm=$VPN_REALM&btnSubmit=Se+connecter"
fi

separator

# Extract DSID from cookie file
DSID=$(grep DSID "$COOKIE_FILE" | awk -F' ' '{print $7}')



separator
log "🔌 Starting openconnect in background"
sudo openconnect -b --no-dtls --protocol=nc "$VPN_HOST" \
    --force-dpd 5 --disable-ipv6 --timestamp \
    --cookie="DSID=$DSID" \
    --script="$VPNC_SCRIPT"

log "⏳ Waiting for connection..."
sleep 5 & spinner $!
log "✅ Connection established"

PIDTOKILL=$(pgrep openconnect)
log "📍 OpenConnect PID: $PIDTOKILL"

separator

if [ "${1:-}" == "noshuttle" ]; then
    log "⏸️ Shuttle disabled, press Ctrl+C to exit"
    read -r -d '' _ </dev/tty
else
    log "🚇 Starting sshuttle"
    
    # Build network arguments
    network_args=()

    # Include networks
    IFS=',' read -ra NETWORKS <<< "$(get_ini_value "ssh_tunnel" "networks_include")"
    for network in "${NETWORKS[@]}"; do
        network_args+=("$(echo "$network" | tr -d ' ')")
    done

    # Exclude networks
    IFS=',' read -ra EXCLUDED <<< "$(get_ini_value "ssh_tunnel" "networks_exclude")"
    for network in "${EXCLUDED[@]}"; do
        network_args+=("-x" "$(echo "$network" | tr -d ' ')")
    done

    sshuttle -v -N --ns-hosts="$SSH_NS" \
        -r "$USER@$SSH_HOST" \
        "${network_args[@]}"
fi
