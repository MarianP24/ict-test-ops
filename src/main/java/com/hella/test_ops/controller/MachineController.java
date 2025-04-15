package com.hella.test_ops.controller;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.MachineDTO;
import com.hella.test_ops.service.impl.MachineServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/machines")
public class MachineController {
    private final MachineServiceImpl machineService;

    public MachineController(MachineServiceImpl machineService) {
        this.machineService = machineService;
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

    @GetMapping("/{id}/fixtures")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public Set<Fixture> getMachineFixtureMap(@PathVariable long id) {
        return machineService.getMachineFixtureMap(id);
    }

    @GetMapping("/filter")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<MachineDTO>> filterMachines(
            @RequestParam(required = false) String equipmentName,
            @RequestParam(required = false) Integer internalFactory,
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) String equipmentType,
            @RequestParam(required = false) String hostname) {

        List<MachineDTO> filteredMachines = machineService.findByFilters(
                equipmentName, internalFactory, serialNumber, equipmentType, hostname);

        return ResponseEntity.ok(filteredMachines);
    }

    @PutMapping("/{id}/assign-vpn")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<Void> assignVpnServer(
            @PathVariable long id,
            @RequestParam long vpnServerId) {
        machineService.assignVpnServer(id, vpnServerId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/remove-vpn")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<Void> removeVpnServer(@PathVariable long id) {
        machineService.removeVpnServer(id);
        return ResponseEntity.ok().build();
    }
}