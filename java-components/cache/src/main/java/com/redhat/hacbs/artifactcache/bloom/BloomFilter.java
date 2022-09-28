package com.redhat.hacbs.artifactcache.bloom;

public class BloomFilter {

    /**
     * Determins if an element is possibly in the rebuilt set based on a serialized bloom filter
     *
     * Note that this must be kept in sync with the golang code that generates the filter
     *
     * @param filter The serialised filter
     * @param gav The gav to check
     * @return <code>true</code> if the provided gav might have been rebuilt
     */
    public static boolean isPossible(byte[] filter, String gav) {
        if (filter == null) {
            return false;
        }
        for (int i = 1; i <= 10; ++i) {
            int hash = doHash(i, gav);
            var totalBits = filter.length * 8;
            hash = hash % totalBits;
            if (hash < 0) {
                hash = hash * -1;
            }
            var pos = hash / 8;
            var bit = hash % 8;
            if ((filter[pos] & (1 << bit)) == 0) {
                return false;
            }
        }
        return true;
    }

    private static int doHash(int multiplicand, String gav) {
        //super simple hash function
        //must match the golang version
        multiplicand = multiplicand * 7;
        int hash = 0;
        for (int i = 0; i < gav.length(); i++) {
            hash = multiplicand * hash + gav.charAt(i);
        }
        return hash;
    }

}
