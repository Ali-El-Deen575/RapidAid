package com.example.auth.service;

import com.example.auth.entity.Assignment;
import com.example.auth.entity.Vehicle;
import com.example.auth.repository.AssignmentRepository;
import com.example.auth.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class VehicleLocationService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RoutingService routingService;

    private static final String LOCATION_KEY_PREFIX = "vehicle:location:";
    private static final String ROUTE_KEY_PREFIX = "vehicle:route:";

    public void saveLocationToRedis(Integer vehicleId, BigDecimal latitude, BigDecimal longitude) {
        String key = LOCATION_KEY_PREFIX + vehicleId;
        redisTemplate.opsForHash().put(key, "latitude", latitude.toString());
        redisTemplate.opsForHash().put(key, "longitude", longitude.toString());
        redisTemplate.opsForHash().put(key, "timestamp", LocalDateTime.now().toString());
    }

    @Scheduled(fixedRate = 30000) 
    public void syncLocationsToDatabase() {
        Set<String> keys = redisTemplate.keys(LOCATION_KEY_PREFIX + "*");
        
        for (String key : keys) {
            try {
                Integer vehicleId = Integer.valueOf(key.replace(LOCATION_KEY_PREFIX, ""));
                String latStr = (String) redisTemplate.opsForHash().get(key, "latitude");
                String lngStr = (String) redisTemplate.opsForHash().get(key, "longitude");
                
                if (latStr != null && lngStr != null) {
                    Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
                    if (vehicle != null) {
                        vehicle.setLastLatitude(new BigDecimal(latStr));
                        vehicle.setLastLongitude(new BigDecimal(lngStr));
                        vehicle.setLastUpdatedTime(LocalDateTime.now());
                        vehicleRepository.save(vehicle);
                    }
                }
            } catch (Exception e) {
            }
        }
    }

    public void calculateAndStoreRoute(Integer vehicleId, BigDecimal targetLat, BigDecimal targetLng) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
        if (vehicle == null) {
            System.out.println("Vehicle not found: " + vehicleId);
            return;
        }
        
        // Initialize vehicle location if not set
        if (vehicle.getLastLatitude() == null || vehicle.getLastLongitude() == null) {
            vehicle.setLastLatitude(new BigDecimal("30.0626"));
            vehicle.setLastLongitude(new BigDecimal("31.2497"));
            vehicleRepository.save(vehicle);
            System.out.println("Initialized vehicle " + vehicleId + " location");
        }

        try {
            RoutingService.RouteResult route = routingService.findRouteForVehicle(vehicle, targetLat, targetLng);
            String routeKey = ROUTE_KEY_PREFIX + vehicleId;
            
            // Store simple route data
            redisTemplate.opsForHash().put(routeKey, "totalTime", String.valueOf(route.getTimeSeconds()));
            redisTemplate.opsForHash().put(routeKey, "startTime", LocalDateTime.now().toString());
            redisTemplate.opsForHash().put(routeKey, "fromLat", vehicle.getLastLatitude().toString());
            redisTemplate.opsForHash().put(routeKey, "fromLng", vehicle.getLastLongitude().toString());
            redisTemplate.opsForHash().put(routeKey, "toLat", targetLat.toString());
            redisTemplate.opsForHash().put(routeKey, "toLng", targetLng.toString());
            
            System.out.println("Route stored for vehicle " + vehicleId + ", time: " + route.getTimeSeconds() + "s");
        } catch (Exception e) {
            System.out.println("Route calculation failed: " + e.getMessage());
        }
    }

    @Scheduled(fixedRate = 5000) // Update every 5 seconds
    public void updateVehiclePositionsAlongRoute() {
        Set<String> routeKeys = redisTemplate.keys(ROUTE_KEY_PREFIX + "*");
        
        for (String routeKey : routeKeys) {
            try {
                updateVehicleAlongRoute(routeKey);
            } catch (Exception e) {
                // Continue with other vehicles if one fails
            }
        }
    }

    private void updateVehicleAlongRoute(String routeKey) {
        Integer vehicleId = Integer.valueOf(routeKey.replace(ROUTE_KEY_PREFIX, ""));
        
        String startTimeStr = (String) redisTemplate.opsForHash().get(routeKey, "startTime");
        String totalTimeStr = (String) redisTemplate.opsForHash().get(routeKey, "totalTime");
        String fromLatStr = (String) redisTemplate.opsForHash().get(routeKey, "fromLat");
        String fromLngStr = (String) redisTemplate.opsForHash().get(routeKey, "fromLng");
        String toLatStr = (String) redisTemplate.opsForHash().get(routeKey, "toLat");
        String toLngStr = (String) redisTemplate.opsForHash().get(routeKey, "toLng");
        
        if (startTimeStr == null || totalTimeStr == null) {
            return;
        }

        LocalDateTime startTime = LocalDateTime.parse(startTimeStr);
        double totalTime = Double.parseDouble(totalTimeStr);
        
        long elapsedSeconds = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();
        
        // Calculate progress (0.0 to 1.0)
        double progress = Math.min(1.0, elapsedSeconds / totalTime);
        
        // Linear interpolation between start and end points
        double fromLat = Double.parseDouble(fromLatStr);
        double fromLng = Double.parseDouble(fromLngStr);
        double toLat = Double.parseDouble(toLatStr);
        double toLng = Double.parseDouble(toLngStr);
        
        double currentLat = fromLat + (toLat - fromLat) * progress;
        double currentLng = fromLng + (toLng - fromLng) * progress;
        
        saveLocationToRedis(vehicleId, BigDecimal.valueOf(currentLat), BigDecimal.valueOf(currentLng));
        
        // Check if arrived (progress >= 1.0)
        if (progress >= 1.0) {
            redisTemplate.delete(routeKey);
            handleVehicleArrival(vehicleId);
        }
    }

    public boolean hasVehicleReachedDestination(Integer vehicleId) {
        String routeKey = ROUTE_KEY_PREFIX + vehicleId;
        return !redisTemplate.hasKey(routeKey);
    }

    private void handleVehicleArrival(Integer vehicleId) {
        List<Assignment> activeAssignments = assignmentRepository
            .findByVehicleVehicleIdAndAssignmentStatusNot(vehicleId, "COMPLETED");
        
        for (Assignment assignment : activeAssignments) {
            assignment.setArrivedAt(LocalDateTime.now());
            assignment.setAssignmentStatus(com.example.auth.enums.AssignmentStatus.ARRIVED);
            assignmentRepository.save(assignment);
        }
    }
}