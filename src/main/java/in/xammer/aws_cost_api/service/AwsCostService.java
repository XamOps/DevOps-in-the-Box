package in.xammer.aws_cost_api.service;

import in.xammer.aws_cost_api.dto.AccountCost;
import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.dto.InvoiceSummary;
import in.xammer.aws_cost_api.dto.ServiceCost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AwsCostService {

    private final CostExplorerClient costExplorerClient;
    private final OrganizationsClient organizationsClient;
    private final DiscountService discountService;
    private final Map<String, CostResponse> costCache = new ConcurrentHashMap<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;

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
        String cacheKey = startDate + endDate + granularity;
        // Use computeIfAbsent for thread-safe, atomic cache population
        return costCache.computeIfAbsent(cacheKey, k -> fetchCostData(startDate, endDate, granularity));
    }

    private CostResponse fetchCostData(String startDate, String endDate, String granularity) {
        System.out.printf("Cache miss. Fetching from AWS for %s to %s with %s granularity%n", startDate, endDate,
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
                return new HashMap<>(); // Return an empty map on error to avoid breaking the process
            }
        });

        // Fetch cost data in parallel
        CompletableFuture<List<Group>> costDataFuture = CompletableFuture.supplyAsync(() -> {
            List<Group> allGroups = new ArrayList<>();
            String nextToken = null;
            do {
                GroupDefinition linkedAccountGroup = GroupDefinition.builder().type("DIMENSION").key("LINKED_ACCOUNT")
                        .build();
                GroupDefinition serviceGroup = GroupDefinition.builder().type("DIMENSION").key("SERVICE").build();

                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                        .timePeriod(tp -> tp.start(startDate).end(endDate))
                        .granularity(granularity)
                        .metrics("UnblendedCost")
                        .groupBy(linkedAccountGroup, serviceGroup)
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
        // Process and aggregate data
        Map<String, Map<String, BigDecimal>> costData = new HashMap<>();
        allGroups.forEach(group -> {
            if (group.keys() == null || group.keys().size() < 2 || group.metrics() == null)
                return;
            String accountId = group.keys().get(0);
            String serviceName = group.keys().get(1);
            MetricValue metric = group.metrics().get("UnblendedCost");
            if (metric != null && metric.amount() != null) {
                BigDecimal amount = new BigDecimal(metric.amount());
                costData.computeIfAbsent(accountId, k -> new HashMap<>()).merge(serviceName, amount, BigDecimal::add);
            }
        });

        BigDecimal grandTotal = BigDecimal.ZERO;
        List<AccountCost> accountCosts = new ArrayList<>();

        for (Map.Entry<String, Map<String, BigDecimal>> entry : costData.entrySet()) {
            String accountId = entry.getKey();
            Map<String, BigDecimal> services = entry.getValue();
            BigDecimal accountTotal = services.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            grandTotal = grandTotal.add(accountTotal);

            List<ServiceCost> serviceCosts = services.entrySet().stream()
                    .map(e -> new ServiceCost(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(ServiceCost::cost).reversed())
                    .toList();

            accountCosts.add(new AccountCost(
                    accountId,
                    accountMap.getOrDefault(accountId, "Account " + accountId),
                    accountTotal,
                    serviceCosts));
        }

        accountCosts.sort(Comparator.comparing(AccountCost::totalCost).reversed());

        return new CostResponse(
                grandTotal,
                accountCosts,
                Map.of("start", startDate, "end", endDate, "granularity", granularity));
    }

    @Cacheable(value = "invoiceSummaries", key = "{#startDate, #endDate}")
    public List<InvoiceSummary> getInvoiceSummaries(String startDate, String endDate) {
        CostResponse costData = getCostAndUsage(startDate, endDate, "MONTHLY");

        List<InvoiceSummary> invoiceSummaries = new ArrayList<>();
        BigDecimal oneHundred = new BigDecimal("100");

        for (AccountCost account : costData.accounts()) {
            BigDecimal awsTotal = account.totalCost();
            BigDecimal discountPercentage = discountService.getDiscount(account.id());
            BigDecimal discountAmount = BigDecimal.ZERO;

            if (discountPercentage.compareTo(BigDecimal.ZERO) > 0 && awsTotal.compareTo(BigDecimal.ZERO) > 0) {
                discountAmount = awsTotal.multiply(discountPercentage).divide(oneHundred, 2, RoundingMode.HALF_UP);
            }

            BigDecimal netPayable = awsTotal.subtract(discountAmount);

            invoiceSummaries.add(new InvoiceSummary(
                    account.id(),
                    account.name(),
                    awsTotal.setScale(2, RoundingMode.HALF_UP),
                    discountPercentage,
                    discountAmount,
                    netPayable.setScale(2, RoundingMode.HALF_UP),
                    null)); // No detailed services in summary view
        }

        invoiceSummaries.sort(Comparator.comparing(InvoiceSummary::netPayable).reversed());
        return invoiceSummaries;
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

        // Get discount from service or default to 0
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

    @Scheduled(fixedRate = 30 * 60 * 1000) // Refresh every 30 minutes
    public void refreshCostDataCache() {
        // Pre-warm the cache for the previous month's data, a common query
        LocalDate today = LocalDate.now();
        YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
        String start = lastMonth.atDay(1).format(formatter);
        String end = lastMonth.atEndOfMonth().plusDays(1).format(formatter); // End date is exclusive

        System.out.println("Pre-warming cache for date range: " + start + " to " + end);
        String cacheKey = start + end + "MONTHLY";
        costCache.put(cacheKey, fetchCostData(start, end, "MONTHLY"));
    }
}