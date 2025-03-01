package com.hella.test_ops.model;

import com.hella.test_ops.entity.Machine;

public record MachineDTO(String equipmentName, String equipmentType, String serialNumber, int internalFactory, String hostname) {

    public Machine convertToEntity() {
        Machine machine = new Machine();
        machine.setEquipmentName(this.equipmentName());
        machine.setEquipmentType(this.equipmentType());
        machine.setSerialNumber(this.serialNumber());
        machine.setInternalFactory(this.internalFactory());
        machine.setHostname(this.hostname());
        return machine;
    }

    public static MachineDTO convertToDTO(Machine machine) {
        return new MachineDTO(machine.getEquipmentName(), machine.getEquipmentType(), machine.getSerialNumber(), machine.getInternalFactory(), machine.getHostname());
    }
}
