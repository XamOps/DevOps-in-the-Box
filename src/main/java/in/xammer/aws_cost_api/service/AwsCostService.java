package in.xammer.aws_cost_api.service;

import in.xammer.aws_cost_api.dto.AccountCost;
import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.dto.InvoiceResponse;
import in.xammer.aws_cost_api.dto.InvoiceSummary;
import in.xammer.aws_cost_api.dto.ServiceCost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.CostExplorerException;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.MetricValue;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class AwsCostService {

    private final CostExplorerClient costExplorerClient;
    private final OrganizationsClient organizationsClient;
    private final DiscountService discountService;

    @Autowired
    public AwsCostService(
            CostExplorerClient costExplorerClient,
            OrganizationsClient organizationsClient,
            DiscountService discountService) {
        this.costExplorerClient = costExplorerClient;
        this.organizationsClient = organizationsClient;
        this.discountService = discountService;
    }

    @Cacheable(value = "awsCosts", key = "{#startDate, #endDate, #granularity}")
    public CostResponse getCostAndUsage(String startDate, String endDate, String granularity) {
        System.out.printf("CACHE MISS: Fetching new data from AWS for %s to %s with %s granularity%n", startDate, endDate,
                granularity);

        // Fetch account names in parallel
        CompletableFuture<Map<String, String>> accountsFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return organizationsClient.listAccountsPaginator()
                        .stream()
                        .flatMap(response -> response.accounts().stream())
                        .collect(Collectors.toMap(Account::id, Account::name));
            } catch (Exception e) {
                System.err.println("Error fetching account names from Organizations: " + e.getMessage());
                return Collections.emptyMap(); // Return an empty map on error
            }
        });

        // Fetch cost data in parallel
        CompletableFuture<List<Group>> costDataFuture = CompletableFuture.supplyAsync(() -> {
            List<Group> allGroups = new ArrayList<>();
            String nextToken = null;
            do {
                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                        .timePeriod(tp -> tp.start(startDate).end(endDate))
                        .granularity(granularity)
                        .metrics("NetUnblendedCost") // Using the correct metric for accurate totals
                        .groupBy(
                                GroupDefinition.builder().type("DIMENSION").key("LINKED_ACCOUNT").build(),
                                GroupDefinition.builder().type("DIMENSION").key("SERVICE").build())
                        .nextPageToken(nextToken)
                        .build();

                try {
                    GetCostAndUsageResponse response = costExplorerClient.getCostAndUsage(request);
                    if (response.resultsByTime() != null) {
                        response.resultsByTime().forEach(rbt -> {
                            if (rbt.groups() != null) {
                                allGroups.addAll(rbt.groups());
                            }
                        });
                    }
                    nextToken = response.nextPageToken();
                } catch (CostExplorerException ce) {
                    System.err.printf(
                            "AWS Cost Explorer SDK Exception for query (%s to %s, %s): %s. AWS Request ID: %s%n",
                            startDate, endDate, granularity, ce.getMessage(), ce.requestId());
                    throw new RuntimeException("Failed to retrieve data from AWS Cost Explorer: " + ce.getMessage(), ce);
                }
            } while (nextToken != null);
            return allGroups;
        });

        // Wait for both futures to complete, then process the results
        return CompletableFuture.allOf(accountsFuture, costDataFuture)
                .thenApply(v -> {
                    Map<String, String> accountMap = accountsFuture.join();
                    List<Group> allGroups = costDataFuture.join();
                    return processCostData(accountMap, allGroups, startDate, endDate, granularity);
                }).join();
    }

    private CostResponse processCostData(Map<String, String> accountMap, List<Group> allGroups, String startDate,
            String endDate, String granularity) {
        
        Map<String, Map<String, BigDecimal>> costData = new HashMap<>();
        allGroups.forEach(group -> {
            if (group.keys() == null || group.keys().size() < 2 || group.metrics() == null) return;
            
            String accountId = group.keys().get(0);
            String serviceName = group.keys().get(1);
            MetricValue metric = group.metrics().get("NetUnblendedCost");

            if (metric != null && metric.amount() != null) {
                BigDecimal amount = new BigDecimal(metric.amount());
                costData.computeIfAbsent(accountId, k -> new HashMap<>()).merge(serviceName, amount, BigDecimal::add);
            }
        });

        List<AccountCost> accountCosts = new ArrayList<>();
        BigDecimal grandTotal = costData.values().stream()
            .flatMap(map -> map.values().stream())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        costData.forEach((accountId, services) -> {
            BigDecimal accountTotal = services.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            List<ServiceCost> serviceCosts = services.entrySet().stream()
                    .map(e -> new ServiceCost(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(ServiceCost::cost).reversed())
                    .toList();

            accountCosts.add(new AccountCost(
                    accountId,
                    accountMap.getOrDefault(accountId, "Account " + accountId),
                    accountTotal,
                    serviceCosts));
        });

        accountCosts.sort(Comparator.comparing(AccountCost::totalCost).reversed());

        return new CostResponse(
                grandTotal,
                accountCosts,
                Map.of("start", startDate, "end", endDate, "granularity", granularity));
    }

    @Cacheable(value = "invoiceSummaries", key = "{#startDate, #endDate}")
    public InvoiceResponse getInvoiceSummaries(String startDate, String endDate) {
        CostResponse costData = getCostAndUsage(startDate, endDate, "MONTHLY");

        List<InvoiceSummary> invoiceSummaries = new ArrayList<>();
        BigDecimal oneHundred = new BigDecimal("100");
        BigDecimal zero = BigDecimal.ZERO;
        
        BigDecimal grandTotalAwsBill = BigDecimal.ZERO;
        BigDecimal grandTotalDiscount = BigDecimal.ZERO;

        for (AccountCost account : costData.accounts()) {
            BigDecimal awsTotal = account.totalCost();
            BigDecimal discountPercentage = discountService.getDiscount(account.id());
            BigDecimal discountAmount = BigDecimal.ZERO;

            if (discountPercentage.compareTo(zero) > 0 && awsTotal.compareTo(zero) > 0) {
                discountAmount = awsTotal.multiply(discountPercentage).divide(oneHundred, 2, RoundingMode.HALF_UP);
            }

            BigDecimal netPayable = awsTotal.subtract(discountAmount);

            grandTotalAwsBill = grandTotalAwsBill.add(awsTotal);
            grandTotalDiscount = grandTotalDiscount.add(discountAmount);

            invoiceSummaries.add(new InvoiceSummary(
                    account.id(),
                    account.name(),
                    awsTotal.setScale(2, RoundingMode.HALF_UP),
                    discountPercentage,
                    discountAmount.setScale(2, RoundingMode.HALF_UP),
                    netPayable.setScale(2, RoundingMode.HALF_UP),
                    account.services() 
            ));
        }

        invoiceSummaries.sort(Comparator.comparing(InvoiceSummary::netPayable).reversed());
        
        BigDecimal grandTotalPayable = grandTotalAwsBill.subtract(grandTotalDiscount);

        return new InvoiceResponse(
            invoiceSummaries,
            grandTotalAwsBill.setScale(2, RoundingMode.HALF_UP),
            grandTotalDiscount.setScale(2, RoundingMode.HALF_UP),
            grandTotalPayable.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public InvoiceSummary getInvoiceForAccount(String startDate, String endDate, String accountId) {
        CostResponse costData = getCostAndUsage(startDate, endDate, "MONTHLY");

        Optional<AccountCost> accountOpt = costData.accounts().stream()
                .filter(acc -> acc.id().equals(accountId))
                .findFirst();

        if (accountOpt.isEmpty()) {
            return null;
        }

        AccountCost account = accountOpt.get();
        BigDecimal discountPercentage = discountService.getDiscount(account.id());
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal awsTotal = account.totalCost();

        if (discountPercentage.compareTo(BigDecimal.ZERO) > 0 && awsTotal.compareTo(BigDecimal.ZERO) > 0) {
            discountAmount = awsTotal.multiply(discountPercentage)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        BigDecimal netPayable = awsTotal.subtract(discountAmount);

        return new InvoiceSummary(
                account.id(),
                account.name(),
                awsTotal.setScale(2, RoundingMode.HALF_UP),
                discountPercentage,
                discountAmount,
                netPayable.setScale(2, RoundingMode.HALF_UP),
                account.services()
        );
    }
}
