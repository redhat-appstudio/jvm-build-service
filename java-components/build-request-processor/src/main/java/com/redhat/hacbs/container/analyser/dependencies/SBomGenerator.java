package com.redhat.hacbs.container.analyser.dependencies;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.cyclonedx.BomParserFactory;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Property;

import com.redhat.hacbs.classfile.tracker.TrackingData;

public class SBomGenerator {

    public static Bom generateSBom(Set<TrackingData> trackingData, InputStream existing) {
        //now build a cyclone DX bom file
        final Bom bom;
        Map<Identifier, Component> existingIds = new HashMap<>();

        //we may need to merge this into an existing bom
        if (existing != null) {
            try {
                bom = BomParserFactory.createParser("{".getBytes(StandardCharsets.UTF_8)).parse(existing);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }

            //now lets clean up some of the syft duplication
            //syft can sometime generate double ups of artifacts
            //basically the same thing, but one with name 'com.foo.bar' and no group-id, and one with name 'bar'
            //and group 'com.foo'
            //we just want the later form, if they are both there we remove the problematic one
            for (var it = bom.getComponents().iterator(); it.hasNext();) {
                var i = it.next();
                Identifier identifier = new Identifier(i.getName(), i.getGroup(), i.getVersion());
                if (existingIds.containsKey(identifier)) {
                    it.remove();
                } else {
                    existingIds.put(identifier, i);
                }
            }
            for (var it = bom.getComponents().iterator(); it.hasNext();) {
                var i = it.next();
                if (i.getPurl() != null && i.getPurl().startsWith("pkg:maven")) {
                    if (i.getGroup() == null && i.getName().contains(".")) {
                        int lastDot = i.getName().lastIndexOf('.');
                        String name = i.getName().substring(lastDot + 1);
                        String group = i.getName().substring(0, lastDot);
                        Identifier key = new Identifier(name, group, i.getVersion());
                        if (existingIds.containsKey(key)) {
                            //this is a duplicate, remove it
                            it.remove();
                            existingIds.remove(new Identifier(i.getName(), i.getGroup(), i.getVersion()));
                        }
                    }
                }
            }

        } else {
            bom = new Bom();
            bom.setComponents(new ArrayList<>());
        }

        for (var i : trackingData) {
            var split = i.gav.split(":");
            String group = split[0];
            String name = split[1];
            String version = split[2];

            Component component = existingIds.get(new Identifier(name, group, version));
            List<Property> properties = new ArrayList<>();
            if (component == null) {
                component = new Component();
                bom.getComponents().add(component);
                component.setType(Component.Type.LIBRARY);
                component.setGroup(group);
                component.setName(name);
                component.setVersion(version);
                component.setPurl(String.format("pkg:maven/%s/%s@%s", group, name, version));
            } else if (component.getProperties() != null) {
                properties.addAll(component.getProperties());
            }
            component.setPublisher(i.source);
            for (var e : i.getAttributes().entrySet()) {
                Property property = new Property();
                property.setName("java:" + e.getKey());
                property.setValue(e.getValue());
                properties.add(property);
            }

            Property packageTypeProperty = new Property();
            packageTypeProperty.setName("package:type");
            packageTypeProperty.setValue("maven");
            properties.add(packageTypeProperty);

            Property packageLanguageProperty = new Property();
            packageLanguageProperty.setName("package:language");
            packageLanguageProperty.setValue("java");
            properties.add(packageLanguageProperty);
            component.setProperties(properties);

        }
        return bom;
    }

    static class Identifier {
        final String name;
        final String groupId;

        final String version;

        Identifier(String name, String groupId, String version) {
            this.name = name;
            this.groupId = groupId;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Identifier that = (Identifier) o;
            return Objects.equals(name, that.name) && Objects.equals(groupId, that.groupId)
                    && Objects.equals(version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, groupId, version);
        }
    }
}
