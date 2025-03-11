package com.hella.test_ops.controller;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.model.FixtureDTO;
import com.hella.test_ops.repository.FixtureRepository;
import com.hella.test_ops.service.FixtureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/fixtures")
public class FixtureController {
    private final FixtureService fixtureService;
    private final FixtureRepository fixtureRepository;

    public FixtureController(FixtureService fixtureService, FixtureRepository fixtureRepository) {
        this.fixtureService = fixtureService;
        this.fixtureRepository = fixtureRepository;
    }

    @PostMapping
    public void save(@RequestBody FixtureDTO fixture) {
        fixtureService.save(fixture);
    }

    @GetMapping("/{id}")
    public FixtureDTO findById(@PathVariable long id) {
        return fixtureService.findById(id);
    }

    @GetMapping("/listFixtures")
    public List<FixtureDTO> getFixtures() {
        return fixtureService.findAll();
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @RequestBody FixtureDTO fixture) {
        fixtureService.update(id, fixture);
    }

    @DeleteMapping("/{id}")
    public void deleteById(@PathVariable long id) {
        fixtureService.removeFixtureFromMachine(id);
        fixtureService.deleteById(id);
    }

    @PostMapping("/{fixtureId}/machines/{machineId}")
    public void addFixtureToMachine(@PathVariable long fixtureId,@PathVariable long machineId) {
        fixtureService.addFixtureToMachine(fixtureId, machineId);
    }

    @PostMapping ("/maintenance")
    public void createMaintenanceFixtureReport() {
        fixtureService.createMaintenanceFixtureReport();
    }

    @GetMapping("/counter")
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
}
