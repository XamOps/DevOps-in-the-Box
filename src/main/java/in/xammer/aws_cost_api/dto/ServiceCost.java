package in.xammer.aws_cost_api.dto;

import java.math.BigDecimal;

public record ServiceCost(
    String name,
    BigDecimal cost
) {}