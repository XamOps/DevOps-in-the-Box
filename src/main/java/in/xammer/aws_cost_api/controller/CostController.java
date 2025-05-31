package in.xammer.aws_cost_api.controller;


import in.xammer.aws_cost_api.dto.CostResponse;
// Make sure this import matches the actual package and class name of AwsCostService
import in.xammer.aws_cost_api.service.AwsCostService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
// Enables Cross-Origin requests from any origin. For production, you might want to restrict it
// e.g., @CrossOrigin(origins = "http://yourdashboard.com")
@CrossOrigin 
public class CostController {
    // Ensure AwsCostService class exists in the specified package
    private final AwsCostService awsCostService;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");


    public CostController(AwsCostService awsCostService) {
        this.awsCostService = awsCostService;
    }

    @GetMapping("/costs")
    public CostResponse getCosts(
        @RequestParam(required = false) String start,
        @RequestParam(required = false) String end,
        @RequestParam(required = false) String granularity
    ) {
        // Default to last month if no parameters are provided
        if (start == null || end == null) {
            LocalDate today = LocalDate.now();
            LocalDate firstDayOfCurrentMonth = today.withDayOfMonth(1);
            LocalDate lastDayOfLastMonth = firstDayOfCurrentMonth.minusDays(1);
            LocalDate firstDayOfLastMonth = lastDayOfLastMonth.withDayOfMonth(1);

            start = firstDayOfLastMonth.format(formatter);
            end = firstDayOfCurrentMonth.format(formatter); // Corrected to be exclusive end date for AWS API
            granularity = (granularity == null) ? "MONTHLY" : granularity;
        } else {
            granularity = (granularity == null) ? "DAILY" : granularity;
        }

        return awsCostService.getCostAndUsage(start, end, granularity);
    }
}