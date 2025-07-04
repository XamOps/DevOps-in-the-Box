package in.xammer.aws_cost_api.controller;

import in.xammer.aws_cost_api.dto.CostResponse;
import in.xammer.aws_cost_api.service.AwsCostService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/costs")
@CrossOrigin
public class CostController {

    private final AwsCostService awsCostService;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public CostController(AwsCostService awsCostService) {
        this.awsCostService = awsCostService;
    }

    @GetMapping
    public CostResponse getCosts(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String granularity) {
        if (start == null || end == null || granularity == null) {
            LocalDate today = LocalDate.now();
            YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
            start = lastMonth.atDay(1).format(formatter);
            end = lastMonth.atEndOfMonth().plusDays(1).format(formatter);
            granularity = "MONTHLY";
        }
        return awsCostService.getCostAndUsage(start, end, granularity);
    }
}
