package com.redhat.hacbs.artifactcache;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.redhat.hacbs.artifactcache.bloom.BloomFilter;

public class BloomFilterTestCase {

    @Test
    public void testBloomFilter() throws Exception {

        try (InputStream in = Files.newInputStream(Paths.get("../../hack/bloom-filter-test-data.json"))) {

            List<BloomFilterData> data = new ObjectMapper().readValue(in,
                    TypeFactory.defaultInstance().constructCollectionLikeType(ArrayList.class, BloomFilterData.class));
            Assertions.assertFalse(data.isEmpty());
            for (var ds : data) {
                byte[] filter = Base64.getDecoder().decode(ds.filter);
                for (var i : ds.items) {
                    Assertions.assertTrue(BloomFilter.isPossible(filter, i));
                }
            }
        }
    }

}

class BloomFilterData {
    String filter;
    List<String> items;

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items;
    }
}
