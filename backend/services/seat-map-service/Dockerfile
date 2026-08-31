ARG OTEL_AGENT_VERSION=2.12.0
ARG OTEL_AGENT_SHA512=82b6f52b76f1dbff6c5a651b3313411e499f24091a778536ddf1aa06e43dda64b71ed95d8f42b17578cb4b9a0e0c8a17d7566ca910bf232f2630e473891efa39

FROM eclipse-temurin:21-jre-alpine AS otel-agent
ARG OTEL_AGENT_VERSION
ARG OTEL_AGENT_SHA512
RUN apk add --no-cache curl ca-certificates && \
    mkdir -p /opt/opentelemetry && \
    curl -fsSL -o /opt/opentelemetry/opentelemetry-javaagent.jar \
      https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar && \
    echo "${OTEL_AGENT_SHA512}  /opt/opentelemetry/opentelemetry-javaagent.jar" | sha512sum -c -

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
COPY --from=otel-agent /opt/opentelemetry/opentelemetry-javaagent.jar /opt/opentelemetry/opentelemetry-javaagent.jar
ENV OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
    OTEL_PROPAGATORS=tracecontext,baggage \
    OTEL_LOGS_EXPORTER=none \
    JAVA_TOOL_OPTIONS=-javaagent:/opt/opentelemetry/opentelemetry-javaagent.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
