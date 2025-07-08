package com.hella.test_ops.service;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.MachineDTO;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public interface MachineService {
    void save(MachineDTO machineDTO);

    MachineDTO findById(long id);

    List<MachineDTO> findAll();

    void update(long id, MachineDTO machineDTO);

    @Transactional
    void deleteById(long id);

    Machine findEntityById(Long id);

    List<Machine> findAllEntities();

    Machine findByHostname(String hostname);

    @Transactional
    Set<Fixture> getMachineFixtureMap(Long machineId);

    List<MachineDTO> findByFilters(
            String equipmentName,
            Integer internalFactory,
            String serialNumber,
            String equipmentType,
            String hostname,
            String machineUsername
    );

    void assignVpnServer(long machineId, long vpnServerId);

    void removeVpnServer(long machineId);

}