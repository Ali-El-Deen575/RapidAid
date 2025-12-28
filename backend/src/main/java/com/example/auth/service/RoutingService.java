package com.example.auth.service;

import com.example.auth.entity.Vehicle;
import com.example.auth.enums.VehicleType;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.PointList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class RoutingService {

    @Autowired
    private GraphHopper graphHopper;

    public RouteResult findOptimalRoute(BigDecimal fromLat, BigDecimal fromLng, 
                                       BigDecimal toLat, BigDecimal toLng, 
                                       VehicleType vehicleType) {
        String profile = getProfileForVehicleType(vehicleType);
        return calculateRoute(fromLat, fromLng, toLat, toLng, profile);
    }

    public RouteResult findRoute(BigDecimal fromLat, BigDecimal fromLng, 
                                BigDecimal toLat, BigDecimal toLng) {
        return calculateRoute(fromLat, fromLng, toLat, toLng, "emergency");
    }

    public RouteResult findRouteForVehicle(Vehicle vehicle, BigDecimal toLat, BigDecimal toLng) {
        if (vehicle.getLastLatitude() == null || vehicle.getLastLongitude() == null) {
            throw new RuntimeException("Vehicle location not available");
        }
        
        String profile = getProfileForVehicleType(vehicle.getVehicleType());
        return calculateRoute(vehicle.getLastLatitude(), vehicle.getLastLongitude(), 
                            toLat, toLng, profile);
    }

    private RouteResult calculateRoute(BigDecimal fromLat, BigDecimal fromLng, 
                                     BigDecimal toLat, BigDecimal toLng, String profile) {
        GHRequest request = new GHRequest(
            fromLat.doubleValue(), fromLng.doubleValue(),
            toLat.doubleValue(), toLng.doubleValue()
        );
        request.setProfile(profile);
        
        GHResponse response = graphHopper.route(request);
        
        if (response.hasErrors()) {
            throw new RuntimeException("Routing error: " + response.getErrors());
        }
        
        ResponsePath path = response.getBest();
        PointList pointList = path.getPoints();
        
        List<RoutePoint> routePoints = new ArrayList<>();
        for (int i = 0; i < pointList.size(); i++) {
            routePoints.add(new RoutePoint(
                BigDecimal.valueOf(pointList.getLat(i)),
                BigDecimal.valueOf(pointList.getLon(i))
            ));
        }
        
        return new RouteResult(
            routePoints,
            path.getDistance() / 1000.0, // Convert to km
            path.getTime() / 1000.0      // Convert to seconds
        );
    }

    private String getProfileForVehicleType(VehicleType vehicleType) {
        return "emergency";
    }

    public static class RouteResult {
        private final List<RoutePoint> points;
        private final double distanceKm;
        private final double timeSeconds;

        public RouteResult(List<RoutePoint> points, double distanceKm, double timeSeconds) {
            this.points = points;
            this.distanceKm = distanceKm;
            this.timeSeconds = timeSeconds;
        }

        public List<RoutePoint> getPoints() { return points; }
        public double getDistanceKm() { return distanceKm; }
        public double getTimeSeconds() { return timeSeconds; }
        public double getTimeMinutes() { return timeSeconds / 60.0; }
    }

    public static class RoutePoint {
        private final BigDecimal latitude;
        private final BigDecimal longitude;

        public RoutePoint(BigDecimal latitude, BigDecimal longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public BigDecimal getLatitude() { return latitude; }
        public BigDecimal getLongitude() { return longitude; }
    }
}