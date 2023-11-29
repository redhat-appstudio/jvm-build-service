package com.redhat.hacbs.management.model;

import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity()
@Table(uniqueConstraints = { @UniqueConstraint(columnNames = { "identifier", "type" }) })
public class DependencySet extends PanacheEntity {

    /**
     * An identifier for the dependency set
     */
    @Column(nullable = false)
    public String identifier;

    @Column(nullable = false)
    public String type;

    @OneToMany(fetch = FetchType.EAGER, mappedBy = "dependencySet", cascade = CascadeType.ALL)
    public List<IdentifiedDependency> dependencies;

}
