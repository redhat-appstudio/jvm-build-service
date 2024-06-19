package com.redhat.hacbs.domainproxy;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Property;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(targets = { Bom.class, Component.class, Property.class })
public class ReflectionConfiguration {
}
