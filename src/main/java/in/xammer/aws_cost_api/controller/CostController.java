package in.xammer.aws_cost_api.controller;

import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.service.AwsCostService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CostController {

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
        // If any parameter is missing (e.g., initial page load), default to the last full month.
        if (start == null || end == null || granularity == null) {
            LocalDate today = LocalDate.now();
            YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
            
            start = lastMonth.atDay(1).format(formatter);
            // The AWS API end date is exclusive, so we use the first day of the current month
            // to get all data for the previous month.
            end = lastMonth.atEndOfMonth().plusDays(1).format(formatter);
            granularity = "MONTHLY";
        }

        return awsCostService.getCostAndUsage(start, end, granularity);
    }
}