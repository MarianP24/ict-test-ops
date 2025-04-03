package com.hella.test_ops.specification;

import com.hella.test_ops.entity.Fixture;
import org.springframework.data.jpa.domain.Specification;

public final class FixtureSpecification {

    private FixtureSpecification() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Specification<Fixture> hasFileNameLike(String fileName) {
        return (root, query, criteriaBuilder) ->
                fileName == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("fileName")),
                                "%" + fileName.toLowerCase() + "%"
                        );
    }

    public static Specification<Fixture> hasProgramNameLike(String programName) {
        return (root, query, criteriaBuilder) ->
                programName == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("programName")),
                                "%" + programName.toLowerCase() + "%"
                        );
    }

    public static Specification<Fixture> hasProductNameLike(String productName) {
        return (root, query, criteriaBuilder) ->
                productName == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("productName")),
                                "%" + productName.toLowerCase() + "%"
                        );
    }

    public static Specification<Fixture> hasBusinessLike(String business) {
        return (root, query, criteriaBuilder) ->
                business == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("business")),
                                "%" + business.toLowerCase() + "%"
                        );
    }

    public static Specification<Fixture> hasFixtureCounterSet(Long fixtureCounterSet) {
        return (root, query, criteriaBuilder) ->
                fixtureCounterSet == null ? criteriaBuilder.conjunction() :
                        criteriaBuilder.equal(root.get("fixtureCounterSet"), fixtureCounterSet);
    }

    public static Specification<Fixture> withFilters(
            String fileName,
            String programName,
            String productName,
            String business,
            Long fixtureCounterSet) {

        return Specification
                .where(hasFileNameLike(fileName))
                .and(hasProgramNameLike(programName))
                .and(hasProductNameLike(productName))
                .and(hasBusinessLike(business))
                .and(hasFixtureCounterSet(fixtureCounterSet));
    }
}