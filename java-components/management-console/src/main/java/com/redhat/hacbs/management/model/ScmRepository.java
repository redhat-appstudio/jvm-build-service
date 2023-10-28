package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class ScmRepository extends PanacheEntity {

    @Column(unique = true)
    public String url;

    public static ScmRepository findORCreate(String url) {
        ScmRepository ret = find("url", url).firstResult();
        if (ret == null) {
            ret = new ScmRepository();
            ret.url = url;
            ret.persistAndFlush();
        }
        return ret;
    }

}
