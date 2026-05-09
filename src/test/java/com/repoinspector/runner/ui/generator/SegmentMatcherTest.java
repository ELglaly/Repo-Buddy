package com.repoinspector.runner.ui.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SegmentMatcherTest {

    @Test
    void exactMatch() {
        assertTrue(SegmentMatcher.has("id", "id"));
        assertTrue(SegmentMatcher.has("lat", "lat"));
    }

    @Test
    void underscoreSuffix() {
        assertTrue(SegmentMatcher.has("user_id", "id"));
        assertTrue(SegmentMatcher.has("order_id", "id"));
    }

    @Test
    void underscorePrefix() {
        assertTrue(SegmentMatcher.has("lat_lng", "lat"));
        assertTrue(SegmentMatcher.has("email_address", "email"));
    }

    @Test
    void underscoreInfix() {
        assertTrue(SegmentMatcher.has("from_lat_to", "lat"));
        assertTrue(SegmentMatcher.has("a_id_b", "id"));
    }

    @Test
    void camelCaseSuffix() {
        assertTrue(SegmentMatcher.has("userid", "id"));
        assertTrue(SegmentMatcher.has("orderId".toLowerCase(), "id"));
    }

    @Test
    void camelCasePrefix() {
        assertTrue(SegmentMatcher.has("latlng", "lat"));
        assertTrue(SegmentMatcher.has("emailaddress", "email"));
    }

    @Test
    void falsePositive_lat_in_platform() {
        assertFalse(SegmentMatcher.has("platform", "lat"));
    }

    @Test
    void falsePositive_lat_in_flatrate() {
        assertFalse(SegmentMatcher.has("flatrate", "lat"));
    }

    @Test
    void falsePositive_id_in_validity() {
        assertFalse(SegmentMatcher.has("validity", "id"));
    }

    @Test
    void multiSegment_matchesIfAnySegmentMatches() {
        assertTrue(SegmentMatcher.has("email", "email", "mail"));
        assertTrue(SegmentMatcher.has("usermail", "email", "mail"));
        assertFalse(SegmentMatcher.has("name", "email", "mail"));
    }

    @Test
    void noMatch_returnsFalse() {
        assertFalse(SegmentMatcher.has("completelydifferent", "id"));
        assertFalse(SegmentMatcher.has("foo", "bar", "baz"));
    }
}
