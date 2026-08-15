#!/usr/bin/env bash
# Update code without deleting society data
# Usage on VM (inside project folder after git pull):
#   bash deploy/oracle/update-app.sh
set -euo pipefail

APP_DIR=/opt/society-app
DATA_DIR=/var/lib/society-data
SERVICE_NAME=society-app
JAR_NAME=society-management.jar

echo "==> Building new jar"
mvn -DskipTests package
JAR_SOURCE=$(ls target/*-1.0.0.jar | head -n 1)

echo "==> Replacing jar only (data in $DATA_DIR is untouched)"
sudo cp "$JAR_SOURCE" "$APP_DIR/$JAR_NAME"
sudo systemctl restart "$SERVICE_NAME"
sudo systemctl --no-pager status "$SERVICE_NAME" || true
echo "Update complete. Members/payments/expenses are still in $DATA_DIR"
