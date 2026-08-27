package com.seatflow.notification.client.resend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.notification.client.resend.dto.ResendAttachment;
import com.seatflow.notification.client.resend.dto.ResendEmailRequest;
import com.seatflow.notification.client.resend.dto.ResendEmailResponse;
import com.seatflow.notification.client.resend.exception.ResendClientException;
import com.seatflow.notification.client.resend.impl.ResendEmailClientImpl;
import com.seatflow.notification.config.ResendProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ResendEmailClientTest {

    private ResendProperties resendProperties;
    private MockRestServiceServer mockServer;
    private ResendEmailClient resendEmailClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        resendProperties = new ResendProperties();
        resendProperties.setApiKey("re_test_12345");
        resendProperties.setApiUrl("https://api.resend.com");
        resendProperties.setFromEmail("SeatFlow <onboarding@resend.dev>");
        resendProperties.setConnectTimeoutMs(2000);
        resendProperties.setReadTimeoutMs(2000);

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        resendEmailClient = new ResendEmailClientImpl(resendProperties, builder, objectMapper);
    }

    @Test
    @DisplayName("Should successfully send email via Resend API")
    void shouldSendEmailSuccessfully() throws Exception {
        ResendEmailRequest request = new ResendEmailRequest(
                "SeatFlow <onboarding@resend.dev>",
                List.of("customer@example.com"),
                "Your SeatFlow Ticket Confirmation",
                "<html><body>Ticket Confirmation</body></html>",
                List.of(new ResendAttachment("ticket.pdf", "JVBERi0xLjQK..."))
        );

        String jsonResponse = objectMapper.writeValueAsString(new ResendEmailResponse("email_123456789"));

        mockServer.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test_12345"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        ResendEmailResponse response = resendEmailClient.sendEmail(request);

        mockServer.verify();
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("email_123456789");
    }

    @Test
    @DisplayName("Should throw ResendClientException when Resend API returns 400 bad request")
    void shouldThrowExceptionOn400BadRequest() {
        ResendEmailRequest request = new ResendEmailRequest(
                "SeatFlow <onboarding@resend.dev>",
                List.of("invalid-email"),
                "Test",
                "<html>Test</html>",
                List.of()
        );

        String errorJson = """
                {
                    "name": "validation_error",
                    "message": "Invalid recipient email address",
                    "statusCode": 400
                }
                """;

        mockServer.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson));

        assertThatThrownBy(() -> resendEmailClient.sendEmail(request))
                .isInstanceOf(ResendClientException.class)
                .hasMessageContaining("Invalid recipient email address");

        mockServer.verify();
    }

    @Test
    @DisplayName("Should throw ResendClientException when Resend API returns 500 server error")
    void shouldThrowExceptionOn500ServerError() {
        ResendEmailRequest request = new ResendEmailRequest(
                "SeatFlow <onboarding@resend.dev>",
                List.of("customer@example.com"),
                "Test",
                "<html>Test</html>",
                List.of()
        );

        mockServer.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> resendEmailClient.sendEmail(request))
                .isInstanceOf(ResendClientException.class);

        mockServer.verify();
    }
}
