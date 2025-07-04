package in.xammer.aws_cost_api.service;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import in.xammer.aws_cost_api.dto.AwsInvoice;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Stream;

@Service
public class AwsPdfInvoiceGenerator {

    // Fonts definition
    private static final Font AWS_LOGO_FONT = new Font(Font.FontFamily.HELVETICA, 24, Font.BOLD, new BaseColor(255, 153, 0));
    private static final Font HEADER_FONT = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD, BaseColor.BLACK);
    private static final Font SECTION_HEADER_FONT = new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.BLACK);
    private static final Font BOLD_FONT = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD);
    private static final Font NORMAL_FONT = new Font(Font.FontFamily.HELVETICA, 8, Font.NORMAL);
    private static final Font SMALL_BOLD_FONT = new Font(Font.FontFamily.HELVETICA, 7, Font.BOLD);
    private static final Font SMALL_NORMAL_FONT = new Font(Font.FontFamily.HELVETICA, 7, Font.NORMAL);
    private static final BaseColor TABLE_BORDER_COLOR = new BaseColor(229, 231, 235); // gray-200

    public byte[] generatePdf(AwsInvoice invoice) throws DocumentException, IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, baos);
            document.open();

            // Header
            addHeader(document, invoice);
            addSpacer(document, 15);

            // Summary Table
            createSummaryTable(document, invoice);
            addSpacer(document, 10);

            document.add(new Paragraph("Payable by Account ID: " + invoice.payerAccountId(), NORMAL_FONT));
            addSpacer(document, 15);

            // Highest Cost Section
            createHighestCostTable(document, invoice);
            addSpacer(document, 15);

            // Billing Period Info
            createBillingInfoTable(document, invoice);
            addSpacer(document, 20);

            // Charges by Service
            createChargesByService(document, invoice);
            addSpacer(document, 20);

            // Totals Section
            createTotalsTable(document, invoice);
            addSpacer(document, 20);

            // Footer sections (as seen in sample)
            addEmptySection(document, "Charges by account (0)");
            addEmptySection(document, "Invoices", "Amazon Web Services India Private Limited (0)");
            addEmptySection(document, "Tax Invoices and Additional Documents");
            addEmptySection(document, "Savings (0)");
            addEmptySection(document, "Taxes by service", "Amazon Web Services India Private Limited (0)");

            document.close();
            return baos.toByteArray();
        }
    }

    private void addHeader(Document document, AwsInvoice invoice) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(2);
        headerTable.setWidthPercentage(100);
        headerTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        
        PdfPCell logoCell = new PdfPCell(new Paragraph("aws", AWS_LOGO_FONT));
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        headerTable.addCell(logoCell);

        PdfPCell titleCell = new PdfPCell(new Paragraph("AWS bill summary", HEADER_FONT));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        titleCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        headerTable.addCell(titleCell);
        
        document.add(headerTable);
    }


    private void createSummaryTable(Document document, AwsInvoice invoice) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.addCell(createBorderlessCell("Service provider", BOLD_FONT, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell("Total in USD", BOLD_FONT, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell(invoice.serviceProvider(), NORMAL_FONT, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell("USD " + formatCurrency(invoice.totalInUSD()), NORMAL_FONT, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell("Grand total:", BOLD_FONT, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell("USD " + formatCurrency(invoice.totalInUSD()), BOLD_FONT, Element.ALIGN_LEFT));
        document.add(table);
    }

    private void createHighestCostTable(Document document, AwsInvoice invoice) throws DocumentException {
        document.add(new Paragraph("Highest cost by service provider", SECTION_HEADER_FONT));
        document.add(new Paragraph(invoice.serviceProvider(), SMALL_NORMAL_FONT));
        addSpacer(document, 5);

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        
        // Highest Service Spend
        PdfPCell serviceHeader = createBorderlessCell("Highest service spend", BOLD_FONT, Element.ALIGN_LEFT);
        serviceHeader.setColspan(2);
        table.addCell(serviceHeader);
        addKeyValueRow(table, "Service name", invoice.highestServiceSpendName());
        addKeyValueRow(table, "Highest service spend", "USD " + formatCurrency(invoice.highestServiceSpendAmount()));
        addKeyValueRow(table, "Trend compared to prior month", invoice.trendComparedToPriorMonth());

        // Highest Region Spend
        PdfPCell regionHeader = createBorderlessCell("Highest AWS Region spend", BOLD_FONT, Element.ALIGN_LEFT);
        regionHeader.setColspan(2);
        regionHeader.setPaddingTop(10);
        table.addCell(regionHeader);
        addKeyValueRow(table, "Region name", invoice.highestRegionSpendName());
        addKeyValueRow(table, "Highest AWS Region spend", "USD " + formatCurrency(invoice.highestRegionSpendAmount()));
        addKeyValueRow(table, "Trend compared to prior month", invoice.regionTrendComparedToPriorMonth());
        
        document.add(table);
    }
    
    private void createBillingInfoTable(Document document, AwsInvoice invoice) throws DocumentException {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        addHeaderRow(table, List.of("Billing period", "Account ID", "Date printed"));
        table.addCell(createBorderlessCell(invoice.billingPeriod(), NORMAL_FONT, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell(invoice.payerAccountId(), NORMAL_FONT, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell(invoice.datePrinted(), NORMAL_FONT, Element.ALIGN_LEFT));
        document.add(table);
    }

    private void createChargesByService(Document document, AwsInvoice invoice) throws DocumentException {
        document.add(new Paragraph("Charges by service", SECTION_HEADER_FONT));
        document.add(new Paragraph(invoice.serviceProvider() + " (" + invoice.chargesByService().size() + ")", NORMAL_FONT));
        addSpacer(document, 10);

        PdfPTable mainTable = new PdfPTable(3);
        mainTable.setWidthPercentage(100);
        mainTable.setWidths(new float[]{2.5f, 1f, 1f});

        addHeaderRow(mainTable, List.of("Description", "Usage Quantity", "Amount in USD"));

        for (AwsInvoice.ServiceCharge charge : invoice.chargesByService()) {
            // Service Name and Total Header
            mainTable.addCell(createBorderlessBoldCell(charge.serviceName(), Element.ALIGN_LEFT, 2));
            mainTable.addCell(createBorderlessBoldCell("USD " + formatCurrency(charge.totalAmount()), Element.ALIGN_RIGHT, 1));

            // Service Details
            String lastRegion = "";
            for(AwsInvoice.ServiceDetail detail : charge.serviceDetails()) {
                // Add region sub-header if it's different from the last one
                if (!detail.region().equals(lastRegion)) {
                    mainTable.addCell(createBorderlessCell(detail.region(), SMALL_NORMAL_FONT, Element.ALIGN_LEFT, 3, 5f));
                    lastRegion = detail.region();
                }

                mainTable.addCell(createBorderlessCell(detail.description(), SMALL_NORMAL_FONT, Element.ALIGN_LEFT));
                mainTable.addCell(createBorderlessCell(detail.usageQuantity(), SMALL_NORMAL_FONT, Element.ALIGN_RIGHT));
                mainTable.addCell(createBorderlessCell("USD " + formatCurrency(detail.amount()), SMALL_NORMAL_FONT, Element.ALIGN_RIGHT));
            }
            // Add a spacer after each service block
            addSpacerCell(mainTable, 10f);
        }
        document.add(mainTable);
    }

    private void createTotalsTable(Document document, AwsInvoice invoice) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(50);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        
        addKeyValueRow(table, "Total pre-tax", "USD " + formatCurrency(invoice.totalPreTax()), NORMAL_FONT, BOLD_FONT);
        addKeyValueRow(table, "Total tax", "USD " + formatCurrency(invoice.totalTax()), NORMAL_FONT, BOLD_FONT);
        
        PdfPCell totalLabel = createBorderlessCell("Total invoiced charges", BOLD_FONT, Element.ALIGN_LEFT);
        totalLabel.setBorder(Rectangle.TOP);
        totalLabel.setBorderColor(TABLE_BORDER_COLOR);
        totalLabel.setPaddingTop(5);
        table.addCell(totalLabel);

        PdfPCell totalValue = createBorderlessCell("USD " + formatCurrency(invoice.totalInvoicedCharges()), BOLD_FONT, Element.ALIGN_RIGHT);
        totalValue.setBorder(Rectangle.TOP);
        totalValue.setBorderColor(TABLE_BORDER_COLOR);
        totalValue.setPaddingTop(5);
        table.addCell(totalValue);

        document.add(table);
    }
    
    private void addEmptySection(Document document, String title) throws DocumentException {
        addEmptySection(document, title, null);
    }

    private void addEmptySection(Document document, String title, String subtitle) throws DocumentException {
        addSpacer(document, 15);
        document.add(new Paragraph(title, BOLD_FONT));
        if (subtitle != null) {
            document.add(new Paragraph(subtitle, SMALL_NORMAL_FONT));
        }
        addSpacer(document, 5);
        document.add(new Paragraph("No data to display.", NORMAL_FONT));
    }

    // --- Helper Methods for PDF Styling ---

    private String formatCurrency(BigDecimal value) {
        if (value == null) return "0.00";
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void addHeaderRow(PdfPTable table, List<String> headers) {
        headers.forEach(header -> {
            PdfPCell cell = new PdfPCell(new Phrase(header, BOLD_FONT));
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(TABLE_BORDER_COLOR);
            cell.setBorderWidthBottom(1f);
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            cell.setPaddingBottom(5);
            table.addCell(cell);
        });
    }

    private PdfPCell createBorderlessCell(String text, Font font, int alignment) {
        return createBorderlessCell(text, font, alignment, 1, 2f);
    }
    
    private PdfPCell createBorderlessCell(String text, Font font, int alignment, int colspan, float paddingTop) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setColspan(colspan);
        cell.setPadding(2);
        cell.setPaddingTop(paddingTop);
        return cell;
    }

    private PdfPCell createBorderlessBoldCell(String text, int alignment, int colspan) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BOLD_FONT));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setColspan(colspan);
        cell.setPadding(2);
        cell.setPaddingTop(5f);
        cell.setPaddingBottom(5f);
        return cell;
    }

    private void addKeyValueRow(PdfPTable table, String key, String value) {
        addKeyValueRow(table, key, value, SMALL_NORMAL_FONT, SMALL_NORMAL_FONT);
    }
    
    private void addKeyValueRow(PdfPTable table, String key, String value, Font keyFont, Font valueFont) {
        table.addCell(createBorderlessCell(key, keyFont, Element.ALIGN_LEFT));
        table.addCell(createBorderlessCell(value, valueFont, Element.ALIGN_RIGHT));
    }

    private void addSpacer(Document document, float space) throws DocumentException {
        document.add(new Paragraph(" ", new Font(Font.FontFamily.HELVETICA, space)));
    }

    private void addSpacerCell(PdfPTable table, float height) {
        PdfPCell spacer = new PdfPCell(new Phrase(" "));
        spacer.setBorder(Rectangle.NO_BORDER);
        spacer.setColspan(3);
        spacer.setFixedHeight(height);
        table.addCell(spacer);
    }
}
