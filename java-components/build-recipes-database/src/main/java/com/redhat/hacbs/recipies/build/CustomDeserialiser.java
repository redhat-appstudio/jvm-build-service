package com.redhat.hacbs.recipies.build;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

public class CustomDeserialiser
        extends StdDeserializer<Map<String, BuildRecipeInfo>> {

    public CustomDeserialiser() {
        this(null);
    }

    public CustomDeserialiser(Class<BuildRecipeInfo> vc) {
        super(vc);
    }

    @Override
    public Map<String, BuildRecipeInfo> deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        BuildRecipeInfo parentBuild = (BuildRecipeInfo) p.getParsingContext().getParent().getCurrentValue();
        JsonNode node = p.getCodec().readTree(p);
        Map<String, BuildRecipeInfo> result = new HashMap<>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            // Don't want to update the parent but have copies for each buildName.
            BuildRecipeInfo clone = BuildRecipeInfoManager.MAPPER.readValue(
                    BuildRecipeInfoManager.MAPPER.writeValueAsString(parentBuild),
                    BuildRecipeInfo.class);
            var field = iterator.next();
            var recipe = BuildRecipeInfoManager.MAPPER.readerForUpdating(clone).treeToValue(node.get(field),
                    BuildRecipeInfo.class);
            result.put(field, recipe);
        }
        System.err.println("### CustomDeserialiser result : " + result);

        return result;
    }
}
