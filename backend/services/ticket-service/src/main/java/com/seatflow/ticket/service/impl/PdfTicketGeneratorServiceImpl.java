package com.seatflow.ticket.service.impl;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.seatflow.ticket.model.common.PdfTicketData;
import com.seatflow.ticket.service.PdfTicketGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;

@Slf4j
@Service
public class PdfTicketGeneratorServiceImpl implements PdfTicketGeneratorService {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy • HH:mm 'UTC'", Locale.US).withZone(ZoneId.of("UTC"));

    private static final Color COLOR_PRIMARY = new Color(79, 70, 229);      // #4F46E5 Indigo
    private static final Color COLOR_DARK = new Color(15, 23, 42);          // #0F172A Slate 900
    private static final Color COLOR_MUTED = new Color(100, 116, 139);      // #64748B Slate 500
    private static final Color COLOR_BG_LIGHT = new Color(248, 250, 252);   // #F8FAFC Slate 50
    private static final Color COLOR_BORDER = new Color(226, 232, 240);     // #E2E8F0 Slate 200
    private static final Color COLOR_EMERALD_BG = new Color(236, 253, 245); // #ECFDF5 Emerald 50
    private static final Color COLOR_EMERALD = new Color(5, 150, 105);      // #059669 Emerald 600

    @Override
    public byte[] generatePdf(PdfTicketData ticketData) {
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, baos);
            document.open();
            try {
                Font brandFont = new Font(Font.HELVETICA, 16, Font.BOLD, Color.WHITE);
                Font brandSubFont = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(199, 210, 254));
                Font statusBadgeFont = new Font(Font.HELVETICA, 9, Font.BOLD, COLOR_EMERALD);

                Font eventTitleFont = new Font(Font.HELVETICA, 20, Font.BOLD, COLOR_DARK);
                Font eventCategoryFont = new Font(Font.HELVETICA, 8, Font.BOLD, COLOR_PRIMARY);
                Font eventMetaFont = new Font(Font.HELVETICA, 10, Font.NORMAL, COLOR_MUTED);
                Font eventVenueFont = new Font(Font.HELVETICA, 11, Font.BOLD, COLOR_DARK);

                Font sectionHeaderFont = new Font(Font.HELVETICA, 10, Font.BOLD, COLOR_PRIMARY);
                Font cardLabelFont = new Font(Font.HELVETICA, 8, Font.BOLD, COLOR_MUTED);
                Font cardValueFont = new Font(Font.HELVETICA, 12, Font.BOLD, COLOR_DARK);
                Font detailLabelFont = new Font(Font.HELVETICA, 8, Font.BOLD, COLOR_MUTED);
                Font detailValueFont = new Font(Font.HELVETICA, 10, Font.NORMAL, COLOR_DARK);
                Font codeFont = new Font(Font.COURIER, 11, Font.BOLD, COLOR_PRIMARY);
                Font totalPaidFont = new Font(Font.HELVETICA, 13, Font.BOLD, COLOR_PRIMARY);
                Font footerFont = new Font(Font.HELVETICA, 8, Font.NORMAL, COLOR_MUTED);

                // --- 1. BRAND HEADER BANNER ---
                PdfPTable headerTable = new PdfPTable(2);
                headerTable.setWidthPercentage(100);
                headerTable.setWidths(new float[]{60, 40});

                PdfPCell brandCell = new PdfPCell();
                brandCell.setBackgroundColor(COLOR_DARK);
                brandCell.setPadding(14);
                brandCell.setBorder(Rectangle.NO_BORDER);
                brandCell.addElement(new Phrase("SEATFLOW", brandFont));
                brandCell.addElement(new Phrase("OFFICIAL EVENT ADMISSION PASS", brandSubFont));
                headerTable.addCell(brandCell);

                PdfPCell statusCell = new PdfPCell();
                statusCell.setBackgroundColor(COLOR_DARK);
                statusCell.setPadding(14);
                statusCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                statusCell.setBorder(Rectangle.NO_BORDER);
                Paragraph statusPara = new Paragraph();
                statusPara.setAlignment(Element.ALIGN_RIGHT);
                statusPara.add(new Phrase("STATUS: " + ticketData.status(), statusBadgeFont));
                statusCell.addElement(statusPara);

                Paragraph refPara = new Paragraph();
                refPara.setAlignment(Element.ALIGN_RIGHT);
                refPara.add(new Phrase("REF: " + ticketData.ticketCode(), brandSubFont));
                statusCell.addElement(refPara);
                headerTable.addCell(statusCell);

                document.add(headerTable);

                // --- 2. EVENT TITLE & SHOWCASE ---
                PdfPTable eventBox = new PdfPTable(1);
                eventBox.setWidthPercentage(100);
                eventBox.setSpacingBefore(12);

                PdfPCell eventCell = new PdfPCell();
                eventCell.setBackgroundColor(COLOR_BG_LIGHT);
                eventCell.setBorderColor(COLOR_BORDER);
                eventCell.setBorderWidth(1);
                eventCell.setPadding(14);

                if (ticketData.eventCategory() != null && !ticketData.eventCategory().isBlank()) {
                    eventCell.addElement(new Phrase(ticketData.eventCategory().toUpperCase(Locale.ROOT), eventCategoryFont));
                }
                eventCell.addElement(new Phrase(ticketData.eventTitle() != null ? ticketData.eventTitle() : "SeatFlow Live Event", eventTitleFont));
                eventCell.addElement(new Phrase(formatInstant(ticketData.eventDate()), eventMetaFont));
                String venueText = (ticketData.venueName() != null ? ticketData.venueName() : "Official Venue") +
                        (ticketData.venueCity() != null ? ", " + ticketData.venueCity() : "");
                eventCell.addElement(new Phrase(venueText, eventVenueFont));
                eventBox.addCell(eventCell);

                document.add(eventBox);

                // --- 3. SEAT & TICKET HOLDER DETAILS (2-COLUMN GRID) ---
                PdfPTable mainGrid = new PdfPTable(2);
                mainGrid.setWidthPercentage(100);
                mainGrid.setWidths(new float[]{55, 45});
                mainGrid.setSpacingBefore(12);

                // Left Column: Seat Allocation & Attendee Info
                PdfPCell leftCol = new PdfPCell();
                leftCol.setBorder(Rectangle.NO_BORDER);
                leftCol.setPaddingRight(8);

                // Seat Chips Sub-table (4 Columns: SECTION, ROW, SEAT, TIER/TYPE)
                PdfPTable seatTable = new PdfPTable(4);
                seatTable.setWidthPercentage(100);
                seatTable.setWidths(new float[]{28, 22, 22, 28});

                String typeName = ticketData.ticketType() != null && !ticketData.ticketType().isBlank() ? ticketData.ticketType() : "Standard";
                addSeatChip(seatTable, "SECTION", ticketData.sectionName() != null ? ticketData.sectionName() : "General", cardLabelFont, cardValueFont);
                addSeatChip(seatTable, "ROW", ticketData.rowLabel() != null ? ticketData.rowLabel() : "—", cardLabelFont, cardValueFont);
                addSeatChip(seatTable, "SEAT", ticketData.seatNumber() != null ? ticketData.seatNumber().toString() : "—", cardLabelFont, cardValueFont);
                addSeatChip(seatTable, "TYPE", typeName, cardLabelFont, cardValueFont);
                leftCol.addElement(seatTable);

                // Attendee Details Box
                PdfPTable attendeeTable = new PdfPTable(2);
                attendeeTable.setWidthPercentage(100);
                attendeeTable.setWidths(new float[]{35, 65});
                attendeeTable.setSpacingBefore(10);

                String attendeeDisplayName = resolveAttendeeDisplayName(ticketData.attendeeName(), ticketData.customerEmail());
                addDetailRow(attendeeTable, "Attendee Name", attendeeDisplayName, detailLabelFont, detailValueFont);
                addDetailRow(attendeeTable, "Account Email", ticketData.customerEmail(), detailLabelFont, detailValueFont);
                addDetailRow(attendeeTable, "Pass Tier", typeName + " Admission", detailLabelFont, detailValueFont);
                addDetailRow(attendeeTable, "Ticket Code", ticketData.ticketCode(), detailLabelFont, codeFont);
                leftCol.addElement(attendeeTable);

                mainGrid.addCell(leftCol);

                // Right Column: QR Code Gate Pass
                PdfPCell rightCol = new PdfPCell();
                rightCol.setBackgroundColor(COLOR_BG_LIGHT);
                rightCol.setBorderColor(COLOR_BORDER);
                rightCol.setBorderWidth(1);
                rightCol.setPadding(12);
                rightCol.setHorizontalAlignment(Element.ALIGN_CENTER);

                Paragraph qrHeader = new Paragraph("GATE SCANNER PASS", sectionHeaderFont);
                qrHeader.setAlignment(Element.ALIGN_CENTER);
                rightCol.addElement(qrHeader);

                if (ticketData.qrCodeImagePng() != null && ticketData.qrCodeImagePng().length > 0) {
                    Image qr = Image.getInstance(ticketData.qrCodeImagePng());
                    qr.scaleToFit(140f, 140f);
                    qr.setAlignment(Image.ALIGN_CENTER);
                    rightCol.addElement(qr);
                }

                Paragraph qrFooter = new Paragraph("Scan at venue entry gate", cardLabelFont);
                qrFooter.setAlignment(Element.ALIGN_CENTER);
                rightCol.addElement(qrFooter);

                mainGrid.addCell(rightCol);

                document.add(mainGrid);

                // --- 4. FISCAL & PAYMENT RECEIPT BREAKDOWN ---
                PdfPTable fiscalBox = new PdfPTable(1);
                fiscalBox.setWidthPercentage(100);
                fiscalBox.setSpacingBefore(14);

                PdfPCell fiscalHeaderCell = new PdfPCell();
                fiscalHeaderCell.setBackgroundColor(COLOR_BG_LIGHT);
                fiscalHeaderCell.setBorderColor(COLOR_BORDER);
                fiscalHeaderCell.setBorderWidth(1);
                fiscalHeaderCell.setPadding(12);

                fiscalHeaderCell.addElement(new Phrase("PAYMENT & FISCAL RECEIPT", sectionHeaderFont));

                PdfPTable fiscalTable = new PdfPTable(2);
                fiscalTable.setWidthPercentage(100);
                fiscalTable.setWidths(new float[]{60, 40});
                fiscalTable.setSpacingBefore(6);

                BigDecimal grossPrice = ticketData.price() != null ? ticketData.price() : BigDecimal.ZERO;
                BigDecimal taxAmount = ticketData.taxAmount() != null ? ticketData.taxAmount() : BigDecimal.ZERO;
                BigDecimal netAmount = ticketData.netAmount() != null ? ticketData.netAmount() : grossPrice.subtract(taxAmount);

                // Fiscal Reconciliation: Net + Tax must equal Gross
                if (netAmount.add(taxAmount).compareTo(grossPrice) != 0) {
                    if (taxAmount.compareTo(BigDecimal.ZERO) == 0 && netAmount.compareTo(grossPrice) < 0) {
                        taxAmount = grossPrice.subtract(netAmount);
                    } else if (netAmount.compareTo(BigDecimal.ZERO) == 0 && taxAmount.compareTo(grossPrice) < 0) {
                        netAmount = grossPrice.subtract(taxAmount);
                    } else {
                        netAmount = grossPrice.subtract(taxAmount);
                    }
                }

                addFiscalRow(fiscalTable, "Net Base Ticket Price", formatMoney(netAmount, ticketData.currency()), detailLabelFont, detailValueFont);
                addFiscalRow(fiscalTable, "Tax / VAT (Included)", formatMoney(taxAmount, ticketData.currency()), detailLabelFont, detailValueFont);
                addFiscalRow(fiscalTable, "Total Amount Paid", formatMoney(grossPrice, ticketData.currency()), detailLabelFont, totalPaidFont);

                fiscalHeaderCell.addElement(fiscalTable);
                fiscalBox.addCell(fiscalHeaderCell);
                document.add(fiscalBox);

                // --- 5. FOOTER & SECURITY NOTICE ---
                Paragraph footer = new Paragraph(
                        "This digital pass is an official admission document powered by the SeatFlow Ticketing Engine. " +
                        "Each pass is single-use and validates admission at the venue turnstile. Keep your ticket code confidential.",
                        footerFont
                );
                footer.setSpacingBefore(16);
                footer.setAlignment(Element.ALIGN_CENTER);
                document.add(footer);

            } finally {
                if (document.isOpen()) {
                    document.close();
                }
            }
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render PDF ticket for code {}", ticketData.ticketCode(), e);
            throw new IllegalStateException("Failed to render PDF ticket for code " + ticketData.ticketCode(), e);
        }
    }

    private void addSeatChip(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(COLOR_BG_LIGHT);
        cell.setBorderColor(COLOR_BORDER);
        cell.setBorderWidth(1);
        cell.setPadding(8);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Paragraph p1 = new Paragraph(label, labelFont);
        p1.setAlignment(Element.ALIGN_CENTER);
        Paragraph p2 = new Paragraph(value, valueFont);
        p2.setAlignment(Element.ALIGN_CENTER);

        cell.addElement(p1);
        cell.addElement(p2);
        table.addCell(cell);
    }

    private void addDetailRow(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPaddingTop(4);
        labelCell.setPaddingBottom(4);

        PdfPCell valueCell = new PdfPCell(new Phrase(value != null ? value : "—", valueFont));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setPaddingTop(4);
        valueCell.setPaddingBottom(4);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void addFiscalRow(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(Rectangle.BOTTOM);
        labelCell.setBorderColor(COLOR_BORDER);
        labelCell.setPaddingTop(4);
        labelCell.setPaddingBottom(4);

        PdfPCell valueCell = new PdfPCell(new Phrase(value != null ? value : "$0.00", valueFont));
        valueCell.setBorder(Rectangle.BOTTOM);
        valueCell.setBorderColor(COLOR_BORDER);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setPaddingTop(4);
        valueCell.setPaddingBottom(4);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private String resolveAttendeeDisplayName(String attendeeName, String customerEmail) {
        if (attendeeName != null && !attendeeName.isBlank() && !attendeeName.equalsIgnoreCase(customerEmail)) {
            return attendeeName;
        }
        if (customerEmail != null && customerEmail.contains("@")) {
            String prefix = customerEmail.substring(0, customerEmail.indexOf('@'));
            // Capitalize if it looks like a clean name or username
            return prefix;
        }
        return "Valued Attendee";
    }

    private String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) {
            return "$0.00";
        }
        try {
            if (currency != null && !currency.isBlank()) {
                Currency cur = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT));
                NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.US);
                formatter.setCurrency(cur);
                return formatter.format(amount);
            }
        } catch (Exception ignored) {
        }
        NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.US);
        return formatter.format(amount);
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "Event Date TBA";
        }
        try {
            return DATE_FORMATTER.format(instant);
        } catch (Exception e) {
            return instant.toString();
        }
    }
}
