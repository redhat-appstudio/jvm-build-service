package com.redhat.hacbs.analyser.pnc.rest;

/**
 * A base interface for all DTO entities to allow generic implementation of some standard operations
 */
public interface DTOEntity {

    /**
     * Get the entity Id
     *
     * @return Id
     */
    String getId();

}
