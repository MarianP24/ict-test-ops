package com.hella.test_ops.controller;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.FixtureDTO;
import com.hella.test_ops.service.FixtureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/fixtures")
public class FixtureController {
    private final FixtureService fixtureService;

    public FixtureController(FixtureService fixtureService) {
        this.fixtureService = fixtureService;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void save(@RequestBody FixtureDTO fixture) {
        fixtureService.save(fixture);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public FixtureDTO findById(@PathVariable long id) {
        return fixtureService.findById(id);
    }

    @GetMapping("/listFixturesDTO")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public List<FixtureDTO> getFixtures() {
        return fixtureService.findAll();
    }

    @GetMapping("/list-all-fixtures")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<Fixture>> getAllFixtures() {
        return ResponseEntity.ok(fixtureService.findAllEntities());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void update(@PathVariable long id, @RequestBody FixtureDTO fixture) {
        fixtureService.update(id, fixture);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void deleteById(@PathVariable long id) {
        fixtureService.removeFixtureFromMachine(id);
        fixtureService.deleteById(id);
    }

    @PostMapping("/{fixtureId}/machines/{machineId}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<Void> addFixtureToMachine(@PathVariable long fixtureId, @PathVariable long machineId) {
        try {
            fixtureService.addFixtureToMachine(fixtureId, machineId);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping ("/maintenance")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void createMaintenanceFixtureReport() {
        fixtureService.createMaintenanceFixtureReport();
    }

    @PostMapping("/maintenance/{fixtureId}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> createMaintenanceReportForSingleFixture(@PathVariable long fixtureId) {
        try {
            String result = fixtureService.createMaintenanceReportForSingleFixture(fixtureId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.error("Failed to process maintenance report for fixture ID {}: {}", fixtureId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            log.error("Error processing maintenance report for fixture ID {}", fixtureId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An error occurred while processing the fixture maintenance report: " + e.getMessage());
        }
    }

    @GetMapping("/counter")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> getCounterContent() {
        try {
            String counterContent = fixtureService.getCounterContent();
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(counterContent);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error retrieving counter content: " + e.getMessage());
        }
    }

    @GetMapping("/filter")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<FixtureDTO>> filterFixtures(
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) String programName,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) String business,
            @RequestParam(required = false) Long fixtureCounterSet) {

        List<FixtureDTO> filteredFixtures = fixtureService.findByFilters(
                fileName, programName, productName, business, fixtureCounterSet);

        return ResponseEntity.ok(filteredFixtures);
    }

    @GetMapping("/{id}/machineMap")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<Set<Machine>> getFixtureMachineMap(@PathVariable long id) {
        try {
            Set<Machine> machines = fixtureService.getFixtureMachineMap(id);
            return ResponseEntity.ok(machines);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{fixtureId}/machines/{machineId}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<Void> removeFixtureFromSpecificMachine(
            @PathVariable long fixtureId,
            @PathVariable long machineId) {
        try {
            fixtureService.removeFixtureFromSpecificMachine(fixtureId, machineId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.error("Failed to remove fixture {} from machine {}: {}", fixtureId, machineId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Error removing fixture {} from machine {}", fixtureId, machineId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/vnc/{hostname}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> connectVnc(@PathVariable String hostname) {
        try {
            fixtureService.connectVnc(hostname);
            return ResponseEntity.ok("VNC connection initiated to " + hostname);
        } catch (IOException e) {
            log.error("Failed to connect VNC to {}: {}", hostname, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to connect VNC to " + hostname + ": " + e.getMessage());
        }
    }

    @PostMapping("/connect-c-drive/{hostname}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> connectToCDrive(@PathVariable String hostname) {
        try {
            fixtureService.connectToCDrive(hostname);
            return ResponseEntity.ok("Successfully connected to C$ drive on " + hostname);
        } catch (IOException e) {
            log.error("Failed to connect to C$ drive on {}: {}", hostname, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to connect to C$ drive on " + hostname + ": " + e.getMessage());
        }
    }

    @PostMapping("/connect-d-drive/{hostname}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<String> connectToDDrive(@PathVariable String hostname) {
        try {
            fixtureService.connectToDDrive(hostname);
            return ResponseEntity.ok("Successfully connected to D$ drive on " + hostname);
        } catch (IOException e) {
            log.error("Failed to connect to D$ drive on {}: {}", hostname, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to connect to D$ drive on " + hostname + ": " + e.getMessage());
        }
    }
}
