package com.seatflow.notification.client.resend.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.notification.client.resend.ResendEmailClient;
import com.seatflow.notification.client.resend.dto.ResendEmailRequest;
import com.seatflow.notification.client.resend.dto.ResendEmailResponse;
import com.seatflow.notification.client.resend.dto.ResendErrorResponse;
import com.seatflow.notification.client.resend.exception.ResendClientException;
import com.seatflow.notification.config.ResendProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;

@Slf4j
@Component
public class ResendEmailClientImpl implements ResendEmailClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ResendEmailClientImpl(ResendProperties resendProperties,
                                 @Qualifier("resendRestClientBuilder") RestClient.Builder restClientBuilder,
                                 ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(resendProperties.getApiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resendProperties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ResendEmailResponse sendEmail(ResendEmailRequest request) {
        log.debug("Sending email via Resend API to={}, subject={}", request.to(), request.subject());

        try {
            ResendEmailResponse response = restClient.post()
                    .uri("/emails")
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String errorBody = "";
                        try {
                            byte[] bodyBytes = res.getBody().readAllBytes();
                            if (bodyBytes.length > 0) {
                                ResendErrorResponse errorResponse = objectMapper.readValue(bodyBytes, ResendErrorResponse.class);
                                if (errorResponse != null && errorResponse.message() != null) {
                                    errorBody = " Message: " + errorResponse.message() + ", Name: " + errorResponse.name();
                                } else {
                                    errorBody = " " + new String(bodyBytes, StandardCharsets.UTF_8);
                                }
                            }
                        } catch (Exception ex) {
                            errorBody = " (failed to parse error response: " + ex.getMessage() + ")";
                        }
                        String msg = "Resend API error [" + res.getStatusCode() + "]:" + errorBody;
                        log.error("Resend API returned error status {}: {}", res.getStatusCode(), msg);
                        throw new ResendClientException(msg);
                    })
                    .body(ResendEmailResponse.class);

            if (response == null || response.id() == null) {
                throw new ResendClientException("Empty or invalid response from Resend API");
            }

            log.info("Email successfully dispatched via Resend: emailId={}, to={}, subject={}",
                    response.id(), request.to(), request.subject());
            return response;
        } catch (ResendClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to dispatch email via Resend API: to={}, subject={}", request.to(), request.subject(), e);
            throw new ResendClientException("Failed to send email via Resend: " + e.getMessage(), e);
        }
    }
}
