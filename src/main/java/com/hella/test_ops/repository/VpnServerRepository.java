package com.hella.test_ops.repository;

import com.hella.test_ops.entity.VpnServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VpnServerRepository extends JpaRepository<VpnServer, Long> {

    // Find a VPN server by its name
    Optional<VpnServer> findByVpnName(String vpnName);

    // Check if a VPN server with the given name exists
    boolean existsByVpnName(String vpnName);
}