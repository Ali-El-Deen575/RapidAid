package com.example.auth.controller.rest;

import com.example.auth.service.VehicleLocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/test/vehicle-location")
public class VehicleLocationTestController {

    @Autowired
    private VehicleLocationService vehicleLocationService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/update/{vehicleId}")
    public ResponseEntity<?> updateLocation(
            @PathVariable Integer vehicleId,
            @RequestParam BigDecimal latitude,
            @RequestParam BigDecimal longitude) {
        
        vehicleLocationService.saveLocationToRedis(vehicleId, latitude, longitude);
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Location saved to Redis");
        response.put("vehicleId", vehicleId);
        response.put("latitude", latitude);
        response.put("longitude", longitude);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/redis/{vehicleId}")
    public ResponseEntity<?> getLocationFromRedis(@PathVariable Integer vehicleId) {
        String locationKey = "vehicle:location:" + vehicleId;
        String routeKey = "vehicle:route:" + vehicleId;
        
        Map<Object, Object> location = redisTemplate.opsForHash().entries(locationKey);
        Map<Object, Object> route = redisTemplate.opsForHash().entries(routeKey);
        
        Map<String, Object> response = new HashMap<>();
        
        if (!location.isEmpty()) {
            response.put("location", location);
        }
        
        if (!route.isEmpty()) {
            response.put("route", route);
        }
        
        if (response.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/redis/all")
    public ResponseEntity<?> getAllLocationsFromRedis() {
        Set<String> locationKeys = redisTemplate.keys("vehicle:location:*");
        Set<String> routeKeys = redisTemplate.keys("vehicle:route:*");
        
        Map<String, Object> allData = new HashMap<>();
        
        // Get all locations
        for (String key : locationKeys) {
            Map<Object, Object> location = redisTemplate.opsForHash().entries(key);
            allData.put(key, location);
        }
        
        // Get all routes
        for (String key : routeKeys) {
            Map<Object, Object> route = redisTemplate.opsForHash().entries(key);
            allData.put(key, route);
        }
        
        return ResponseEntity.ok(allData);
    }

    @PostMapping("/start-assignment/{vehicleId}")
    public ResponseEntity<?> startAssignment(@PathVariable Integer vehicleId) {
        vehicleLocationService.saveLocationToRedis(vehicleId, new BigDecimal("30.0626"), new BigDecimal("31.2497"));
        vehicleLocationService.calculateAndStoreRoute(vehicleId, new BigDecimal("30.0444"), new BigDecimal("31.2357"));
        return ResponseEntity.ok(Map.of("message", "Assignment started for vehicle " + vehicleId));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> forceSyncToDatabase() {
        vehicleLocationService.syncLocationsToDatabase();
        return ResponseEntity.ok(Map.of("message", "Sync completed"));
    }
}