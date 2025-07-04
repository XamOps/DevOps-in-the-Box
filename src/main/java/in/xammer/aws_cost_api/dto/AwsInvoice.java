package in.xammer.aws_cost_api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Represents the data structure for the detailed AWS-style invoice.
 * This record holds all the information necessary to populate the PDF and UI,
 * mirroring the detailed sample provided.
 */
public record AwsInvoice(
    String serviceProvider,
    BigDecimal totalInUSD,
    String payerAccountId,
    String highestServiceSpendName,
    BigDecimal highestServiceSpendAmount,
    String trendComparedToPriorMonth,
    String highestRegionSpendName,
    BigDecimal highestRegionSpendAmount,
    String regionTrendComparedToPriorMonth,
    String billingPeriod,
    String datePrinted,
    List<ServiceCharge> chargesByService,
    BigDecimal totalPreTax,
    BigDecimal totalTax,
    BigDecimal totalInvoicedCharges
) {
    /**
     * Represents a charge for a specific top-level service (e.g., "Elastic Compute Cloud").
     */
    public static record ServiceCharge(
        String serviceName,
        BigDecimal totalAmount,
        List<ServiceDetail> serviceDetails
    ) {}

    /**
     * Represents a single, granular line item within a service charge, detailing usage, region, and cost.
     */
    public static record ServiceDetail(
        String description, // e.g., "$0.0124 per On Demand Linux t2.micro Instance Hour"
        String usageQuantity, // e.g., "5.551 Hrs"
        String region, // e.g., "Asia Pacific (Mumbai)"
        BigDecimal amount
    ) {}
}
