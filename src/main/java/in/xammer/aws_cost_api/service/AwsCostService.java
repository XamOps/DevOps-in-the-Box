package in.xammer.aws_cost_api.service;

import in.xammer.aws_cost_api.dto.AccountCost;
import in.xammer.aws_cost_api.dto.AwsInvoice;
import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.dto.InvoiceResponse;
import in.xammer.aws_cost_api.dto.InvoiceSummary;
import in.xammer.aws_cost_api.dto.ServiceCost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;
import software.amazon.awssdk.services.organizations.model.OrganizationsException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy");

    private static final Map<String, String> REGION_CODES = Map.ofEntries(
        Map.entry("USE1", "US East (N. Virginia)"),
        Map.entry("USE2", "US East (Ohio)"),
        Map.entry("USW1", "US West (N. California)"),
        Map.entry("USW2", "US West (Oregon)"),
        Map.entry("APS1", "Asia Pacific (Singapore)"),
        Map.entry("APS2", "Asia Pacific (Sydney)"),
        Map.entry("APS3", "Asia Pacific (Mumbai)"),
        Map.entry("APN1", "Asia Pacific (Tokyo)"),
        Map.entry("APN2", "Asia Pacific (Seoul)"),
        Map.entry("APN3", "Asia Pacific (Osaka)"),
        Map.entry("EUC1", "EU (Frankfurt)"),
        Map.entry("EUW1", "EU (Ireland)"),
        Map.entry("EUW2", "EU (London)"),
        Map.entry("EUW3", "EU (Paris)"),
        Map.entry("EUN1", "EU (Stockholm)"),
        Map.entry("SAE1", "South America (Sao Paulo)"),
        Map.entry("CAC1", "Canada (Central)"),
        Map.entry("MES1", "Middle East (Bahrain)"),
        Map.entry("AFS1", "Africa (Cape Town)"),
        Map.entry("GLOBAL", "Global")
    );


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

        CompletableFuture<Map<String, String>> accountsFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return organizationsClient.listAccountsPaginator()
                        .stream()
                        .flatMap(response -> response.accounts().stream())
                        .collect(Collectors.toMap(Account::id, Account::name));
            } catch (OrganizationsException e) {
                System.err.println("Could not fetch account names from AWS Organizations. This might be a permissions issue. Proceeding with account IDs only. Error: " + e.getMessage());
                return new HashMap<>();
            }
        });

        CompletableFuture<List<Group>> costDataFuture = CompletableFuture.supplyAsync(() -> {
            List<Group> allGroups = new ArrayList<>();
            String nextToken = null;
            do {
                GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                        .timePeriod(tp -> tp.start(startDate).end(endDate))
                        .granularity(granularity)
                        .metrics("NetUnblendedCost")
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
    
    @Cacheable(value = "detailedAwsInvoice", key = "{#startDate, #endDate, #accountId}")
    public AwsInvoice getAwsInvoiceForAccount(String startDate, String endDate, String accountId) {
        System.out.println("CACHE MISS: Fetching detailed invoice data for account " + accountId);
        
        Expression filter = Expression.builder()
            .dimensions(d -> d.key("LINKED_ACCOUNT").values(accountId))
            .build();

        List<Group> allGroups = new ArrayList<>();
        String nextToken = null;
        do {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(tp -> tp.start(startDate).end(endDate))
                .granularity("MONTHLY")
                .metrics("NetUnblendedCost", "UsageQuantity")
                .groupBy(
                    GroupDefinition.builder().type("DIMENSION").key("SERVICE").build(),
                    GroupDefinition.builder().type("DIMENSION").key("USAGE_TYPE").build()
                )
                .filter(filter)
                .nextPageToken(nextToken)
                .build();
            
            GetCostAndUsageResponse response = costExplorerClient.getCostAndUsage(request);
            if (response.resultsByTime() != null && !response.resultsByTime().isEmpty()) {
                allGroups.addAll(response.resultsByTime().get(0).groups());
            }
            nextToken = response.nextPageToken();
        } while (nextToken != null);

        if (allGroups.isEmpty()) {
            return null;
        }

        Map<String, List<AwsInvoice.ServiceDetail>> detailsByService = new HashMap<>();
        Map<String, BigDecimal> totalsByService = new HashMap<>();
        Map<String, BigDecimal> totalsByRegion = new HashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (Group group : allGroups) {
            MetricValue costMetric = group.metrics().get("NetUnblendedCost");
            BigDecimal amount = new BigDecimal(costMetric.amount());

            if (amount.compareTo(BigDecimal.ZERO) == 0) continue;
            
            grandTotal = grandTotal.add(amount);

            String serviceName = group.keys().get(0);
            String usageType = group.keys().get(1);
            
            MetricValue usageMetric = group.metrics().get("UsageQuantity");
            String usageQuantityStr = new BigDecimal(usageMetric.amount()).setScale(3, RoundingMode.HALF_UP).toPlainString() + " " + usageMetric.unit();

            String regionName = "Global";
            String regionCode = "GLOBAL";
            if (usageType.contains("-")) {
                String potentialRegionCode = usageType.substring(0, usageType.indexOf('-')).toUpperCase();
                if (REGION_CODES.containsKey(potentialRegionCode)) {
                    regionCode = potentialRegionCode;
                }
            }
            regionName = REGION_CODES.getOrDefault(regionCode, "Global");

            AwsInvoice.ServiceDetail detail = new AwsInvoice.ServiceDetail(usageType, usageQuantityStr, regionName, amount);
            
            detailsByService.computeIfAbsent(serviceName, k -> new ArrayList<>()).add(detail);
            totalsByService.merge(serviceName, amount, BigDecimal::add);
            if (!regionName.equals("Global")) {
                totalsByRegion.merge(regionName, amount, BigDecimal::add);
            }
        }

        List<AwsInvoice.ServiceCharge> serviceCharges = new ArrayList<>();
        for (String serviceName : totalsByService.keySet()) {
            List<AwsInvoice.ServiceDetail> details = detailsByService.get(serviceName);
            details.sort(Comparator.comparing(AwsInvoice.ServiceDetail::amount).reversed());
            
            serviceCharges.add(new AwsInvoice.ServiceCharge(
                serviceName,
                totalsByService.get(serviceName),
                details
            ));
        }
        serviceCharges.sort(Comparator.comparing(AwsInvoice.ServiceCharge::totalAmount).reversed());
        
        Map.Entry<String, BigDecimal> highestService = totalsByService.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        Map.Entry<String, BigDecimal> highestRegion = totalsByRegion.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);

        LocalDate start = LocalDate.parse(startDate);
        String billingPeriod = start.format(DateTimeFormatter.ofPattern("MMMM d")) + " - " + start.withDayOfMonth(start.lengthOfMonth()).format(DateTimeFormatter.ofPattern("d, yyyy"));

        return new AwsInvoice(
            "Amazon Web Services India Private Limited",
            grandTotal,
            accountId,
            highestService != null ? highestService.getKey() : "N/A",
            highestService != null ? highestService.getValue() : BigDecimal.ZERO,
            "N/A", 
            highestRegion != null ? highestRegion.getKey() : "N/A",
            highestRegion != null ? highestRegion.getValue() : BigDecimal.ZERO,
            "N/A", 
            billingPeriod,
            LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")),
            serviceCharges,
            grandTotal,
            BigDecimal.ZERO,
            grandTotal
        );
    }
    
    public void updateDiscount(String accountId, BigDecimal discount) {
        discountService.updateDiscount(accountId, discount);
    }
}
