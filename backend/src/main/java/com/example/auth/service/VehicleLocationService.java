package com.example.auth.service;

import com.example.auth.entity.Vehicle;
import com.example.auth.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

@Service
public class VehicleLocationService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private VehicleRepository vehicleRepository;

    private static final String LOCATION_KEY_PREFIX = "vehicle:location:";

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
}