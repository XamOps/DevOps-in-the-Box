package in.xammer.aws_cost_api.dto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
public record CostResponse(BigDecimal grandTotal, List<AccountCost> accounts, Map<String, String> query) {}