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
public class VehicleAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(VehicleAssignmentService.class);

    @Value("${vehicle.assignment.enabled:true}")
    private boolean assignmentEnabled;

    @Value("${vehicle.assignment.service-time-seconds:30}")
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

    @Scheduled(fixedDelayString = "${vehicle.assignment.update-interval-ms:5000}")
    @Transactional
    public void processVehicleAssignments() {
        if (!assignmentEnabled) return;

        List<Assignment> activeAssignments = assignmentRepository
                .findByAssignmentStatusIn(List.of(
                    AssignmentStatus.ASSIGNED.name(),
                    AssignmentStatus.ENROUTE.name(),
                    AssignmentStatus.ARRIVED.name()
                ));

        activeAssignments.forEach(this::processAssignment);
    }

    private void processAssignment(Assignment assignment) {
        Vehicle vehicle = assignment.getVehicle();
        Incident incident = assignment.getIncident();

        if (!isValidAssignment(vehicle, incident)) return;

        BigDecimal targetLat = incident.getAddress().getLatitude();
        BigDecimal targetLng = incident.getAddress().getLongitude();

        if (targetLat == null || targetLng == null) return;

        switch (assignment.getAssignmentStatus()) {
            case ASSIGNED:
                startRoute(assignment, vehicle, targetLat, targetLng);
                break;
            case ENROUTE:
                checkArrival(assignment, vehicle, targetLat, targetLng);
                break;
            case ARRIVED:
                handleServiceTime(assignment, vehicle);
                break;
        }
    }

    private boolean isValidAssignment(Vehicle vehicle, Incident incident) {
        return vehicle != null && incident != null && incident.getAddress() != null;
    }

    private void startRoute(Assignment assignment, Vehicle vehicle, BigDecimal targetLat, BigDecimal targetLng) {
        // Set vehicle as en route
        assignment.setAssignmentStatus(AssignmentStatus.ENROUTE);
        assignment.setAcceptedAt(LocalDateTime.now());
        assignmentRepository.save(assignment);

        // Calculate and start route
        vehicleLocationService.calculateAndStoreRoute(vehicle.getVehicleId(), targetLat, targetLng);
        
        logger.info("Vehicle {} started route to incident {}", vehicle.getVehicleId(), assignment.getIncident().getIncidentId());
    }

    private void checkArrival(Assignment assignment, Vehicle vehicle, BigDecimal targetLat, BigDecimal targetLng) {
        // Check if vehicle has reached destination via VehicleLocationService
        if (vehicleLocationService.hasVehicleReachedDestination(vehicle.getVehicleId())) {
            handleArrival(assignment, vehicle, targetLat, targetLng);
        }
    }

    private void handleArrival(Assignment assignment, Vehicle vehicle, BigDecimal targetLat, BigDecimal targetLng) {
        // Update vehicle location to exact target
        vehicleLocationService.saveLocationToRedis(vehicle.getVehicleId(), targetLat, targetLng);

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

        // Check if vehicle has other assignments
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
}