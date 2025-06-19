package in.xammer.aws_cost_api.service;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PostConstruct;

@Service
public class DiscountService {
    private final Map<String, BigDecimal> accountDiscounts = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Default discounts
        accountDiscounts.put("730335563558", new BigDecimal("10.0"));
        accountDiscounts.put("036440838380", new BigDecimal("5.0"));
    }

    public void updateDiscount(String accountId, BigDecimal discount) {
        accountDiscounts.put(accountId, discount);
    }

    public BigDecimal getDiscount(String accountId) {
        return accountDiscounts.getOrDefault(accountId, BigDecimal.ZERO);
    }

    /**
     * Sets a custom discount for an account, with validation.
     * To be used by an API endpoint.
     *
     * @param accountId The AWS account ID.
     * @param discount The discount percentage to apply.
     * @throws IllegalArgumentException if the discount is not between 0 and 100.
     */
    public void setCustomDiscount(String accountId, BigDecimal discount) {
        if (discount.compareTo(BigDecimal.ZERO) < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Discount must be between 0 and 100");
        }
        accountDiscounts.put(accountId, discount);
    }
}