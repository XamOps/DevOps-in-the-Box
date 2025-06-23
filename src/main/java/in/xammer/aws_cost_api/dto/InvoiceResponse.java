package in.xammer.aws_cost_api.dto;
import java.math.BigDecimal;
import java.util.List;
public record InvoiceResponse(List<InvoiceSummary> invoiceList, BigDecimal totalAwsBill, BigDecimal totalDiscount, BigDecimal totalPayable) {}
