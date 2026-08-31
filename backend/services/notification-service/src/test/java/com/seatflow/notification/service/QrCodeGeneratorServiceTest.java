package com.seatflow.notification.service;

import com.seatflow.notification.service.impl.QrCodeGeneratorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QrCodeGeneratorServiceTest {

    private QrCodeGeneratorService qrCodeGeneratorService;

    @BeforeEach
    void setUp() {
        qrCodeGeneratorService = new QrCodeGeneratorServiceImpl();
    }

    @Test
    @DisplayName("Should generate valid PNG byte array for ticket QR code")
    void shouldGenerateQrCodePng() {
        byte[] pngBytes = qrCodeGeneratorService.generateQrCodePng("https://seatflow.app/tickets/guest/SF-TKT-1234", 200, 200);

        assertThat(pngBytes).isNotNull();
        assertThat(pngBytes.length).isGreaterThan(50);
        // Standard PNG magic bytes header: 0x89 'P' 'N' 'G'
        assertThat(pngBytes[0]).isEqualTo((byte) 0x89);
        assertThat(pngBytes[1]).isEqualTo((byte) 0x50);
        assertThat(pngBytes[2]).isEqualTo((byte) 0x4E);
        assertThat(pngBytes[3]).isEqualTo((byte) 0x47);
    }

    @Test
    @DisplayName("Should generate valid Base64 data URI for ticket QR code")
    void shouldGenerateQrCodeBase64() {
        String base64 = qrCodeGeneratorService.generateQrCodeBase64("https://seatflow.app/tickets/guest/SF-TKT-1234", 200, 200);

        assertThat(base64).isNotNull();
        assertThat(base64).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("Should return null for empty payload")
    void shouldReturnNullForEmptyPayload() {
        String base64 = qrCodeGeneratorService.generateQrCodeBase64("", 200, 200);
        assertThat(base64).isNull();
    }
}
