package com.example.auth.service;

import com.example.auth.dto.VehicleResponse;
import com.example.auth.entity.Assignment;
import com.example.auth.entity.Incident;
import com.example.auth.entity.Vehicle;
import com.example.auth.enums.AssignmentStatus;
import com.example.auth.repository.AssignmentRepository;
import com.example.auth.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class VehicleMovementSimulationService {

    private static final Logger logger = LoggerFactory.getLogger(VehicleMovementSimulationService.class);

    @Value("${vehicle.simulation.enabled:true}")
    private boolean simulationEnabled;

    @Value("${vehicle.simulation.step-distance-km:0.5}")
    private double stepDistanceKm;

    @Value("${vehicle.simulation.arrival-threshold-km:0.1}")
    private double arrivalThresholdKm;

    @Value("${vehicle.simulation.service-time-seconds:30}")
    private int serviceTimeSeconds;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private WebSocketNotificationService webSocketNotificationService;

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private VehicleLocationService vehicleLocationService;

    @Scheduled(fixedDelayString = "${vehicle.simulation.update-interval-ms:2000}")
    @Transactional
    public void updateVehicleLocation() {
        if (!simulationEnabled) return;

        List<Assignment> activeAssignments = assignmentRepository
                .findByAssignmentStatusIn(List.of(
                    AssignmentStatus.ASSIGNED.name(),
                    AssignmentStatus.ENROUTE.name(),
                    AssignmentStatus.ARRIVED.name()
                ));

        if (activeAssignments.isEmpty()) return;

        activeAssignments.forEach(this::processAssignment);
    }

    private void processAssignment(Assignment assignment) {
        Vehicle vehicle = assignment.getVehicle();
        Incident incident = assignment.getIncident();

        if (!isValidAssignment(vehicle, incident)) return;

        BigDecimal targetLat = incident.getAddress().getLatitude();
        BigDecimal targetLng = incident.getAddress().getLongitude();

        if (targetLat == null || targetLng == null) return;

        initializeVehicleLocation(vehicle, targetLat, targetLng);

        double distanceKm = calculateDistanceKm(
            vehicle.getLastLatitude().doubleValue(),
            vehicle.getLastLongitude().doubleValue(),
            targetLat.doubleValue(),
            targetLng.doubleValue()
        );

        if (assignment.getAssignmentStatus() == AssignmentStatus.ARRIVED) {
            handleServiceTime(assignment, vehicle);
        } else if (distanceKm <= arrivalThresholdKm) {
            handleArrival(assignment, vehicle, targetLat, targetLng);
        } else {
            moveVehicle(assignment, vehicle, targetLat, targetLng, distanceKm);
        }
    }

    private boolean isValidAssignment(Vehicle vehicle, Incident incident) {
        return vehicle != null && incident != null && incident.getAddress() != null;
    }

    private void initializeVehicleLocation(Vehicle vehicle, BigDecimal targetLat, BigDecimal targetLng) {
        if (vehicle.getLastLatitude() == null || vehicle.getLastLongitude() == null) {
            vehicle.setLastLatitude(targetLat.subtract(BigDecimal.valueOf(0.05)));
            vehicle.setLastLongitude(targetLng.subtract(BigDecimal.valueOf(0.05)));
            vehicleRepository.save(vehicle);
        }
    }

    private void moveVehicle(Assignment assignment, Vehicle vehicle,
                            BigDecimal targetLat, BigDecimal targetLng, double distanceKm) {
        if (assignment.getAssignmentStatus() == AssignmentStatus.ASSIGNED) {
            assignment.setAssignmentStatus(AssignmentStatus.ENROUTE);
            assignment.setAcceptedAt(LocalDateTime.now());
            assignmentRepository.save(assignment);
            
            // Calculate and store route for realistic movement
            vehicleLocationService.calculateAndStoreRoute(vehicle.getVehicleId(), targetLat, targetLng);
        }
    }

    private void handleArrival(Assignment assignment, Vehicle vehicle,
                              BigDecimal targetLat, BigDecimal targetLng) {
        vehicle.setLastLatitude(targetLat);
        vehicle.setLastLongitude(targetLng);
        vehicle.setLastUpdatedTime(LocalDateTime.now());
        vehicleRepository.save(vehicle);

        assignment.setArrivedAt(LocalDateTime.now());
        assignment.setAssignmentStatus(AssignmentStatus.ARRIVED);
        assignmentRepository.save(assignment);

        webSocketNotificationService.notifyAcceptedIncidentUpdate();
        logger.info("Vehicle {} arrived at incident {}", vehicle.getVehicleId(), assignment.getIncident().getIncidentId());
    }

    private void handleServiceTime(Assignment assignment, Vehicle vehicle) {
        LocalDateTime arrivedAt = assignment.getArrivedAt();
        if (arrivedAt != null && arrivedAt.plusSeconds(serviceTimeSeconds).isBefore(LocalDateTime.now())) {
            completeAssignment(assignment, vehicle);
        }
    }


    private void completeAssignment(Assignment assignment, Vehicle vehicle) {
        assignment.setCompletedAt(LocalDateTime.now());
        assignment.setAssignmentStatus(AssignmentStatus.COMPLETED);
        assignmentRepository.save(assignment);

        List<Assignment> remainingAssignments = assignmentRepository
                .findByVehicleVehicleIdAndAssignmentStatusNot(vehicle.getVehicleId(), "COMPLETED");

        if (remainingAssignments.isEmpty()) {
            vehicle.setAvailable();
            vehicleRepository.save(vehicle);
            logger.info("Vehicle {} completed assignment and is now available", vehicle.getVehicleId());
        }

        incidentService.checkIncidentCompletion(assignment.getIncident());
        List<VehicleResponse> availableVehicles = vehicleService.getAvailableVehicles();
        webSocketNotificationService.notifyAvailableVehicleUpdate(availableVehicles);
    }

    private double calculateDistanceKm(double lat1, double lng1, double lat2, double lng2) {
        final int EARTH_RADIUS_KM = 6371;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
