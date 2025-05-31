package in.xammer.aws_cost_api.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountCost(
    String id,
    String name,
    BigDecimal totalCost,
    List<ServiceCost> services
) {}
