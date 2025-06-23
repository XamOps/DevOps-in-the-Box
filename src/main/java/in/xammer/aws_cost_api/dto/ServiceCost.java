package in.xammer.aws_cost_api.dto;
import java.math.BigDecimal;
// Add this to make ServiceCost serializable
public record ServiceCost(
    String name,
    BigDecimal cost
) {
    // Add default constructor for JSON deserialization
    public ServiceCost {
        if (name == null) name = "";
        if (cost == null) cost = BigDecimal.ZERO;
    }
}