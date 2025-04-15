package com.hella.test_ops.service;

import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.entity.VpnServer;
import com.hella.test_ops.model.VpnServerDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public interface VpnServerService {
    void save(VpnServerDTO vpnServerDTO);

    VpnServerDTO findById(long id);

    List<VpnServerDTO> findAll();

    void update(long id, VpnServerDTO vpnServerDTO);

    @Transactional
    void deleteById(long id);

    VpnServer findEntityById(Long id);

    List<VpnServer> findAllEntities();

    VpnServer findByVpnName(String vpnName);

    // If you need to get machines connected to a VPN server
    @Transactional
    Set<Machine> getVpnServerMachines(Long vpnServerId);

    List<VpnServerDTO> findByFilters(
            String vpnName,
            String serverAddress,
            String destinationNetwork
    );
}