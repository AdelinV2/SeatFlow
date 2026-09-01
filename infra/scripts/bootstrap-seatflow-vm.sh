#!/usr/bin/env bash
# ==============================================================================
# SeatFlow Compute Engine Host Bootstrap Script
# Idempotent host initialization script executed on initial VM boot or startup.
# Installs Docker Engine, Compose plugin v2, Ops Agent, configures registry auth,
# creates directory layout, and sets up systemd service unit.
# INVARIANT: Zero secrets embedded.
# ==============================================================================
set -euo pipefail

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin:$PATH"

echo "==> [SeatFlow Bootstrap] Starting Compute Engine host bootstrap..."

# 1. Update APT and install prerequisite utilities
export DEBIAN_FRONTEND=noninteractive
echo "==> [SeatFlow Bootstrap] Installing base packages..."
apt-get update -y
apt-get install -y --no-install-recommends \
  ca-certificates \
  curl \
  gnupg \
  lsb-release \
  jq \
  ufw \
  tar \
  gzip \
  unzip

# 2. Install Docker Engine and Docker Compose v2 from official Docker repository
if ! command -v docker &>/dev/null; then
  echo "==> [SeatFlow Bootstrap] Setting up Docker official apt repository..."
  install -m 0755 -d /etc/apt/keyrings
  if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
  fi

  ARCH="$(dpkg --print-architecture)"
  CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
  echo "deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list

  apt-get update -y
  apt-get install -y --no-install-recommends \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin

  systemctl enable docker
  systemctl start docker
  echo "==> [SeatFlow Bootstrap] Docker installed: $(docker --version)"
else
  echo "==> [SeatFlow Bootstrap] Docker is already installed: $(docker --version)"
fi

# 3. Configure Docker credential helper for Google Artifact Registry
echo "==> [SeatFlow Bootstrap] Configuring Artifact Registry Docker credential helper..."
if command -v gcloud &>/dev/null; then
  gcloud auth configure-docker europe-west1-docker.pkg.dev --quiet || true
fi

# 4. Install and configure Google Cloud Ops Agent for Cloud Logging & Monitoring
if [ ! -f /etc/google-cloud-ops-agent/config.yaml ]; then
  echo "==> [SeatFlow Bootstrap] Installing Google Cloud Ops Agent..."
  curl -sSO https://dl.google.com/cloudagents/add-google-cloud-ops-agent-repo.sh
  bash add-google-cloud-ops-agent-repo.sh --also-install || {
    echo "==> [SeatFlow Bootstrap] Warning: Ops Agent installation script returned non-zero. Continuing..."
  }
  rm -f add-google-cloud-ops-agent-repo.sh
else
  echo "==> [SeatFlow Bootstrap] Google Cloud Ops Agent is already installed."
fi

# 5. Create SeatFlow host directory layout with strict permissions
echo "==> [SeatFlow Bootstrap] Creating directory layout..."
mkdir -p /opt/seatflow
mkdir -p /opt/seatflow/config
mkdir -p /opt/seatflow/logs
mkdir -p /opt/seatflow/backups
mkdir -p /run/seatflow

chmod 750 /opt/seatflow
chmod 750 /opt/seatflow/config
chmod 750 /opt/seatflow/logs
chmod 700 /opt/seatflow/backups
chmod 700 /run/seatflow

# 6. Configure systemd service for automatic reboot recovery
echo "==> [SeatFlow Bootstrap] Installing systemd seatflow.service unit..."
cat << 'EOF' > /etc/systemd/system/seatflow.service
[Unit]
Description=SeatFlow Production Docker Compose Stack
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/seatflow
ExecStart=/bin/bash -c 'if [ -f /opt/seatflow/docker-compose.yml ] && [ -f /run/seatflow/runtime.env ]; then \
  docker compose \
    -f /opt/seatflow/docker-compose.yml \
    -f /opt/seatflow/docker-compose.services.yml \
    -f /opt/seatflow/docker-compose.monitoring.yml \
    -f /opt/seatflow/docker-compose.prod.yml \
    --env-file /run/seatflow/runtime.env \
    up -d --remove-orphans; \
  else \
    echo "SeatFlow compose files or runtime.env not ready yet."; \
  fi'
ExecStop=/bin/bash -c 'if [ -f /opt/seatflow/docker-compose.yml ] && [ -f /run/seatflow/runtime.env ]; then \
  docker compose \
    -f /opt/seatflow/docker-compose.yml \
    -f /opt/seatflow/docker-compose.services.yml \
    -f /opt/seatflow/docker-compose.monitoring.yml \
    -f /opt/seatflow/docker-compose.prod.yml \
    --env-file /run/seatflow/runtime.env \
    down; \
  fi'
TimeoutStartSec=600

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable seatflow.service

echo "==> [SeatFlow Bootstrap] Bootstrap completed successfully."
