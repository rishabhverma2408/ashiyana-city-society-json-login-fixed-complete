#!/usr/bin/env bash
# Build jar and install systemd service (run from project root on the VM)
set -euo pipefail

APP_DIR=/opt/society-app
DATA_DIR=/var/lib/society-data
SERVICE_NAME=society-app
JAR_NAME=society-management.jar

echo "==> Building Spring Boot jar"
mvn -DskipTests package

JAR_SOURCE=$(ls target/*-1.0.0.jar | head -n 1)
if [[ -z "$JAR_SOURCE" ]]; then
  echo "Jar not found in target/"
  exit 1
fi

echo "==> Installing jar to $APP_DIR/$JAR_NAME"
sudo mkdir -p "$APP_DIR" "$DATA_DIR"
sudo cp "$JAR_SOURCE" "$APP_DIR/$JAR_NAME"
sudo cp deploy/oracle/society-app.service /tmp/society-app.service
sudo mv /tmp/society-app.service /etc/systemd/system/${SERVICE_NAME}.service

echo "==> Enabling service"
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"
sudo systemctl --no-pager status "$SERVICE_NAME" || true

PUBLIC_IP=$(curl -s ifconfig.me || true)
echo
echo "App should be running."
echo "Open: http://${PUBLIC_IP}:10000"
echo "Data folder (safe on code updates): $DATA_DIR"
echo
echo "Useful commands:"
echo "  sudo systemctl status $SERVICE_NAME"
echo "  sudo journalctl -u $SERVICE_NAME -f"
echo "  sudo systemctl restart $SERVICE_NAME"
