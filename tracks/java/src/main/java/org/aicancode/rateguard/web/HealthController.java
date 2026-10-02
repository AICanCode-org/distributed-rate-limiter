package org.aicancode.rateguard.web;

import java.util.Map;
import org.aicancode.rateguard.store.Store;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness + Redis health. v1.1: always 200 while the process runs; DEGRADED when Redis is down. */
@RestController
public class HealthController {

    private final Store store;

    public HealthController(Store store) {
        this.store = store;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean up = store.ping();
        return ResponseEntity.ok(Json.health(up));
    }
}
