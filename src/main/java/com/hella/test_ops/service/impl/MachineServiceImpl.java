package com.hella.test_ops.service.impl;

import com.hella.test_ops.entity.Machine;
import com.hella.test_ops.model.FixtureMachineMapDTO;
import com.hella.test_ops.model.MachineDTO;
import com.hella.test_ops.repository.MachineRepository;
import com.hella.test_ops.service.MachineService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class MachineServiceImpl implements MachineService {
    private final MachineRepository machineRepository;

    public MachineServiceImpl(MachineRepository machineRepository, MachineService machineService, MachineServiceImpl machineService1) {
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
        log.info("Found {} machines", machines.size());
        return machines.stream()
                .map(MachineDTO::convertToDTO)
                .toList();
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
}
