package com.seatflow.notification.service;

import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.service.impl.ThymeleafEmailTemplateRendererImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateRendererTest {

    private EmailTemplateRenderer templateRenderer;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/mail/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(false);

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);

        templateRenderer = new ThymeleafEmailTemplateRendererImpl(templateEngine);
    }

    @Test
    @DisplayName("Should render ticket-issued.html with ADR-004 fiscal tax breakdown and attendee name")
    void shouldRenderTicketIssuedTemplate() {
        UUID ticketId = UUID.randomUUID();
        Map<String, Object> variables = Map.of(
                "attendeeName", "John Doe",
                "ticketCode", "SF-TKT-98765432",
                "ticketId", ticketId.toString(),
                "eventName", "Rock Festival 2026",
                "seatInfo", "Section VIP, Row 1, Seat 5",
                "netAmount", new BigDecimal("80.00"),
                "taxAmount", new BigDecimal("20.00"),
                "totalAmount", new BigDecimal("100.00")
        );

        String html = templateRenderer.renderTemplate(NotificationTemplateType.TICKET_ISSUED, variables);

        assertThat(html).isNotBlank();
        assertThat(html).contains("John Doe");
        assertThat(html).contains("SF-TKT-98765432");
        assertThat(html).contains(ticketId.toString());
        assertThat(html).contains("Rock Festival 2026");
        assertThat(html).contains("Section VIP, Row 1, Seat 5");
        assertThat(html).contains("$80.00");
        assertThat(html).contains("$20.00");
        assertThat(html).contains("$100.00");
    }

    @Test
    @DisplayName("Should render payment-failed.html with failure reason and retry information")
    void shouldRenderPaymentFailedTemplate() {
        UUID resId = UUID.randomUUID();
        Map<String, Object> variables = Map.of(
                "customerEmail", "customer@example.com",
                "reservationId", resId.toString(),
                "amount", new BigDecimal("150.00"),
                "currency", "USD",
                "failureReason", "Insufficient funds in account",
                "stripePaymentId", "pi_9988776655"
        );

        String html = templateRenderer.renderTemplate(NotificationTemplateType.PAYMENT_FAILED, variables);

        assertThat(html).isNotBlank();
        assertThat(html).contains("customer@example.com");
        assertThat(html).contains(resId.toString());
        assertThat(html).contains("$150.00 USD");
        assertThat(html).contains("Insufficient funds in account");
        assertThat(html).contains("pi_9988776655");
    }

    @Test
    @DisplayName("Should render reservation-held.html with 15-minute countdown and seat info")
    void shouldRenderReservationHeldTemplate() {
        UUID resId = UUID.randomUUID();
        Map<String, Object> variables = Map.of(
                "customerEmail", "customer@example.com",
                "reservationId", resId.toString(),
                "seatCount", 3,
                "totalAmount", new BigDecimal("225.00"),
                "formattedExpiresAt", "2026-08-27 20:30 UTC"
        );

        String html = templateRenderer.renderTemplate(NotificationTemplateType.RESERVATION_HELD, variables);

        assertThat(html).isNotBlank();
        assertThat(html).contains("customer@example.com");
        assertThat(html).contains(resId.toString());
        assertThat(html).contains("3");
        assertThat(html).contains("$225.00");
        assertThat(html).contains("2026-08-27 20:30 UTC");
    }
}
