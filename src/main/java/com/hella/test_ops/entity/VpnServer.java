package com.hella.test_ops.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "server_vpn")
public class VpnServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String vpnName;

    private String serverAddress;

    private String destinationNetwork;

    @JsonIgnore
    @OneToMany(mappedBy = "vpnServer")
    private Set<Machine> machines = new HashSet<>();
}