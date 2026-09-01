ARG OTEL_AGENT_VERSION=2.12.0
ARG OTEL_AGENT_SHA512=82b6f52b76f1dbff6c5a651b3313411e499f24091a778536ddf1aa06e43dda64b71ed95d8f42b17578cb4b9a0e0c8a17d7566ca910bf232f2630e473891efa39

FROM maven:3.9.11-eclipse-temurin-21-alpine@sha256:922927df2c662cdd47ddb116443d6bec4696cfae3de1a0ddac8fcc7b87ce61ae AS build
WORKDIR /workspace
COPY pom.xml ./
COPY common ./common
COPY services ./services
RUN mvn --batch-mode --no-transfer-progress -pl services/api-gateway -am -DskipTests package \
    && cp services/api-gateway/target/*.jar /tmp/app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS otel-agent
ARG OTEL_AGENT_VERSION
ARG OTEL_AGENT_SHA512
RUN apk add --no-cache curl ca-certificates && \
    mkdir -p /opt/opentelemetry && \
    curl -fsSL -o /opt/opentelemetry/opentelemetry-javaagent.jar \
      https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar && \
    echo "${OTEL_AGENT_SHA512}  /opt/opentelemetry/opentelemetry-javaagent.jar" | sha512sum -c -

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
RUN addgroup -S seatflow && adduser -S -G seatflow seatflow
WORKDIR /app
COPY --from=build --chown=seatflow:seatflow /tmp/app.jar /app/app.jar
COPY --from=otel-agent --chown=seatflow:seatflow /opt/opentelemetry/opentelemetry-javaagent.jar /opt/opentelemetry/opentelemetry-javaagent.jar
RUN chmod 500 /app/app.jar
USER seatflow:seatflow
ENV OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
    OTEL_PROPAGATORS=tracecontext,baggage \
    OTEL_LOGS_EXPORTER=none \
    JAVA_TOOL_OPTIONS=-javaagent:/opt/opentelemetry/opentelemetry-javaagent.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --retries=5 --start-period=60s CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
# Preserve Temurin's certificate import entrypoint for corporate CA bundles.
ENTRYPOINT ["/__cacert_entrypoint.sh", "sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
