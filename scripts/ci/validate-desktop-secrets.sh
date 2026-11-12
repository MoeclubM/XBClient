#!/usr/bin/env bash
set -euo pipefail

panel_type="${XBCLIENT_PANEL_TYPE:-xboard}"
panel_type="$(printf '%s' "$panel_type" | tr '[:upper:]' '[:lower:]')"

for name in XBCLIENT_DEFAULT_API_URL XBCLIENT_APP_NAME XBCLIENT_USER_AGENT; do
  if [ -z "${!name:-}" ]; then
    echo "::error::$name is empty or not available to this workflow"
    exit 1
  fi
done

if [ "$panel_type" != "pxboard" ] && [ -z "${XBCLIENT_OAUTH_CALLBACK_SCHEME:-}" ]; then
  echo "::error::XBCLIENT_OAUTH_CALLBACK_SCHEME is required when XBCLIENT_PANEL_TYPE=$panel_type"
  exit 1
fi

if [ -n "${XBCLIENT_APP_PAYMENT_ENABLED:-}" ]; then
  payment_flag="$(printf '%s' "$XBCLIENT_APP_PAYMENT_ENABLED" | tr '[:upper:]' '[:lower:]')"
  case "$payment_flag" in
    true|false|1|0|yes|no|on|off) ;;
    *) echo "::error::XBCLIENT_APP_PAYMENT_ENABLED must be true or false"; exit 1 ;;
  esac
fi

echo "desktop build secrets ok"
