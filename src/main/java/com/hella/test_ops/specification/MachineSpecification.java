package com.hella.test_ops.specification;

import com.hella.test_ops.entity.Machine;
import org.springframework.data.jpa.domain.Specification;

public final class MachineSpecification {

    private MachineSpecification() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Specification<Machine> hasEquipmentNameLike(String equipmentName) {
        return (root, query, criteriaBuilder) ->
                equipmentName == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("equipmentName")),
                                "%" + equipmentName.toLowerCase() + "%"
                        );
    }

    public static Specification<Machine> hasInternalFactory(Integer internalFactory) {
        return (root, query, criteriaBuilder) ->
                internalFactory == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.equal(root.get("internalFactory"), internalFactory);
    }

    public static Specification<Machine> hasSerialNumberLike(String serialNumber) {
        return (root, query, criteriaBuilder) ->
                serialNumber == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("serialNumber")),
                                "%" + serialNumber.toLowerCase() + "%"
                        );
    }

    public static Specification<Machine> hasEquipmentTypeLike(String equipmentType) {
        return (root, query, criteriaBuilder) ->
                equipmentType == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("equipmentType")),
                                "%" + equipmentType.toLowerCase() + "%"
                        );
    }

    public static Specification<Machine> hasHostnameLike(String hostname) {
        return (root, query, criteriaBuilder) ->
                hostname == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("hostname")),
                                "%" + hostname.toLowerCase() + "%"
                        );
    }

    public static Specification<Machine> withFilters(
            String equipmentName,
            Integer internalFactory,
            String serialNumber,
            String equipmentType,
            String hostname) {

        return Specification
                .where(hasEquipmentNameLike(equipmentName))
                .and(hasInternalFactory(internalFactory))
                .and(hasSerialNumberLike(serialNumber))
                .and(hasEquipmentTypeLike(equipmentType))
                .and(hasHostnameLike(hostname));
    }
}