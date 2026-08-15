#!/usr/bin/env bash
# One-time setup on Oracle Always Free Ubuntu VM
set -euo pipefail

APP_DIR=/opt/society-app
DATA_DIR=/var/lib/society-data
SERVICE_USER=ubuntu

echo "==> Installing Java 17 and Maven"
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk maven git ufw

echo "==> Creating folders"
sudo mkdir -p "$APP_DIR" "$DATA_DIR"
sudo chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR" "$DATA_DIR"

echo "==> Opening firewall port 10000"
sudo ufw allow OpenSSH
sudo ufw allow 10000/tcp
sudo ufw --force enable || true

echo "==> Done. Next:"
echo "1) Upload/build your project into $APP_DIR"
echo "2) Run: bash $APP_DIR/install-service.sh"
echo "Data will be stored forever in: $DATA_DIR"
