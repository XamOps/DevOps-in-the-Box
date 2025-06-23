package in.xammer.aws_cost_api.dto;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
// Add this to make AccountCost serializable
public record AccountCost(
    String id,
    String name,
    BigDecimal totalCost,
    List<ServiceCost> services
) {
    // Add default constructor for JSON deserialization
    public AccountCost {
        if (id == null) id = "";
        if (name == null) name = "";
        if (totalCost == null) totalCost = BigDecimal.ZERO;
        if (services == null) services = new ArrayList<>();
    }
}