package com.seatflow.ticket.service.impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.seatflow.ticket.service.QrCodeGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class QrCodeGeneratorServiceImpl implements QrCodeGeneratorService {

    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN, 1
    );

    @Override
    public byte[] generateQrCodePng(String payload, int width, int height) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, width, height, HINTS);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
            return baos.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Failed to generate QR code for payload", e);
        }
    }

    @Override
    public String generateQrCodeBase64(String payload, int width, int height) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(generateQrCodePng(payload, width, height));
    }
}
