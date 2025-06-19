package in.xammer.aws_cost_api.dto;

import java.math.BigDecimal;
import java.util.List;

public record InvoiceSummary(
    String id,
    String name,
    BigDecimal awsTotal,
    BigDecimal discountPercentage,
    BigDecimal discountAmount,
    BigDecimal netPayable,
    List<ServiceCost> services 
    
) {}