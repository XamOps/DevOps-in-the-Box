package in.xammer.aws_cost_api.controller;

import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.service.AwsCostService;
// Ensure this import matches the actual package and class name of GeminiService
import in.xammer.aws_cost_api.service.GeminiService;
import in.xammer.aws_cost_api.dto.AccountCost; // Import AccountCost
import org.springframework.http.ResponseEntity; // For better response handling
import org.springframework.web.bind.annotation.*; // For PathVariable

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CostController {

    private final AwsCostService awsCostService;
    private final GeminiService geminiService; // Inject GeminiService
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public CostController(AwsCostService awsCostService, GeminiService geminiService) {
        this.awsCostService = awsCostService;
        this.geminiService = geminiService; // Initialize GeminiService
    }

    @GetMapping("/costs")
    public CostResponse getCosts(
        @RequestParam(required = false) String start,
        @RequestParam(required = false) String end,
        @RequestParam(required = false) String granularity
    ) {
        if (start == null || end == null || granularity == null) {
            LocalDate today = LocalDate.now();
            YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
            start = lastMonth.atDay(1).format(formatter);
            end = lastMonth.atEndOfMonth().plusDays(1).format(formatter);
            granularity = "MONTHLY";
        }
        return awsCostService.getCostAndUsage(start, end, granularity);
    }

    // New Endpoint for Optimization Suggestions
    @PostMapping("/costs/{accountId}/optimize-suggestions")
    public ResponseEntity<String> getOptimizationSuggestions(
            @PathVariable String accountId,
            @RequestBody AccountCost accountCost // Receive account details in request body
    ) {
        try {
            String suggestions = geminiService.getOptimizationSuggestions(
                accountCost.name(), // Use name from the provided AccountCost DTO
                accountId,
                accountCost.services() // Use services from the provided AccountCost DTO
            );
            return ResponseEntity.ok(suggestions);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error generating optimization suggestions: " + e.getMessage());
        }
    }
}