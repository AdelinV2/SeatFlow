#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "configure-https-edge.sh must run as root" >&2
  exit 1
fi
if [[ $# -ne 1 || ! $1 =~ ^https:// ]]; then
  echo "Usage: $0 <https-smoke-url>" >&2
  exit 2
fi
smoke_url=$1
metadata_url=http://metadata.google.internal/computeMetadata/v1
expected_ip=$(curl -fsS -H 'Metadata-Flavor: Google' \
  "${metadata_url}/instance/network-interfaces/0/access-configs/0/external-ip")

for host in seat-flow.me www.seat-flow.me api.seat-flow.me; do
  if ! getent ahostsv4 "${host}" | awk '{print $1}' | grep -Fxq "${expected_ip}"; then
    echo "DNS for ${host} is not pointing to the SeatFlow VM yet; HTTPS configuration deferred"
    exit 10
  fi
done

export DEBIAN_FRONTEND=noninteractive
if ! command -v certbot >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y --no-install-recommends certbot
fi

certbot certonly --webroot \
  --webroot-path /var/www/certbot \
  --non-interactive --agree-tos --register-unsafely-without-email \
  --keep-until-expiring \
  -d seat-flow.me -d www.seat-flow.me -d api.seat-flow.me

install -d -o root -g root -m 0755 /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/seatflow-nginx-reload.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
nginx -t
systemctl reload nginx
EOF
chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/seatflow-nginx-reload.sh

/opt/seatflow/infra/scripts/configure-http-edge.sh
systemctl enable --now certbot.timer 2>/dev/null || true

public_status=$(curl -fsS -o /dev/null -w '%{http_code}' "${smoke_url}")
if [[ ! ${public_status} =~ ^2[0-9]{2}$ ]]; then
  echo "Public HTTPPS smoke probe failed with HTTP ${public_status}" >&2
  exit 1
fi

echo "HTTPS edge is configured and the public smoke probe passed"
