package com.example.auth.controller.rest;

import com.example.auth.dto.VehicleRequest;
import com.example.auth.dto.VehicleResponse;
import com.example.auth.enums.VehicleStatus;
import com.example.auth.service.VehicleService;
import com.example.auth.service.VehicleLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/vehicle")
@Tag(name = "Vehicle Management", description = "Emergency vehicle and responder unit management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class VehicleController {

    @Autowired
    private VehicleService vehicleService;

    @Operation(
            summary = "Get vehicle by ID",
            description = "Retrieves detailed information about a specific emergency vehicle."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved vehicle",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VehicleResponse.class)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<VehicleResponse> getVehicleById(
            @Parameter(description = "Vehicle ID", required = true, example = "1")
            @PathVariable Integer id) {
        VehicleResponse vehicle = vehicleService.getVehicleById(id);
        return ResponseEntity.ok(vehicle);
    }

    @Operation(
            summary = "Update vehicle status",
            description = "Updates the operational status of a vehicle (AVAILABLE, BUSY, MAINTENANCE, OUT_OF_SERVICE)."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Status updated successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VehicleResponse.class)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    @PutMapping("/update-status")
    public ResponseEntity<VehicleResponse> updateStatus(
            @Parameter(description = "Vehicle ID", required = true, example = "1")
            @RequestParam Integer vehicleId,
            @Parameter(description = "New vehicle status", required = true, example = "AVAILABLE")
            @RequestParam VehicleStatus status
    ) {
        VehicleResponse updatedVehicle = vehicleService.updateStatus(vehicleId, status);
        return ResponseEntity.ok(updatedVehicle);
    }

    @Operation(
            summary = "Get vehicles by status",
            description = "Retrieves all vehicles with a specific operational status."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved vehicles",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VehicleResponse.class)
                    )
            )
    })
    @GetMapping("/by-status")
    public ResponseEntity<List<VehicleResponse>> getVehiclesByStatus(
            @Parameter(description = "Vehicle status to filter by", required = true, example = "AVAILABLE")
            @RequestParam VehicleStatus status
    ) {
        List<VehicleResponse> vehicles = vehicleService.getVehiclesByStatus(status);
        return ResponseEntity.ok(vehicles);
    }

    @Operation(
            summary = "Update vehicle location",
            description = "Updates the real-time GPS location of a vehicle for tracking purposes."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Location updated successfully",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = "\"Vehicle location updated successfully\"")
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    @PutMapping("/{vehicleId}/location")
    public ResponseEntity<String> updateLocation(
            @Parameter(description = "Vehicle ID", required = true, example = "1")
            @PathVariable Integer vehicleId,
            @Parameter(description = "Latitude coordinate", required = true, example = "30.0444")
            @RequestParam BigDecimal latitude,
            @Parameter(description = "Longitude coordinate", required = true, example = "31.2357")
            @RequestParam BigDecimal longitude) {
        vehicleService.updateLocation(vehicleId, latitude, longitude);
        return ResponseEntity.ok("Vehicle location updated successfully");
    }

    @Operation(
            summary = "Create new vehicle",
            description = "Registers a new emergency vehicle in the system."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Vehicle created successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VehicleResponse.class)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Bad request - validation error")
    })
    @PostMapping("/create")
    public ResponseEntity<VehicleResponse> createVehicle(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Vehicle details",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = VehicleRequest.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "type": "AMBULANCE",
                                        "licensePlate": "ABC-1234",
                                        "status": "AVAILABLE",
                                        "latitude": 30.0444,
                                        "longitude": 31.2357
                                    }
                                    """)
                    )
            )
            @RequestBody VehicleRequest vehicleRequest) {
        VehicleResponse createdVehicle = vehicleService.createVehicle(vehicleRequest);
        return ResponseEntity.ok(createdVehicle);
    }

    @Operation(
            summary = "Get all available vehicles",
            description = "Retrieves a list of all vehicles that are currently marked as AVAILABLE."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved available vehicles",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VehicleResponse.class)
                    )
            )
    })
    @GetMapping("/available")
    public ResponseEntity<List<VehicleResponse>> getAllAvailableVehicles() {
        List<VehicleResponse> vehicles = vehicleService.getVehiclesByStatus(VehicleStatus.AVAILABLE);
        return ResponseEntity.ok(vehicles);
    }

    @Operation(
            summary = "Calculate route for vehicle",
            description = "Calculates and stores route for vehicle to target location using GraphHopper."
    )
    @PostMapping("/{vehicleId}/calculate-route")
    public ResponseEntity<String> calculateRoute(
            @PathVariable Integer vehicleId,
            @RequestParam BigDecimal targetLat,
            @RequestParam BigDecimal targetLng) {
        try {
            vehicleLocationService.calculateAndStoreRoute(vehicleId, targetLat, targetLng);
            return ResponseEntity.ok("Route calculated and stored successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to calculate route: " + e.getMessage());
        }
    }

    @Autowired
    private VehicleLocationService vehicleLocationService;
}
