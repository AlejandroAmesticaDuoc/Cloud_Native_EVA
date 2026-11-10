package cl.duoc.pedidos360.report.controller;

import cl.duoc.pedidos360.report.dto.ActivityResponse;
import cl.duoc.pedidos360.report.dto.LeadTimeResponse;
import cl.duoc.pedidos360.report.dto.SummaryResponse;
import cl.duoc.pedidos360.report.service.ActivityMetricsService;
import cl.duoc.pedidos360.report.service.ReportsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportsController {
    private final ReportsService reports;
    private final ActivityMetricsService activity;

    public ReportsController(ReportsService reports, ActivityMetricsService activity) {
        this.reports = reports;
        this.activity = activity;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(@RequestParam(defaultValue = "24") @Min(1) @Max(168) int hours) {
        return reports.summary(hours);
    }

    @GetMapping("/lead-time")
    public LeadTimeResponse leadTime() {
        return reports.leadTime();
    }

    /** Actividad en tiempo real (desde audit.timeline) de los últimos {@code minutes} minutos. */
    @GetMapping("/activity")
    public ActivityResponse activity(@RequestParam(defaultValue = "60") @Min(1) @Max(1440) int minutes) {
        return activity.activity(minutes);
    }
}
