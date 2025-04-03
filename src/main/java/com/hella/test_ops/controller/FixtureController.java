package com.hella.test_ops.controller;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.model.FixtureDTO;
import com.hella.test_ops.service.FixtureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
