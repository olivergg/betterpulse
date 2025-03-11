# 🌐 BetterPulse VPN Script

An improved alternative to the Pulse Secure client, enabling VPN connection via OpenConnect with fine-grained and selective network routing.

## 🎯 Key Feature

Unlike most VPN configurations that route all internet traffic through the VPN tunnel, this script is designed to:

- Route **only** specific subnets through the VPN
- Maintain the rest of the traffic via the original default route
- Allow granular route configuration

This approach is particularly useful for:

- 🏢 Accessing corporate resources without impacting personal internet browsing
- ⚡ Optimizing performance by avoiding unnecessary traffic routing through VPN
- 🔒 Maintaining better security by clearly separating flows

## 🚀 Features

- 🔄 Automatic VPN connection via OpenConnect
- 🔑 Mobile token authentication support
- 🛣️ Custom routes configuration
- 🚇 Optional SSH tunnel via sshuttle
- 🧹 Clean disconnection handling
- ⚙️ INI file configuration

## 📋 Prerequisites

- OpenConnect
- sshuttle (optional, for SSH tunnel)
- curl
- sudo
- iproute2

## 🛣️ Route Management

### Configuration

Routes are defined in the `routes` section of the `vpn_config.ini` file:

```ini
[routes]
routes_to_replace = 10.0.0.0/24, 192.168.0.0/24
```

### Customization

The `vpnc-script` has been modified in the `set_ipv4_default_route()` function to:

1. Capture the existing default route
2. Add only specified routes to the VPN tunnel
3. Avoid creating a default route via VPN

To modify this behavior, you can adjust the function in `vpnc-script`:

```bash
set_ipv4_default_route() {
    $IPROUTE route | grep '^default' | fix_ip_get_output > "$DEFAULT_ROUTE_FILE"

    # Add routes from config file
    if [ -f /tmp/vpn_routes ]; then
        while read -r route; do
            [ -n "$route" ] && $IPROUTE route replace $(echo "$route" | tr -d ' ') dev "$TUNDEV"
        done < /tmp/vpn_routes
    fi

    $IPROUTE route flush cache 2>/dev/null
}
```

## ⚙️ Configuration

Create a directory ~/.config/betterpulse that will store the various configuration files.

Create a `~/.config/betterpulse/vpn_config.ini` file with the following structure:

```ini
[vpn]
host = vpn.example.com
url = https://vpn.example.com/
realm = Example
user_agent = Mozilla/5.0
accept = text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8
accept_language = en-US,en;q=0.5
accept_encoding = gzip, deflate, br
origin = https://vpn.example.com

[ssh_tunnel]
host = ssh.example.com
nameserver = 8.8.8.8
networks_include = 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
networks_exclude = 192.168.1.0/24

[routes]
routes_to_replace = 10.0.0.0/24, 192.168.0.0/24
```

## 🔧 Installation

1. Clone the repository:

```bash
git clone https://github.com/your-username/betterpulse.git
cd betterpulse
```

2. Make scripts executable:

```bash
chmod u+x betterpulse.sh vpnc-script
```

3. Copy the vpnc-script to your configuration folder:

```bash
cp vpnc-script ~/.config/betterpulse/
```

4. Edit the configuration file with your settings:

```bash
nano ~/.config/betterpulse/vpn_config.ini
```

## 📝 Usage

### Standard connection

```bash
./betterpulse.sh
```

### Connection without SSH tunnel

```bash
./betterpulse.sh noshuttle
```

## 🔑 Mobile Token

To save a mobile token prefix (optional):

```bash
echo "your-prefix" > ~/.config/betterpulse/.mobilepassprefix
```

## 🤝 Contributing

Contributions are welcome! Feel free to:

- 🐛 Open an issue
- 🔀 Submit a pull request
- 💡 Suggest improvements

## 📜 License

This project is under GPLv3 License. See the `LICENSE` file for more details.
