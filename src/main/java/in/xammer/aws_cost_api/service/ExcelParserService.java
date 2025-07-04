package in.xammer.aws_cost_api.service;

import in.xammer.aws_cost_api.dto.AwsInvoice;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExcelParserService {

    public AwsInvoice parseInvoiceFromExcel(InputStream inputStream) throws IOException {
        Workbook workbook = new XSSFWorkbook(inputStream);
        Sheet sheet = workbook.getSheetAt(0);

        Map<String, List<AwsInvoice.ServiceDetail>> detailsByService = new HashMap<>();
        Map<String, BigDecimal> totalsByService = new HashMap<>();
        Map<String, BigDecimal> totalsByRegion = new HashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        // Iterate over rows, skipping the header row (index 0)
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            try {
                String serviceName = getCellStringValue(row.getCell(0));

                // *** FIX: Check if the service name starts with "Total" (case-insensitive) ***
                if (serviceName.trim().toLowerCase().startsWith("total")) {
                    continue;
                }

                String description = getCellStringValue(row.getCell(1));
                String region = getCellStringValue(row.getCell(2));
                String usageQuantity = getCellStringValue(row.getCell(3));
                BigDecimal amount = getCellBigDecimalValue(row.getCell(4));

                if (serviceName.isEmpty() || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue; // Skip rows with no service name or zero amount
                }

                grandTotal = grandTotal.add(amount);

                AwsInvoice.ServiceDetail detail = new AwsInvoice.ServiceDetail(description, usageQuantity, region, amount);
                detailsByService.computeIfAbsent(serviceName, k -> new ArrayList<>()).add(detail);
                totalsByService.merge(serviceName, amount, BigDecimal::add);
                if (!region.equalsIgnoreCase("Global") && !region.isEmpty()) {
                    totalsByRegion.merge(region, amount, BigDecimal::add);
                }

            } catch (Exception e) {
                // Log error for the specific row and continue
                System.err.println("Could not parse row " + (i + 1) + ". Error: " + e.getMessage());
            }
        }

        workbook.close();

        List<AwsInvoice.ServiceCharge> serviceCharges = new ArrayList<>();
        for (String serviceName : totalsByService.keySet()) {
            List<AwsInvoice.ServiceDetail> details = detailsByService.get(serviceName);
            details.sort(Comparator.comparing(AwsInvoice.ServiceDetail::amount).reversed());
            serviceCharges.add(new AwsInvoice.ServiceCharge(serviceName, totalsByService.get(serviceName), details));
        }
        serviceCharges.sort(Comparator.comparing(AwsInvoice.ServiceCharge::totalAmount).reversed());

        Map.Entry<String, BigDecimal> highestService = totalsByService.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        Map.Entry<String, BigDecimal> highestRegion = totalsByRegion.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);

        return new AwsInvoice(
            "Amazon Web Services India Private Limited",
            grandTotal,
            "N/A (Uploaded)",
            highestService != null ? highestService.getKey() : "N/A",
            highestService != null ? highestService.getValue() : BigDecimal.ZERO,
            "N/A",
            highestRegion != null ? highestRegion.getKey() : "N/A",
            highestRegion != null ? highestRegion.getValue() : BigDecimal.ZERO,
            "N/A",
            LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")),
            serviceCharges,
            grandTotal,
            BigDecimal.ZERO,
            grandTotal
        );
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }

    private BigDecimal getCellBigDecimalValue(Cell cell) {
        if (cell == null) {
            return BigDecimal.ZERO;
        }
    
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
    
        if (cell.getCellType() == CellType.STRING) {
            String cellValue = cell.getStringCellValue().trim();
            // Remove currency symbols, commas, and any other non-numeric characters except the decimal point
            String cleanValue = cellValue.replaceAll("[^\\d.-]", "");
            if (cleanValue.isEmpty()) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(cleanValue);
            } catch (NumberFormatException e) {
                System.err.println("Could not parse string to BigDecimal: " + cellValue);
                return BigDecimal.ZERO;
            }
        }
    
        return BigDecimal.ZERO;
    }
}
