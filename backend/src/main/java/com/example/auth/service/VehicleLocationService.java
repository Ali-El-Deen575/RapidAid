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
        if (vehicle == null || vehicle.getLastLatitude() == null || vehicle.getLastLongitude() == null) {
            return;
        }

        try {
            RoutingService.RouteResult route = routingService.findRouteForVehicle(vehicle, targetLat, targetLng);
            String routeKey = ROUTE_KEY_PREFIX + vehicleId;
            
            redisTemplate.opsForHash().put(routeKey, "totalTime", route.getTimeSeconds());
            redisTemplate.opsForHash().put(routeKey, "totalDistance", route.getDistanceKm());
            redisTemplate.opsForHash().put(routeKey, "currentIndex", "0");
            redisTemplate.opsForHash().put(routeKey, "startTime", LocalDateTime.now().toString());
            redisTemplate.opsForHash().put(routeKey, "pointsSize", String.valueOf(route.getPoints().size()));
            
            // Store route points individually
            for (int i = 0; i < route.getPoints().size(); i++) {
                RoutingService.RoutePoint point = route.getPoints().get(i);
                redisTemplate.opsForHash().put(routeKey, "point_" + i + "_lat", point.getLatitude().toString());
                redisTemplate.opsForHash().put(routeKey, "point_" + i + "_lng", point.getLongitude().toString());
            }
        } catch (Exception e) {
            // Fallback to direct movement if routing fails
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
        String pointsSizeStr = (String) redisTemplate.opsForHash().get(routeKey, "pointsSize");
        
        if (startTimeStr == null || totalTimeStr == null || pointsSizeStr == null) {
            return;
        }

        LocalDateTime startTime = LocalDateTime.parse(startTimeStr);
        double totalTime = Double.parseDouble(totalTimeStr);
        int pointsSize = Integer.parseInt(pointsSizeStr);
        
        long elapsedSeconds = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();
        
        // Calculate progress along route
        double progress = Math.min(1.0, elapsedSeconds / totalTime);
        int targetIndex = (int) (progress * (pointsSize - 1));
        
        if (targetIndex < pointsSize) {
            String latStr = (String) redisTemplate.opsForHash().get(routeKey, "point_" + targetIndex + "_lat");
            String lngStr = (String) redisTemplate.opsForHash().get(routeKey, "point_" + targetIndex + "_lng");
            
            if (latStr != null && lngStr != null) {
                saveLocationToRedis(vehicleId, new BigDecimal(latStr), new BigDecimal(lngStr));
            }
            
            // Check if vehicle reached destination
            if (targetIndex >= pointsSize - 1) {
                redisTemplate.delete(routeKey); // Remove completed route
                handleVehicleArrival(vehicleId);
            }
        }
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