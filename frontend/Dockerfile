FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build -- --configuration production

FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
RUN addgroup -S seatflow \
    && adduser -S -G seatflow seatflow \
    && mkdir -p /tmp/client_temp /tmp/proxy_temp /tmp/fastcgi_temp /tmp/uwsgi_temp /tmp/scgi_temp \
    && chown -R seatflow:seatflow /tmp /var/cache/nginx /var/log/nginx /usr/share/nginx/html
COPY --from=build --chown=seatflow:seatflow /app/dist/seatflow/browser /usr/share/nginx/html
COPY nginx-main.conf /etc/nginx/nginx.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
USER seatflow:seatflow
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=10s CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1
