package cl.duoc.pedidos360.bff.service;

import cl.duoc.pedidos360.bff.client.ReportsClient;
import cl.duoc.pedidos360.bff.dto.report.*;
import org.springframework.stereotype.Service;

@Service
public class ReportsService {
    private final ReportsClient client;

    public ReportsService(ReportsClient client) {
        this.client = client;
    }

    public SummaryResponse summary(int hours) {
        if (hours < 1 || hours > 168) throw new IllegalArgumentException("hours debe estar entre 1 y 168");
        return client.summary(hours);
    }

    public LeadTimeResponse leadTime() {
        return client.leadTime();
    }

    public ActivityResponse activity(int minutes) {
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("minutes debe estar entre 1 y 1440");
        return client.activity(minutes);
    }
}
