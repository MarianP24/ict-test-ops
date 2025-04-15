package com.hella.test_ops.service.impl;

import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.entity.VpnServer;
import com.hella.test_ops.model.VpnServerDTO;
import com.hella.test_ops.repository.VpnServerRepository;
import com.hella.test_ops.service.VpnServerService;
import com.hella.test_ops.specification.VpnServerSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class VpnServerServiceImpl implements VpnServerService {
    private final VpnServerRepository vpnServerRepository;

    public VpnServerServiceImpl(VpnServerRepository vpnServerRepository) {
        this.vpnServerRepository = vpnServerRepository;
    }

    @Override
    public void save(VpnServerDTO vpnServerDTO) {
        vpnServerRepository.save(vpnServerDTO.convertToEntity());
        log.info("VPN Server {} has been saved", vpnServerDTO.vpnName());
    }

    @Override
    public VpnServerDTO findById(long id) {
        VpnServer vpnServer = vpnServerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("VPN Server with id " + id + " not found"));
        log.info("VPN Server {} has been found", vpnServer.getVpnName());
        return VpnServerDTO.convertToDTO(vpnServer);
    }

    @Override
    public List<VpnServerDTO> findAll() {
        List<VpnServer> vpnServers = vpnServerRepository.findAll();
        log.info("Found {} VPN Servers (DTO)", vpnServers.size());
        return vpnServers.stream()
                .map(VpnServerDTO::convertToDTO)
                .toList();
    }

    @Override
    public void update(long id, VpnServerDTO vpnServerDTO) {
        VpnServer oldVpnServer = vpnServerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("VPN Server with id " + id + " not found"));
        oldVpnServer.setVpnName(vpnServerDTO.vpnName());
        oldVpnServer.setServerAddress(vpnServerDTO.serverAddress());
        oldVpnServer.setDestinationNetwork(vpnServerDTO.destinationNetwork());
        vpnServerRepository.save(oldVpnServer);
        log.info("VPN Server {} has been updated", vpnServerDTO.vpnName());
    }

    @Transactional
    @Override
    public void deleteById(long id) {
        vpnServerRepository.deleteById(id);
        log.info("VPN Server with id {} has been deleted", id);
    }

    @Override
    public VpnServer findEntityById(Long id) {
        return vpnServerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("VPN Server with id " + id + " not found"));
    }

    @Override
    public List<VpnServer> findAllEntities() {
        List<VpnServer> vpnServers = vpnServerRepository.findAll();
        log.info("Found {} VPN Servers", vpnServers.size());
        return vpnServers;
    }

    @Override
    public VpnServer findByVpnName(String vpnName) {
        return vpnServerRepository.findByVpnName(vpnName)
                .orElseThrow(() -> new IllegalArgumentException("VPN Server with name " + vpnName + " not found"));
    }

    @Transactional
    @Override
    public Set<Machine> getVpnServerMachines(Long vpnServerId) {
        VpnServer vpnServer = vpnServerRepository.findById(vpnServerId)
                .orElseThrow(() -> new IllegalArgumentException("VPN Server with id " + vpnServerId + " not found"));
        log.info("Retrieved {} machines for VPN Server with id {}", vpnServer.getMachines().size(), vpnServerId);
        return vpnServer.getMachines();
    }

    @Override
    public List<VpnServerDTO> findByFilters(
            String vpnName,
            String serverAddress,
            String destinationNetwork) {

        List<VpnServer> filteredVpnServers = vpnServerRepository.findAll(
                VpnServerSpecification.withFilters(
                        vpnName,
                        serverAddress,
                        destinationNetwork
                )
        );

        log.info("Found {} VPN Servers matching filter criteria", filteredVpnServers.size());

        return filteredVpnServers.stream()
                .map(VpnServerDTO::convertToDTO)
                .toList();
    }
}