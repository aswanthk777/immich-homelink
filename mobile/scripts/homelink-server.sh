#!/usr/bin/env bash
# Home Link server side — run as root on the home WireGuard box (any Linux host on the LAN, e.g. a Raspberry Pi).
#
# Creates a *separate* WireGuard interface (wg1) for the Immich app so it never touches an
# existing wg0 or its firewall rules. Peers get a /32 in $NET.0/24 and are NATed onto the LAN,
# so every LAN host (the Immich server included) just sees this box's address.
#
#   sudo ./homelink-server.sh init                       # once: wg1 up on udp/51821, forwarding + NAT
#   sudo ./homelink-server.sh add "My phone"            # new peer, prints wg-quick config + QR
#   sudo ./homelink-server.sh add "Mom" --pubkey <key>   # peer whose key was made on the phone
#   sudo ./homelink-server.sh list | remove <name> | status
#
# Then forward udp/$PORT on your router to this box, and set PUBLIC to your public IP or DDNS name.
set -euo pipefail

IFACE=${IFACE:-wg1}
PORT=${PORT:-51821}
NET=${NET:-10.66.78}                    # tunnel /24
LAN_CIDR=${LAN_CIDR:-192.168.1.0/24}    # what phones may reach through the tunnel (your LAN)
PUBLIC=${PUBLIC:-home.example.com}      # home public IP / DDNS name (set this!)
CONF=/etc/wireguard/$IFACE.conf
LAN_IF=${LAN_IF:-$(ip -4 route show default | awk '{print $5; exit}')}

need_root() { [ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }; }
server_pub() { wg show "$IFACE" public-key; }

cmd_init() {
  need_root
  command -v wg >/dev/null || apt-get install -y wireguard-tools
  if [ -f "$CONF" ]; then echo "$CONF exists — nothing to do (use add/list)"; return; fi
  umask 077
  local priv; priv=$(wg genkey)
  cat >"$CONF" <<CONF
# Immich Home Link — managed by homelink-server.sh. Peers below carry "# name: …" markers.
[Interface]
Address = $NET.1/24
ListenPort = $PORT
PrivateKey = $priv
PostUp   = sysctl -w net.ipv4.ip_forward=1; iptables -A FORWARD -i $IFACE -d $LAN_CIDR -j ACCEPT; iptables -A FORWARD -o $IFACE -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -A POSTROUTING -s $NET.0/24 -o $LAN_IF -j MASQUERADE
PostDown = iptables -D FORWARD -i $IFACE -d $LAN_CIDR -j ACCEPT; iptables -D FORWARD -o $IFACE -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -D POSTROUTING -s $NET.0/24 -o $LAN_IF -j MASQUERADE
CONF
  systemctl enable --now "wg-quick@$IFACE"
  echo "wg1 up: $NET.1/24 on udp/$PORT, forwarding to $LAN_CIDR via $LAN_IF"
  echo "Server public key: $(server_pub)"
  echo "NOW: forward udp/$PORT on the router to $(ip -4 addr show "$LAN_IF" | awk '/inet /{print $2; exit}' | cut -d/ -f1)"
}

next_ip() {
  local used; used=$(grep -oE "AllowedIPs = $NET\.[0-9]+" "$CONF" | awk -F. '{print $NF}' | sort -n)
  for i in $(seq 10 250); do echo "$used" | grep -qx "$i" || { echo "$i"; return; }; done
  echo "pool exhausted" >&2; exit 1
}

cmd_add() {
  need_root
  local name=${1:?name}; shift || true
  local pub="" priv="" psk
  while [ $# -gt 0 ]; do case "$1" in --pubkey) pub=$2; shift 2;; *) echo "unknown arg $1" >&2; exit 1;; esac; done
  [ -f "$CONF" ] || { echo "run init first" >&2; exit 1; }
  if [ -z "$pub" ]; then priv=$(wg genkey); pub=$(echo "$priv" | wg pubkey); fi
  psk=$(wg genpsk)
  local n; n=$(next_ip)
  cat >>"$CONF" <<PEER

# name: $name
[Peer]
PublicKey = $pub
PresharedKey = $psk
AllowedIPs = $NET.$n/32
PEER
  wg set "$IFACE" peer "$pub" preshared-key <(echo "$psk") allowed-ips "$NET.$n/32"
  local client
  client=$(cat <<CLIENT
[Interface]
PrivateKey = ${priv:-<paste the private key generated on the phone>}
Address = $NET.$n/32
MTU = 1280

[Peer]
PublicKey = $(server_pub)
PresharedKey = $psk
Endpoint = $PUBLIC:$PORT
AllowedIPs = $LAN_CIDR, $NET.0/24
PersistentKeepalive = 25
CLIENT
)
  echo "=== $name -> $NET.$n ==="
  echo "$client"
  echo
  if [ -n "$priv" ] && command -v qrencode >/dev/null; then
    echo "$client" | qrencode -t ansiutf8
  elif [ -n "$priv" ]; then
    echo "(apt-get install qrencode to also print this as a QR)"
  fi
}

cmd_list() {
  awk '/^# name:/{name=substr($0,9)} /^AllowedIPs/{print $3 "\t" name}' "$CONF"
  echo; wg show "$IFACE" latest-handshakes 2>/dev/null | awk '{ if ($2>0) printf "%s handshake %d s ago\n", $1, systime()-$2; else printf "%s never\n", $1 }'
}

cmd_remove() {
  need_root
  local name=${1:?name}
  local pub; pub=$(awk -v n="$name" '/^# name:/{cur=substr($0,9)} /^PublicKey/ && cur==n {print $3}' "$CONF")
  [ -n "$pub" ] || { echo "no peer named $name" >&2; exit 1; }
  wg set "$IFACE" peer "$pub" remove
  python3 - "$CONF" "$name" <<'PY'
import sys,re
p,name=sys.argv[1],sys.argv[2]
s=open(p).read()
s=re.sub(r"\n# name: "+re.escape(name)+r"\n\[Peer\](?:\n(?!\n|\[).*)*\n?", "\n", s)
open(p,"w").write(s)
PY
  echo "removed $name"
}

case "${1:-}" in
  init) cmd_init;;
  add) shift; cmd_add "$@";;
  list) cmd_list;;
  remove) shift; cmd_remove "$@";;
  status) wg show "$IFACE";;
  *) sed -n 2,14p "$0"; exit 1;;
esac
