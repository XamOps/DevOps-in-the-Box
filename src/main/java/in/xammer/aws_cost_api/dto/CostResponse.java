package in.xammer.aws_cost_api.dto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// Import AccountCost from the correct package or define it here if missing

// Using Records for concise, immutable DTOs
public record CostResponse(
    BigDecimal grandTotal,
    List<AccountCost> accounts,
    Map<String, String> query
) {}