package cl.duoc.pedidos360.report.controller;

import cl.duoc.pedidos360.report.dto.LeadTimeResponse;
import cl.duoc.pedidos360.report.dto.SummaryResponse;
import cl.duoc.pedidos360.report.service.ReportsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportsController {
    private final ReportsService reports;

    public ReportsController(ReportsService reports) {
        this.reports = reports;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(@RequestParam(defaultValue = "24") @Min(1) @Max(168) int hours) {
        return reports.summary(hours);
    }

    @GetMapping("/lead-time")
    public LeadTimeResponse leadTime() {
        return reports.leadTime();
    }
}
