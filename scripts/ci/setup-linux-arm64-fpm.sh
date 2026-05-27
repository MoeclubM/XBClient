#!/usr/bin/env bash
set -euo pipefail
if [ "$(uname -m)" != "aarch64" ]; then
  echo "setup-linux-arm64-fpm: skip (not aarch64)"
  exit 0
fi
sudo apt-get update
sudo apt-get install -y ruby ruby-dev build-essential rpm
sudo gem install fpm
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "USE_SYSTEM_FPM=true" >> "$GITHUB_ENV"
fi
