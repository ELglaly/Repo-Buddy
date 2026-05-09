package com.repoinspector.constants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpringAnnotationsTest {

    @Test
    void repositoryFqns_areCorrect() {
        assertEquals("org.springframework.data.jpa.repository.JpaRepository", SpringAnnotations.JPA_REPOSITORY);
        assertEquals("org.springframework.data.repository.CrudRepository", SpringAnnotations.CRUD_REPOSITORY);
        assertEquals("org.springframework.data.repository.PagingAndSortingRepository", SpringAnnotations.PAGING_SORTING_REPOSITORY);
        assertEquals("org.springframework.data.repository.Repository", SpringAnnotations.DATA_REPOSITORY);
        assertEquals("org.springframework.data.mongodb.repository.MongoRepository", SpringAnnotations.MONGO_REPOSITORY);
    }

    @Test
    void httpMappingFqns_areCorrect() {
        assertEquals("org.springframework.web.bind.annotation.GetMapping", SpringAnnotations.GET_MAPPING);
        assertEquals("org.springframework.web.bind.annotation.PostMapping", SpringAnnotations.POST_MAPPING);
        assertEquals("org.springframework.web.bind.annotation.PutMapping", SpringAnnotations.PUT_MAPPING);
        assertEquals("org.springframework.web.bind.annotation.DeleteMapping", SpringAnnotations.DELETE_MAPPING);
        assertEquals("org.springframework.web.bind.annotation.PatchMapping", SpringAnnotations.PATCH_MAPPING);
    }

    @Test
    void transactionalAndModifying_areCorrect() {
        assertEquals("org.springframework.transaction.annotation.Transactional", SpringAnnotations.TRANSACTIONAL);
        assertEquals("org.springframework.data.jpa.repository.Modifying", SpringAnnotations.MODIFYING);
    }

    @Test
    void springDataRepoFqns_containsJpaAndCrud() {
        assertArrayContains(SpringAnnotations.SPRING_DATA_REPO_FQNS, SpringAnnotations.JPA_REPOSITORY);
        assertArrayContains(SpringAnnotations.SPRING_DATA_REPO_FQNS, SpringAnnotations.CRUD_REPOSITORY);
        assertTrue(SpringAnnotations.SPRING_DATA_REPO_FQNS.length >= 4);
    }

    @Test
    void httpMappingAnnotations_verbsAreCorrect() {
        boolean foundGet = false, foundPost = false;
        for (String[] pair : SpringAnnotations.HTTP_MAPPING_ANNOTATIONS) {
            assertEquals(2, pair.length);
            if (SpringAnnotations.GET_MAPPING.equals(pair[0])) {
                assertEquals("GET", pair[1]);
                foundGet = true;
            }
            if (SpringAnnotations.POST_MAPPING.equals(pair[0])) {
                assertEquals("POST", pair[1]);
                foundPost = true;
            }
        }
        assertTrue(foundGet, "GET mapping not found in HTTP_MAPPING_ANNOTATIONS");
        assertTrue(foundPost, "POST mapping not found in HTTP_MAPPING_ANNOTATIONS");
    }

    private static void assertArrayContains(String[] array, String value) {
        for (String s : array) {
            if (value.equals(s)) return;
        }
        fail("Array does not contain: " + value);
    }
}
