package com.hella.test_ops.service.impl;

import com.hella.test_ops.entity.Fixture;
import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.MachineDTO;
import com.hella.test_ops.repository.MachineRepository;
import com.hella.test_ops.service.MachineService;
import com.hella.test_ops.specification.MachineSpecification;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class MachineServiceImpl implements MachineService {
    private final MachineRepository machineRepository;

    public MachineServiceImpl(MachineRepository machineRepository) {
        this.machineRepository = machineRepository;
    }

    @Override
    public void save(MachineDTO machineDTO) {
        machineRepository.save(machineDTO.convertToEntity());
        log.info("Machine {} has been saved", machineDTO.equipmentName());
    }

    @Override
    public MachineDTO findById(long id) {
        Machine machine = machineRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Machine with id " + id + " not found"));
        log.info("Machine {} has been found", machine.getEquipmentName());
        return MachineDTO.convertToDTO(machine);
    }

    @Override
    public List<MachineDTO> findAll() {
        List<Machine> machines = machineRepository.findAll();
        log.info("Found {} machines (DTO)", machines.size());
        return machines.stream()
                .map(MachineDTO::convertToDTO)
                .toList();
    }

    @Override
    public List<Machine> findAllEntities() {
        List<Machine> machines = machineRepository.findAll();
        log.info("Found {} machines", machines.size());
        return machines;
    }


    @Override
    public void update(long id, MachineDTO machineDTO) {
        Machine oldMachine = machineRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Machine with id " + id + " not found"));
        oldMachine.setEquipmentName(machineDTO.equipmentName());
        oldMachine.setInternalFactory(machineDTO.internalFactory());
        oldMachine.setSerialNumber(machineDTO.serialNumber());
        oldMachine.setEquipmentType(machineDTO.equipmentType());
        oldMachine.setHostname(machineDTO.hostname());
        machineRepository.save(oldMachine);
        log.info("Machine {} has been updated", machineDTO.equipmentName());
    }

    @Transactional
    @Override
    public void deleteById(long id) {
        // First, delete all associations in the fixture_machine table
        machineRepository.deleteFixtureRelations(id);

        // Then delete the machine itself
        machineRepository.deleteById(id);
        log.info("Machine with id {} and its related fixture  have been deleted", id);
    }

    @Override
    public Machine findEntityById(Long id) {
        return machineRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Machine with id " + id + " not found"));
    }

    @Override
    public Machine findByHostname(String hostname) {
        return machineRepository.findByHostname(hostname);
    }

    @Transactional
    @Override
    public Set<Fixture> getMachineFixtureMap(Long machineId) {
        Machine machine = machineRepository.findById(machineId)
                .orElseThrow(() -> new IllegalArgumentException("Machine with id " + machineId + " not found"));
        log.info("Retrieved {} fixtures for machine with id {}", machine.getFixtures().size(), machineId);
        return machine.getFixtures();
    }

    @Override
    public List<MachineDTO> findByFilters(
            String equipmentName,
            Integer internalFactory,
            String serialNumber,
            String equipmentType,
            String hostname) {

        List<Machine> filteredMachines = machineRepository.findAll(
                MachineSpecification.withFilters(
                        equipmentName,
                        internalFactory,
                        serialNumber,
                        equipmentType,
                        hostname
                )
        );

        log.info("Found {} machines matching the filter criteria", filteredMachines.size());

        return filteredMachines.stream()
                .map(MachineDTO::convertToDTO)
                .toList();
    }
}