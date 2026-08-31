package com.seatflow.common.observability.logging;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationContractTest {

    private static final List<String> SERVICE_NAMES = List.of(
            "api-gateway", "eureka-server", "user-service", "seat-map-service", "event-service",
            "reservation-service", "payment-service", "ticket-service", "realtime-service", "notification-service"
    );

    @Test
    void shouldKeepEveryServiceProductionLoggingConfigurationAligned() throws Exception {
        Path repositoryRoot = repositoryRoot();
        Path sharedConfig = repositoryRoot.resolve("backend/common/common-observability/src/main/resources/logback-spring.xml");
        Document document = parse(sharedConfig);
        Element prodProfile = profile(document, "prod");

        assertThat(prodProfile.getTextContent()).contains(
                "${SPRING_PROFILES_ACTIVE:prod}"
        );
        assertThat(prodProfile.getTextContent()).doesNotContain("${SPRING_PROFILES_ACTIVE:-prod}");
        assertThat(child(prodProfile, "encoder").getAttribute("class"))
                .isEqualTo("net.logstash.logback.encoder.LogstashEncoder");
        assertThat(child(prodProfile, "jsonGeneratorDecorator").getAttribute("class"))
                .isEqualTo("net.logstash.logback.mask.MaskingJsonGeneratorDecorator");
        assertThat(child(prodProfile, "valueMasker").getAttribute("class"))
                .isEqualTo("com.seatflow.common.observability.logging.LogstashSensitiveValueMasker");
        assertThat(child(prodProfile, "throwableConverter").getAttribute("class"))
                .isEqualTo("net.logstash.logback.stacktrace.ShortenedThrowableConverter");
        assertThat(childText(prodProfile, "maxDepthPerThrowable")).isEqualTo("20");
        assertThat(childText(prodProfile, "maxLength")).isEqualTo("4096");

        List<String> includedMdcFields = IntStream.range(0, prodProfile.getElementsByTagName("includeMdcKeyName").getLength())
                .mapToObj(index -> prodProfile.getElementsByTagName("includeMdcKeyName").item(index).getTextContent())
                .toList();
        assertThat(includedMdcFields).containsExactly(
                StructuredLogFields.TRACE_ID,
                StructuredLogFields.SPAN_ID,
                StructuredLogFields.CORRELATION_ID,
                StructuredLogFields.USER_ID,
                StructuredLogFields.HTTP_METHOD,
                StructuredLogFields.HTTP_URI,
                StructuredLogFields.HTTP_CLIENT_IP
        );

        for (String serviceName : SERVICE_NAMES) {
            Path serviceConfig = repositoryRoot.resolve("backend/services")
                    .resolve(serviceName).resolve("src/main/resources/logback-spring.xml");
            assertThat(Files.mismatch(sharedConfig, serviceConfig)).isEqualTo(-1L);
        }
    }

    private Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private Element profile(Document document, String profileName) {
        NodeList profiles = document.getElementsByTagName("springProfile");
        for (int index = 0; index < profiles.getLength(); index++) {
            Element profile = (Element) profiles.item(index);
            if (profileName.equals(profile.getAttribute("name"))) {
                return profile;
            }
        }
        throw new AssertionError("Missing springProfile '" + profileName + "'");
    }

    private String childText(Element parent, String elementName) {
        return child(parent, elementName).getTextContent();
    }

    private Element child(Element parent, String elementName) {
        return (Element) parent.getElementsByTagName(elementName).item(0);
    }

    private Path repositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("backend/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("SeatFlow repository root not found from " + System.getProperty("user.dir"));
    }
}
