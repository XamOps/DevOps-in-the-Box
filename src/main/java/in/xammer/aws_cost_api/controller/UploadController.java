package in.xammer.aws_cost_api.controller;

import com.itextpdf.text.DocumentException;
import in.xammer.aws_cost_api.dto.AwsInvoice;
import in.xammer.aws_cost_api.service.AwsPdfInvoiceGenerator;
import in.xammer.aws_cost_api.service.ExcelParserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/upload-invoice")
@CrossOrigin
public class UploadController {

    private final ExcelParserService excelParserService;
    private final AwsPdfInvoiceGenerator awsPdfInvoiceGenerator;

    public UploadController(ExcelParserService excelParserService, AwsPdfInvoiceGenerator awsPdfInvoiceGenerator) {
        this.excelParserService = excelParserService;
        this.awsPdfInvoiceGenerator = awsPdfInvoiceGenerator;
    }

    @PostMapping
    public ResponseEntity<?> uploadInvoiceAndGeneratePdf(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please select a file to upload."));
        }

        try {
            AwsInvoice invoice = excelParserService.parseInvoiceFromExcel(file.getInputStream());
            byte[] pdf = awsPdfInvoiceGenerator.generatePdf(invoice);

            String originalFilename = file.getOriginalFilename();
            String baseFilename = originalFilename != null ? originalFilename.replaceAll("\\.(xlsx|xls)$", "") : "invoice";
            String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String pdfFilename = String.format("%s-%s.pdf", baseFilename, date);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdfFilename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);

        } catch (IOException e) {
            System.err.println("Error processing uploaded file: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to process the uploaded file."));
        } catch (DocumentException e) {
            System.err.println("Error generating PDF: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to generate PDF from the provided data."));
        }
    }
}
