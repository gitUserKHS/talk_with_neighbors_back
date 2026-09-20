#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_DIR="${1:?release directory is required}"
readonly SYSTEMD_SOURCE="$SOURCE_DIR/systemd"
readonly CONFIG_SOURCE="$SOURCE_DIR/duckdns.conf"
readonly CONFIG_TARGET="/etc/talk-with-neighbors/duckdns.conf"
readonly SERVICE="talk-with-neighbors-duckdns-update.service"
readonly TIMER="talk-with-neighbors-duckdns-update.timer"

[[ "$EUID" -eq 0 ]] || { echo "install-duckdns-update.sh must run as root" >&2; exit 1; }
[[ -d "$SOURCE_DIR" && ! -L "$SOURCE_DIR" ]] || { echo "Unsafe release directory" >&2; exit 1; }
for path in \
  "$SOURCE_DIR/duckdns-update.sh" \
  "$SYSTEMD_SOURCE/$SERVICE" \
  "$SYSTEMD_SOURCE/$TIMER"; do
  [[ -s "$path" && ! -L "$path" ]] || { echo "Missing or unsafe DuckDNS asset: $path" >&2; exit 1; }
done

install -o root -g root -m 0700 -d /etc/talk-with-neighbors
install -o root -g root -m 0750 "$SOURCE_DIR/duckdns-update.sh" /usr/local/sbin/talk-with-neighbors-duckdns-update

if [[ -s "$CONFIG_SOURCE" && ! -L "$CONFIG_SOURCE" ]]; then
  grep -Eq '^DUCKDNS_DOMAIN=[a-z0-9-]+$' "$CONFIG_SOURCE"
  grep -Eq '^DUCKDNS_TOKEN_PARAMETER=/[A-Za-z0-9_./-]+$' "$CONFIG_SOURCE"
  install -o root -g root -m 0600 "$CONFIG_SOURCE" "$CONFIG_TARGET"
  for unit in "$SERVICE" "$TIMER"; do
    install -o root -g root -m 0644 "$SYSTEMD_SOURCE/$unit" "/etc/systemd/system/$unit"
  done
  systemctl daemon-reload
  systemctl enable --now "$TIMER" >/dev/null
  systemctl is-enabled --quiet "$TIMER"
  # The runner already verified DNS before this deployment started, so a
  # failure here (for example a missing token parameter) is reported, not fatal.
  if ! systemctl start "$SERVICE"; then
    echo "The first DuckDNS update failed; inspect: journalctl -u $SERVICE" >&2
  fi
  echo "DuckDNS updater timer is installed"
else
  # The public host is not a DuckDNS name: leave no stale updater behind.
  if systemctl list-unit-files --no-legend "$TIMER" 2>/dev/null | grep -Fq "$TIMER"; then
    systemctl disable --now "$TIMER" >/dev/null 2>&1 || true
  fi
  rm -f -- "$CONFIG_TARGET"
  echo "The public host is not a DuckDNS name; the updater timer stays disabled"
fi
