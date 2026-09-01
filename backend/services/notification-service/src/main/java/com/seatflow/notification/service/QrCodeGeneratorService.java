package com.seatflow.notification.service;

public interface QrCodeGeneratorService {

    /**
     * Generate a PNG byte array QR code for the given payload.
     *
     * @param payload Content to encode
     * @param width   Pixel width
     * @param height  Pixel height
     * @return PNG image byte array
     */
    byte[] generateQrCodePng(String payload, int width, int height);

    /**
     * Generate a Base64-encoded Data URL string (e.g. data:image/png;base64,...) for embedding in HTML emails.
     *
     * @param payload Content to encode
     * @param width   Pixel width
     * @param height  Pixel height
     * @return Base64 data URL string
     */
    String generateQrCodeBase64(String payload, int width, int height);
}
