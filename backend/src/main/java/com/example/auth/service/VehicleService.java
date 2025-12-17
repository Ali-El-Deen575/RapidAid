package com.example.auth.service;

import com.example.auth.dto.VehicleRequest;
import com.example.auth.dto.VehicleResponse;
import com.example.auth.entity.Assignment;
import com.example.auth.entity.Incident;
import com.example.auth.entity.Vehicle;
import com.example.auth.enums.AssignmentStatus;
import com.example.auth.enums.VehicleStatus;
import com.example.auth.mapper.VehicleMapper;
import com.example.auth.repository.AssignmentRepository;
import com.example.auth.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VehicleService {

    private final VehicleMapper vehicleMapper;
    private final AssignmentRepository assignmentRepository;
    private final VehicleRepository vehicleRepository;
    private final IncidentService incidentService;
    private final WebSocketNotificationService webSocketNotificationService;
    private final VehicleLocationService vehicleLocationService;

    public VehicleService(AssignmentRepository assignmentRepository,
                         VehicleRepository vehicleRepository,
                         IncidentService incidentService,
                         VehicleMapper vehicleMapper,
                         WebSocketNotificationService webSocketNotificationService,
                         VehicleLocationService vehicleLocationService) {
        this.assignmentRepository = assignmentRepository;
        this.vehicleRepository = vehicleRepository;
        this.incidentService = incidentService;
        this.vehicleMapper = vehicleMapper;
        this.webSocketNotificationService = webSocketNotificationService;
        this.vehicleLocationService = vehicleLocationService;
    }

    @Transactional
    public void updateLocation(Integer vehicleId, BigDecimal latitude, BigDecimal longitude) {
        // Save to Redis for real-time tracking
        vehicleLocationService.saveLocationToRedis(vehicleId, latitude, longitude);

        if (latitude == null || longitude == null) return;

        List<Assignment> activeAssignments = assignmentRepository
                .findByVehicleVehicleIdAndAssignmentStatusNot(vehicleId, "COMPLETED");

        for (Assignment assignment : activeAssignments) {
            Incident incident = assignment.getIncident();
            BigDecimal incidentLat = incident.getAddress().getLatitude();
            BigDecimal incidentLng = incident.getAddress().getLongitude();

            if (incidentLat != null && incidentLng != null &&
                latitude.equals(incidentLat) && longitude.equals(incidentLng)) {

                assignment.setAssignmentStatus(AssignmentStatus.COMPLETED);
                assignment.setCompletedAt(LocalDateTime.now());
                assignmentRepository.save(assignment);

                List<Assignment> remainingAssignments = assignmentRepository
                        .findByVehicleVehicleIdAndAssignmentStatusNot(vehicleId, "COMPLETED");

                if (remainingAssignments.isEmpty()) {
                    Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
                    if (vehicle != null) {
                        vehicle.setStatus(VehicleStatus.AVAILABLE);
                        vehicleRepository.save(vehicle);
                    }
                }

                incidentService.checkIncidentCompletion(incident);
            }
        }
    }

    @Transactional(readOnly = true)
    public VehicleResponse getVehicleById(Integer vehicleId) {
        Vehicle vehicle = vehicleRepository.findVehicleById(vehicleId)
                .orElseThrow(() -> new RuntimeException("Vehicle not found with id: " + vehicleId));
        return vehicleMapper.toResponse(vehicle);
    }

    @Transactional
    public VehicleResponse updateStatus(Integer vehicleId, VehicleStatus status) {
        Vehicle vehicle = vehicleRepository.findVehicleById(vehicleId)
                .orElseThrow(() -> new RuntimeException("Vehicle not found with id: " + vehicleId));

        vehicle.setStatus(status);
        Vehicle updatedVehicle = vehicleRepository.save(vehicle);

        List<VehicleResponse> availableVehicles = getAvailableVehicles();
        webSocketNotificationService.notifyAvailableVehicleUpdate(availableVehicles);
        return vehicleMapper.toResponse(updatedVehicle);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> getVehiclesByStatus(VehicleStatus status) {
        List<Vehicle> vehicles = vehicleRepository.findByStatus(status.name());
        return vehicles.stream()
                .map(vehicleMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public VehicleResponse createVehicle(VehicleRequest vehicleRequest) {
        Vehicle vehicle = vehicleMapper.toEntity(vehicleRequest);
        Vehicle savedVehicle = vehicleRepository.save(vehicle);

        List<VehicleResponse> availableVehicles = getAvailableVehicles();
        webSocketNotificationService.notifyAvailableVehicleUpdate(availableVehicles);
        return vehicleMapper.toResponse(savedVehicle);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> getAvailableVehicles() {
        List<Vehicle> vehicles = vehicleRepository.findByStatus("AVAILABLE");
        return vehicles.stream().map(vehicleMapper::toResponse).collect(Collectors.toList());
    }

}
