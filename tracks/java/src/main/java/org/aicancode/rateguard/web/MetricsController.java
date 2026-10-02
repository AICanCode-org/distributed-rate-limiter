package org.aicancode.rateguard.web;

import org.aicancode.rateguard.metrics.Metrics;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** v1.1: GET /metrics in Prometheus text format. */
@RestController
public class MetricsController {

    private static final MediaType PROMETHEUS_TEXT = MediaType.parseMediaType("text/plain;version=0.0.4;charset=utf-8");

    private final Metrics metrics;

    public MetricsController(Metrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok().contentType(PROMETHEUS_TEXT).body(metrics.render());
    }
}
