package in.xammer.aws_cost_api.controller;

import com.itextpdf.text.DocumentException;
import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.dto.InvoiceSummary;
import in.xammer.aws_cost_api.service.AwsCostService;
import in.xammer.aws_cost_api.service.DiscountService;
import in.xammer.aws_cost_api.service.GeminiService;
import in.xammer.aws_cost_api.service.PdfInvoiceService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CostController {

    private final AwsCostService awsCostService;
    private final GeminiService geminiService;
    private final PdfInvoiceService pdfInvoiceService;
    private final DiscountService discountService; // Added dependency
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public CostController(
            AwsCostService awsCostService,
            GeminiService geminiService,
            PdfInvoiceService pdfInvoiceService,
            DiscountService discountService) { // Updated constructor
        this.awsCostService = awsCostService;
        this.geminiService = geminiService;
        this.pdfInvoiceService = pdfInvoiceService;
        this.discountService = discountService;
    }

    @GetMapping("/costs")
    public CostResponse getCosts(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String granularity) {
        // If any parameter is missing, default to the last full month.
        if (start == null || end == null || granularity == null) {
            LocalDate today = LocalDate.now();
            YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
            start = lastMonth.atDay(1).format(formatter);
            end = lastMonth.atEndOfMonth().plusDays(1).format(formatter); // AWS end date is exclusive
            granularity = "MONTHLY";
        }
        return awsCostService.getCostAndUsage(start, end, granularity);
    }

    @GetMapping("/invoices")
    public ResponseEntity<?> getInvoices(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String accountId) {
        if (start == null || end == null) {
            LocalDate today = LocalDate.now();
            YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
            start = lastMonth.atDay(1).format(formatter);
            end = lastMonth.atEndOfMonth().plusDays(1).format(formatter);
        }

        if (accountId != null && !accountId.isEmpty()) {
            // Return a single invoice for the specific account
            InvoiceSummary invoice = awsCostService.getInvoiceForAccount(start, end, accountId);
            if (invoice != null) {
                return ResponseEntity.ok(invoice);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Invoice not found for account " + accountId);
            }
        } else {
            // Return all invoices (existing behavior)
            List<InvoiceSummary> invoices = awsCostService.getInvoiceSummaries(start, end);
            return ResponseEntity.ok(invoices);
        }
    }

    @GetMapping("/invoices/{accountId}/pdf")
    public ResponseEntity<byte[]> downloadInvoicePdf(
            @PathVariable String accountId,
            @RequestParam String start,
            @RequestParam String end) {
        InvoiceSummary invoice = awsCostService.getInvoiceForAccount(start, end, accountId);
        if (invoice == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] pdf = pdfInvoiceService.generatePdf(invoice);
            String fileName = "invoice-" + invoice.id() + "-" + start + "_to_" + end + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (DocumentException e) {
            // Log the exception for debugging purposes
            System.err.println("Error generating PDF for account " + accountId + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (java.io.IOException e) {
            // Log the IOException for debugging purposes
            System.err.println("IO error generating PDF for account " + accountId + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/discounts/{accountId}")
    public ResponseEntity<String> setDiscount(
            @PathVariable String accountId,
            @RequestParam BigDecimal discount) {
        try {
            discountService.setCustomDiscount(accountId, discount);
            return ResponseEntity.ok("Discount updated successfully for account " + accountId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            System.err.println("Error updating discount for account " + accountId + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Error updating discount: " + e.getMessage());
        }
    }
}