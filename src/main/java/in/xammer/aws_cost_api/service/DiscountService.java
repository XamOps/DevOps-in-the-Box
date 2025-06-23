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
        accountDiscounts.put("730335563558", new BigDecimal("10.0"));
        accountDiscounts.put("036440838380", new BigDecimal("5.0"));
    }

    public void updateDiscount(String accountId, BigDecimal discount) {
        accountDiscounts.put(accountId, discount);
    }

    public BigDecimal getDiscount(String accountId) {
        return accountDiscounts.getOrDefault(accountId, BigDecimal.ZERO);
    }
}