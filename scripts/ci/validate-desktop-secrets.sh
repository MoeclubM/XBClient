#!/usr/bin/env bash
set -euo pipefail

for name in XBCLIENT_DEFAULT_API_URL XBCLIENT_USER_AGENT XBCLIENT_OAUTH_CALLBACK_SCHEME; do
  if [ -z "${!name:-}" ]; then
    echo "::error::$name is empty or not available to this workflow"
    exit 1
  fi
done

echo "desktop build secrets ok"
