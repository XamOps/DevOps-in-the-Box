package in.xammer.aws_cost_api.service;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import in.xammer.aws_cost_api.dto.InvoiceSummary;
import in.xammer.aws_cost_api.dto.ServiceCost;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class PdfInvoiceService {
    public byte[] generatePdf(InvoiceSummary invoice) throws DocumentException, java.io.IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);

            document.open();

            // Define fonts
            Font headerFont = new Font(Font.FontFamily.HELVETICA, 18, Font.BOLD, BaseColor.DARK_GRAY);
            Font subHeaderFont = new Font(Font.FontFamily.HELVETICA, 12, Font.NORMAL, BaseColor.GRAY);
            Font boldFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD);
            Font normalFont = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL);

            // Add header
            Paragraph header = new Paragraph("Xammer Cloud Services", headerFont);
            header.setAlignment(Element.ALIGN_CENTER);
            document.add(header);
            Paragraph subHeader = new Paragraph("Invoice for AWS Usage", subHeaderFont);
            subHeader.setAlignment(Element.ALIGN_CENTER);
            document.add(subHeader);

            document.add(Chunk.NEWLINE);

            // Add invoice details table
            PdfPTable detailsTable = new PdfPTable(2);
            detailsTable.setWidthPercentage(100);
            detailsTable.setWidths(new float[]{1, 1});
            detailsTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            detailsTable.addCell(new Paragraph("Bill To:", boldFont));
            detailsTable.addCell(new Paragraph("Invoice Details:", boldFont));
            detailsTable.addCell(new Paragraph(invoice.name(), normalFont));
            detailsTable.addCell(new Paragraph("Invoice #: " + invoice.id(), normalFont));
            detailsTable.addCell(new Paragraph("Account ID: " + invoice.id(), normalFont));
            detailsTable.addCell(new Paragraph("Date: " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy")), normalFont));

            document.add(detailsTable);
            document.add(Chunk.NEWLINE);

            // Add cost summary table
            PdfPTable table = new PdfPTable(2); // Changed to 2 columns for simplicity as per new logic
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            
            // Add table headers
            table.addCell(new Phrase("Service Description", boldFont));
            table.addCell(new Phrase("Amount (USD)", boldFont));


            if (invoice.services() != null) {
                for (ServiceCost service : invoice.services()) {
                    table.addCell(new Phrase(service.name(), normalFont));
                    table.addCell(new Phrase("$" + service.cost().setScale(2, RoundingMode.HALF_UP), normalFont));
                }
            }
            
            document.add(table);
            document.add(Chunk.NEWLINE);

            // Add totals section
            PdfPTable totalsTable = new PdfPTable(2);
            totalsTable.setWidthPercentage(50);
            totalsTable.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalsTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            totalsTable.addCell(new Phrase("Subtotal (AWS Cost):", normalFont));
            totalsTable.addCell(new Phrase("$" + invoice.awsTotal().setScale(2, RoundingMode.HALF_UP), normalFont));
            
            totalsTable.addCell(new Phrase("Xammer Discount (" + invoice.discountPercentage() + "%):", normalFont));
            totalsTable.addCell(new Phrase("-$" + invoice.discountAmount().setScale(2, RoundingMode.HALF_UP), normalFont));

            totalsTable.addCell(new Phrase("Net Payable:", boldFont));
            totalsTable.addCell(new Phrase("$" + invoice.netPayable().setScale(2, RoundingMode.HALF_UP), boldFont));
            
            document.add(totalsTable);

            document.close();
            return baos.toByteArray();
        }
    }
}