package com.hella.test_ops.controller;

import com.hella.test_ops.model.FixtureDTO;
import com.hella.test_ops.service.FixtureService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/fixtures")
public class FixtureController {
    private final FixtureService fixtureService;

    public FixtureController(FixtureService fixtureService) {
        this.fixtureService = fixtureService;
    }

    @PostMapping
    public void save(@RequestBody FixtureDTO fixture) {
        fixtureService.save(fixture);
    }

    @GetMapping("/{id}")
    public FixtureDTO findById(@PathVariable long id) {
        return fixtureService.findById(id);
    }

    @GetMapping
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
}
