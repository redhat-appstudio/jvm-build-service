package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class ContainerImageRepository extends PanacheEntity {

    @Column(nullable = false, length = -1, unique = true)
    public String repository;

    public static ContainerImageRepository findByRepository(String repository) {
        return ContainerImageRepository.find("repository", repository).firstResult();
    }

    public static ContainerImageRepository getOrCreate(String repository) {
        var existing = findByRepository(repository);
        if (existing == null) {
            existing = new ContainerImageRepository();
            existing.repository = repository;
            existing.persistAndFlush();
        }
        return existing;
    }
}
