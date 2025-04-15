package com.hella.test_ops.controller;

import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.entity.VpnServer;
import com.hella.test_ops.model.VpnServerDTO;
import com.hella.test_ops.service.impl.VpnServerServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/vpn-servers")
public class VpnServerController {
    private final VpnServerServiceImpl vpnServerService;

    public VpnServerController(VpnServerServiceImpl vpnServerService) {
        this.vpnServerService = vpnServerService;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void save(@RequestBody VpnServerDTO vpnServer) {
        vpnServerService.save(vpnServer);
    }

    // - Get a specific VPN server by ID
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public VpnServerDTO findById(@PathVariable long id) {
        return vpnServerService.findById(id);
    }

    // - Get all VPN servers as DTOs via GET
    @GetMapping("/all-dto")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public List<VpnServerDTO> getVpnServers() {
        return vpnServerService.findAll();
    }

    // - Get all VPN servers as full entities via GET
    @GetMapping("/all")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<VpnServer>> getAllVpnServers() {
        return ResponseEntity.ok(vpnServerService.findAllEntities());
    }

    // - Get a specific VPN server by ID via GET
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void update(@PathVariable long id, @RequestBody VpnServerDTO vpnServer) {
        vpnServerService.update(id, vpnServer);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public void deleteById(@PathVariable long id) {
        vpnServerService.deleteById(id);
    }

    // - View all machines connected to a specific VPN server via GET
    @GetMapping("/{id}/machines")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public Set<Machine> getVpnServerMachines(@PathVariable long id) {
        return vpnServerService.getVpnServerMachines(id);
    }

    // - Find a VPN server by name via GET
    @GetMapping("/by-name/{vpnName}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<VpnServer> findByVpnName(@PathVariable String vpnName) {
        return ResponseEntity.ok(vpnServerService.findByVpnName(vpnName));
    }

    @GetMapping("/filter")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<VpnServerDTO>> filterVpnServers(
            @RequestParam(required = false) String vpnName,
            @RequestParam(required = false) String serverAddress,
            @RequestParam(required = false) String destinationNetwork) {

        List<VpnServerDTO> filteredVpnServers = vpnServerService.findByFilters(
                vpnName, serverAddress, destinationNetwork);

        return ResponseEntity.ok(filteredVpnServers);
    }
}