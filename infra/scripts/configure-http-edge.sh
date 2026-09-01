#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "configure-http-edge.sh must run as root" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
if ! command -v nginx >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y --no-install-recommends nginx
fi

install -d -o root -g root -m 0755 /var/www/certbot
rm -f /etc/nginx/sites-enabled/default

cat > /etc/nginx/snippets/seatflow-proxy.conf <<'EOF'
proxy_pass http://127.0.0.1:8080;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
proxy_connect_timeout 5s;
proxy_buffering off;
EOF

cert_dir=/etc/letsencrypt/live/seat-flow.me
if [[ -s ${cert_dir}/fullchain.pem && -s ${cert_dir}/privkey.pem ]]; then
  cat > /etc/nginx/sites-available/seatflow <<'EOF'
server {
  listen 80;
  listen [::]:80;
  server_name seat-flow.me www.seat-flow.me api.seat-flow.me;
  location /.well-known/acme-challenge/ { root /var/www/certbot; }
  location / { return 301 https://$host$request_uri; }
}
server {
  listen 443 ssl;
  listen [::]:443 ssl;
  server_name seat-flow.me www.seat-flow.me api.seat-flow.me;
  ssl_certificate /etc/letsencrypt/live/seat-flow.me/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/seat-flow.me/privkey.pem;
  ssl_protocols TLSv1.2 TLSv1.3;
  ssl_session_cache shared:SSL:10m;
  add_header Strict-Transport-Security "max-age=31536000" always;
  location / { include /etc/nginx/snippets/seatflow-proxy.conf; }
}
EOF
else
  cat > /etc/nginx/sites-available/seatflow <<'EOF'
server {
  listen 80;
  listen [::]:80;
  server_name seat-flow.me www.seat-flow.me api.seat-flow.me _;
  location /.well-known/acme-challenge/ { root /var/www/certbot; }
  location / { include /etc/nginx/snippets/seatflow-proxy.conf; }
}
EOF
fi

ln -sfn /etc/nginx/sites-available/seatflow /etc/nginx/sites-enabled/seatflow
nginx -t
systemctl enable nginx
systemctl restart nginx
