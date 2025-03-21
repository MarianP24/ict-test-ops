package com.hella.test_ops.controller;

import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.FixtureMachineMapDTO;
import com.hella.test_ops.model.MachineDTO;
import com.hella.test_ops.repository.MachineRepository;
import com.hella.test_ops.service.impl.MachineServiceImpl;
import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/machines")
public class MachineController {
    private final MachineServiceImpl machineService;
    private final MachineRepository machineRepository;

    public MachineController(MachineServiceImpl machineService, MachineRepository machineRepository) {
        this.machineService = machineService;
        this.machineRepository = machineRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void save(@RequestBody MachineDTO machine) {
        machineService.save(machine);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public MachineDTO findById(@PathVariable long id) {
        return machineService.findById(id);
    }

    @GetMapping("/all-dto")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public List<MachineDTO> getMachines() {
        return machineService.findAll();
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<Machine>> getAllMachines() {
        return ResponseEntity.ok(machineService.findAllEntities());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void update(@PathVariable long id, @RequestBody MachineDTO machine) {
        machineService.update(id, machine);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void deleteById(@PathVariable long id) {
        machineService.deleteById(id);
    }

    @Transactional
    @GetMapping("/fixtureMap")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public List<FixtureMachineMapDTO> showFixtureMachineMap() {
        List<Machine> machines = machineRepository.findAll();
        return machines.stream()
                .flatMap(machine -> machine.getFixtures().stream()
                        .map(fixture -> new FixtureMachineMapDTO(
                                machine.getId(),
                                fixture.getId()
                        )))
                .collect(Collectors.toList());
    }
}