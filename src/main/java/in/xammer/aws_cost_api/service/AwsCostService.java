package in.xammer.aws_cost_api.service;

import in.xammer.aws_cost_api.dto.AccountCost;
import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.dto.ServiceCost;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*; // Import all from model
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AwsCostService {

    private final CostExplorerClient costExplorerClient;
    private final OrganizationsClient organizationsClient;

    public AwsCostService(CostExplorerClient costExplorerClient, OrganizationsClient organizationsClient) {
        this.costExplorerClient = costExplorerClient;
        this.organizationsClient = organizationsClient;
    }

    @Cacheable(value = "awsCosts", key = "{#startDate, #endDate, #granularity}")
    public CostResponse getCostAndUsage(String startDate, String endDate, String granularity) {
        System.out.printf("Cache miss. Fetching from AWS for %s to %s with %s granularity%n", startDate, endDate, granularity);

        Map<String, String> accountMap = new HashMap<>();
        try {
            accountMap = organizationsClient.listAccountsPaginator().stream()
                    .flatMap(page -> page.accounts().stream())
                    .collect(Collectors.toMap(software.amazon.awssdk.services.organizations.model.Account::id, software.amazon.awssdk.services.organizations.model.Account::name));
        } catch (Exception e) {
            System.err.println("Error fetching account names from Organizations: " + e.getMessage());
            // Continue without account names if this fails
        }


        List<Group> allGroups = new ArrayList<>();
        String nextToken = null;
        GetCostAndUsageResponse response;

        do {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(tp -> tp.start(startDate).end(endDate))
                .granularity(granularity)
                .metrics("UnblendedCost")
    .groupBy(
        GroupDefinition.builder().type("DIMENSION").key("LINKED_ACCOUNT").build(),
        GroupDefinition.builder().type("DIMENSION").key("SERVICE").build()
    )
    .build();
try {
    System.out.println("Requesting Cost Explorer with: " + request.toString());
                response = costExplorerClient.getCostAndUsage(request);
            } catch (CostExplorerException ce) {
                System.err.printf("AWS Cost Explorer SDK Exception for query (%s to %s, %s): %s. AWS Request ID: %s%n",
                        startDate, endDate, granularity, ce.getMessage(), ce.requestId());
                throw new RuntimeException("Failed to retrieve data from AWS Cost Explorer: " + ce.getMessage(), ce);
            } catch (Exception e) {
                System.err.printf("Generic Exception during Cost Explorer call for query (%s to %s, %s): %s%n",
                        startDate, endDate, granularity, e.getMessage());
                throw new RuntimeException("Failed to retrieve data from AWS Cost Explorer due to an unexpected error.", e);
            }
            
            if (response.resultsByTime() != null) {
                response.resultsByTime().forEach(rbt -> {
                    if (rbt.groups() != null) {
                        allGroups.addAll(rbt.groups());
                    }
                });
            }
            nextToken = response.nextPageToken();
        } while (nextToken != null);

        Map<String, Map<String, BigDecimal>> costData = new HashMap<>();

        allGroups.forEach(group -> {
            // Defensive checks for group keys and metrics
            if (group.keys() == null || group.keys().size() < 2 || group.metrics() == null) {
                System.out.println("Skipping malformed group: " + group.toString());
                return;
            }

            String accountId = group.keys().get(0);
            String serviceName = group.keys().get(1);
            MetricValue unblendedCostMetric = group.metrics().get("UnblendedCost");

            if (unblendedCostMetric != null && unblendedCostMetric.amount() != null) {
                BigDecimal amount = new BigDecimal(unblendedCostMetric.amount());
                costData.computeIfAbsent(accountId, k -> new HashMap<>())
                        .merge(serviceName, amount, BigDecimal::add);
            } else {
                System.out.printf("Warning: UnblendedCost metric missing or null for account %s, service %s in group %s%n", accountId, serviceName, group.toString());
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
                .map(e -> new ServiceCost(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(ServiceCost::cost).reversed())
                .toList();

            accountCosts.add(new AccountCost(
                accountId,
                accountMap.getOrDefault(accountId, "Account " + accountId), // Use accountId if name not found
                accountTotal.setScale(2, RoundingMode.HALF_UP),
                serviceCosts
            ));
        }

        accountCosts.sort(Comparator.comparing(AccountCost::totalCost).reversed());

        return new CostResponse(
            grandTotal.setScale(2, RoundingMode.HALF_UP),
            accountCosts,
            Map.of("start", startDate, "end", endDate, "granularity", granularity)
        );
    }
}