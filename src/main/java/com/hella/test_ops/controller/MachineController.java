package com.hella.test_ops.controller;

import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.MachineDTO;
import com.hella.test_ops.service.impl.MachineServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/machines")
@CrossOrigin(origins = "http://localhost:3000")
public class MachineController {
    private final MachineServiceImpl machineService;

    public MachineController(MachineServiceImpl machineService) {
        this.machineService = machineService;
    }

    @PostMapping
    public void save(@RequestBody MachineDTO machine) {
        machineService.save(machine);
    }

    @GetMapping("/{id}")
    public MachineDTO findById(@PathVariable long id) {
        return machineService.findById(id);
    }

    @GetMapping("/all-dto")
    public List<MachineDTO> getMachines() {
        return machineService.findAll();
    }

    @GetMapping("/all")
    public ResponseEntity<List<Machine>> getAllMachines() {
        return ResponseEntity.ok(machineService.findAllEntities());
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @RequestBody MachineDTO machine) {
        machineService.update(id, machine);
    }

    @DeleteMapping("/{id}")
    public void deleteById(@PathVariable long id) {
        machineService.deleteById(id);
    }
}