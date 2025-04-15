package com.hella.test_ops.model;

import com.hella.test_ops.entity.VpnServer;

public record VpnServerDTO(String vpnName, String serverAddress, String destinationNetwork) {

    public VpnServer convertToEntity() {
        VpnServer vpnServer = new VpnServer();
        vpnServer.setVpnName(this.vpnName());
        vpnServer.setServerAddress(this.serverAddress());
        vpnServer.setDestinationNetwork(this.destinationNetwork());
        return vpnServer;
    }

    public static VpnServerDTO convertToDTO(VpnServer vpnServer) {
        return new VpnServerDTO(
                vpnServer.getVpnName(),
                vpnServer.getServerAddress(),
                vpnServer.getDestinationNetwork()
        );
    }
}