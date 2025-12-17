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
        String key = "vehicle:location:" + vehicleId;
        
        Map<Object, Object> location = redisTemplate.opsForHash().entries(key);
        
        if (location.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(location);
    }

    @GetMapping("/redis/all")
    public ResponseEntity<?> getAllLocationsFromRedis() {
        Set<String> keys = redisTemplate.keys("vehicle:location:*");
        Map<String, Map<Object, Object>> allLocations = new HashMap<>();
        
        for (String key : keys) {
            Map<Object, Object> location = redisTemplate.opsForHash().entries(key);
            allLocations.put(key, location);
        }
        
        return ResponseEntity.ok(allLocations);
    }

    @PostMapping("/sync")
    public ResponseEntity<?> forceSyncToDatabase() {
        vehicleLocationService.syncLocationsToDatabase();
        return ResponseEntity.ok(Map.of("message", "Sync completed"));
    }
}