package com.example.auth.controller.rest;

import com.example.auth.service.RoutingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/test/routing")
public class RoutingTestController {

    @Autowired
    private RoutingService routingService;

    @GetMapping("/route")
    public ResponseEntity<?> getRoute(
            @RequestParam BigDecimal fromLat,
            @RequestParam BigDecimal fromLng,
            @RequestParam BigDecimal toLat,
            @RequestParam BigDecimal toLng) {
        
        try {
            RoutingService.RouteResult route = routingService.findRoute(fromLat, fromLng, toLat, toLng);
            return ResponseEntity.ok(route);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Routing error: " + e.getMessage());
        }
    }
}