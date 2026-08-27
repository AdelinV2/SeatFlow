package com.seatflow.ticket.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.seatflow.ticket.service.impl.QrCodeGeneratorServiceImpl;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class QrCodeGeneratorServiceTest {

    private final QrCodeGeneratorService qrCodeGeneratorService = new QrCodeGeneratorServiceImpl();

    @Test
    void generatesNonEmptyPng() {
        byte[] png = qrCodeGeneratorService.generateQrCodePng("SEATFLOW-TICKET-123", 200, 200);

        assertThat(png).isNotNull().isNotEmpty();
    }

    @Test
    void generatesBase64DataUrl() {
        String base64 = qrCodeGeneratorService.generateQrCodeBase64("SEATFLOW-TICKET-123", 200, 200);

        assertThat(base64).startsWith("data:image/png;base64,");
        assertThat(base64.length()).isGreaterThan("data:image/png;base64,".length());
    }

    @Test
    void generatedQrCodeDecodesBackToSource() throws Exception {
        String payload = "SEATFLOW-TICKET-123";
        byte[] png = qrCodeGeneratorService.generateQrCodePng(payload, 250, 250);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();

        BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Result result = new MultiFormatReader().decode(bitmap);

        assertThat(result.getText()).isEqualTo(payload);
    }
}
