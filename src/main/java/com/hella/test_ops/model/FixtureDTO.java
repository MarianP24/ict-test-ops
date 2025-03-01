package com.hella.test_ops.model;

import com.hella.test_ops.entity.Fixture;

public record FixtureDTO(String fileName, String programName, String productName, String business, long fixtureCounterSet) {

    public static FixtureDTO convertToDTO(Fixture fixture) {
        return new FixtureDTO(fixture.getFileName(), fixture.getProgramName(), fixture.getProductName(), fixture.getBusiness(), fixture.getFixtureCounterSet());
    }

    public Fixture convertToEntity() {
        Fixture fixture = new Fixture();
        fixture.setFileName(this.fileName());
        fixture.setBusiness(this.business());
        fixture.setProductName(this.productName());
        fixture.setProgramName(this.programName());
        fixture.setFixtureCounterSet(this.fixtureCounterSet());
        return fixture;
    }
}
