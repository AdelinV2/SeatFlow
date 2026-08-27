package com.seatflow.ticket.service;

public interface QrCodeGeneratorService {

    /**
     * Generates a PNG byte array for a QR code from text payload.
     */
    byte[] generateQrCodePng(String payload, int width, int height);

    /**
     * Generates a Base64 data URL (data:image/png;base64,...) for embedding in HTML/JSON.
     */
    String generateQrCodeBase64(String payload, int width, int height);
}
