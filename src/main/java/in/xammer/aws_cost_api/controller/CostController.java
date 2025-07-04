package in.xammer.aws_cost_api.controller;

import com.itextpdf.text.DocumentException;
import in.xammer.aws_cost_api.dto.AccountCost;
import in.xammer.aws_cost_api.dto.AwsInvoice;
import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.dto.InvoiceSummary;
import in.xammer.aws_cost_api.service.AwsCostService;
import in.xammer.aws_cost_api.service.AwsPdfInvoiceGenerator;
import in.xammer.aws_cost_api.service.GeminiService;
import in.xammer.aws_cost_api.service.PdfInvoiceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CostController {

    private final AwsCostService awsCostService;
    private final GeminiService geminiService;
    private final PdfInvoiceService pdfInvoiceService;
    private final AwsPdfInvoiceGenerator awsPdfInvoiceGenerator;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public CostController(
            AwsCostService awsCostService,
            GeminiService geminiService,
            PdfInvoiceService pdfInvoiceService,
            AwsPdfInvoiceGenerator awsPdfInvoiceGenerator) {
        this.awsCostService = awsCostService;
        this.geminiService = geminiService;
        this.pdfInvoiceService = pdfInvoiceService;
        this.awsPdfInvoiceGenerator = awsPdfInvoiceGenerator;
    }

    @GetMapping("/costs")
    public CostResponse getCosts(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String granularity) {
        if (start == null || end == null || granularity == null) {
            LocalDate today = LocalDate.now();
            YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
            start = lastMonth.atDay(1).format(formatter);
            end = lastMonth.atEndOfMonth().plusDays(1).format(formatter);
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
            InvoiceSummary invoice = awsCostService.getInvoiceForAccount(start, end, accountId);
            if (invoice != null) {
                return ResponseEntity.ok(invoice);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Invoice not found for account " + accountId);
            }
        } else {
            var invoiceResponse = awsCostService.getInvoiceSummaries(start, end);
            return ResponseEntity.ok(invoiceResponse.invoiceList());
        }
    }

    @GetMapping("/invoices/{accountId}/aws-style-pdf")
    public ResponseEntity<byte[]> downloadAwsInvoicePdf(
            @PathVariable String accountId,
            @RequestParam String start,
            @RequestParam String end) {
        try {
            AwsInvoice invoice = awsCostService.getAwsInvoiceForAccount(start, end, accountId);
            if (invoice == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] pdf = awsPdfInvoiceGenerator.generatePdf(invoice);
            String fileName = "aws-invoice-" + accountId + "-" + start + "_to_" + end + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (DocumentException | IOException e) {
            System.err.println("Error generating AWS-style PDF for account " + accountId + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/invoices/{accountId}/aws-invoice-data")
    public ResponseEntity<AwsInvoice> getAwsInvoiceData(
            @PathVariable String accountId,
            @RequestParam String start,
            @RequestParam String end) {
        AwsInvoice invoice = awsCostService.getAwsInvoiceForAccount(start, end, accountId);
        if (invoice == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(invoice);
    }
    
    @PostMapping("/discounts/{accountId}")
    public ResponseEntity<Void> updateDiscount(@PathVariable String accountId, @RequestParam BigDecimal discount) {
        awsCostService.updateDiscount(accountId, discount);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/costs/{accountId}/optimize-suggestions")
    public ResponseEntity<String> getOptimizationSuggestions(
            @PathVariable String accountId,
            @RequestBody AccountCost accountCost
    ) {
        try {
            String suggestions = geminiService.getOptimizationSuggestions(
                    accountCost.name(),
                    accountId,
                    accountCost.services()
            );
            return ResponseEntity.ok(suggestions);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating suggestions: " + e.getMessage());
        }
    }
}
