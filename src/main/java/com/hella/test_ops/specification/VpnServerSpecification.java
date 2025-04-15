package com.hella.test_ops.specification;

import com.hella.test_ops.entity.VpnServer;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

public final class VpnServerSpecification {

    private VpnServerSpecification() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Specification<VpnServer> hasVpnNameLike(String vpnName) {
        return (root, query, criteriaBuilder) ->
                vpnName == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("vpnName")),
                                "%" + vpnName.toLowerCase() + "%"
                        );
    }

    public static Specification<VpnServer> hasServerAddressLike(String serverAddress) {
        return (root, query, criteriaBuilder) ->
                serverAddress == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("serverAddress")),
                                "%" + serverAddress.toLowerCase() + "%"
                        );
    }

    public static Specification<VpnServer> hasDestinationNetworkLike(String destinationNetwork) {
        return (root, query, criteriaBuilder) ->
                destinationNetwork == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("destinationNetwork")),
                                "%" + destinationNetwork.toLowerCase() + "%"
                        );
    }

    public static Sort withFilters(
            String vpnName,
            String serverAddress,
            String destinationNetwork) {

        return (Sort) Specification
                .where(hasVpnNameLike(vpnName))
                .and(hasServerAddressLike(serverAddress))
                .and(hasDestinationNetworkLike(destinationNetwork));
    }
}