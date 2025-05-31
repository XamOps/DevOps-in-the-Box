package in.xammer.aws_cost_api.service;
import in.xammer.aws_cost_api.dto.AccountCost;
import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.dto.ServiceCost;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        System.out.printf("Cache miss. Fetching from AWS for %s to %s%n", startDate, endDate);

        // 1. Fetch Account Names
        Map<String, String> accountMap = organizationsClient.listAccounts().accounts()
            .stream()
            .collect(Collectors.toMap(Account::id, Account::name));

        // 2. Fetch Cost Data with Pagination
        List<Group> allGroups = new ArrayList<>();
        String nextToken = null;
        do {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(tp -> tp.start(startDate).end(endDate))
                .granularity(granularity)
                .metrics("UnblendedCost")
                .groupBy(
                    List.of(
                        software.amazon.awssdk.services.costexplorer.model.GroupDefinition.builder()
                            .type("DIMENSION").key("LINKED_ACCOUNT").build(),
                        software.amazon.awssdk.services.costexplorer.model.GroupDefinition.builder()
                            .type("DIMENSION").key("SERVICE").build()
                    )
                )
                .nextPageToken(nextToken)
                .build();

            GetCostAndUsageResponse response = costExplorerClient.getCostAndUsage(request);
            response.resultsByTime().forEach(rbt -> allGroups.addAll(rbt.groups()));
            nextToken = response.nextPageToken();
        } while (nextToken != null);

        // 3. Process the data
        Map<String, Map<String, BigDecimal>> costData = new HashMap<>(); // AccountID -> {Service -> Cost}
        allGroups.forEach(group -> {
            String accountId = group.keys().get(0);
            String serviceName = group.keys().get(1);
            BigDecimal amount = new BigDecimal(group.metrics().get("UnblendedCost").amount());

            costData.computeIfAbsent(accountId, k -> new HashMap<>())
                    .merge(serviceName, amount, BigDecimal::add);
        });

        // 4. Structure the response
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
                accountMap.getOrDefault(accountId, "N/A"),
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