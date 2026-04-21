package com.repoinspector.constants;

/**
 * Single source of truth for all Spring framework annotation FQNs used across the plugin.
 * This includes annotations for repositories, HTTP mappings, transactions, and more.
 *
 *
 * @author Sherif Elglaly
 * @version 1.0.0
 * @see com.repoinspector.actions.TraceRepositoryCallsAction
 * @see com.repoinspector.analysis.api.EndpointAnalysisService
 * @see com.repoinspector.analysis.api.CallChainService
 */
public final class SpringAnnotations {

    private SpringAnnotations() {}

    public static final String REPOSITORY = "org.springframework.stereotype.Repository";

    public static final String DATA_REPOSITORY           = "org.springframework.data.repository.Repository";
    public static final String CRUD_REPOSITORY           = "org.springframework.data.repository.CrudRepository";
    public static final String JPA_REPOSITORY            = "org.springframework.data.jpa.repository.JpaRepository";
    public static final String PAGING_SORTING_REPOSITORY = "org.springframework.data.repository.PagingAndSortingRepository";
    public static final String MONGO_REPOSITORY          = "org.springframework.data.mongodb.repository.MongoRepository";

    public static final String[] SPRING_DATA_BASE_TYPES = {
            DATA_REPOSITORY, CRUD_REPOSITORY, JPA_REPOSITORY, PAGING_SORTING_REPOSITORY
    };

    public static final String[] SPRING_DATA_REPO_FQNS = {
            JPA_REPOSITORY, CRUD_REPOSITORY, PAGING_SORTING_REPOSITORY, DATA_REPOSITORY, MONGO_REPOSITORY
    };

    public static final String GET_MAPPING     = "org.springframework.web.bind.annotation.GetMapping";
    public static final String POST_MAPPING    = "org.springframework.web.bind.annotation.PostMapping";
    public static final String PUT_MAPPING     = "org.springframework.web.bind.annotation.PutMapping";
    public static final String DELETE_MAPPING  = "org.springframework.web.bind.annotation.DeleteMapping";
    public static final String PATCH_MAPPING   = "org.springframework.web.bind.annotation.PatchMapping";
    public static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";

    public static final String[][] HTTP_MAPPING_ANNOTATIONS = {
            {GET_MAPPING,     "GET"},
            {POST_MAPPING,    "POST"},
            {PUT_MAPPING,     "PUT"},
            {DELETE_MAPPING,  "DELETE"},
            {PATCH_MAPPING,   "PATCH"},
            {REQUEST_MAPPING, "REQUEST"},
    };


    public static final String[] HTTP_MAPPING_FQNS = {
            GET_MAPPING, POST_MAPPING, PUT_MAPPING, DELETE_MAPPING, PATCH_MAPPING, REQUEST_MAPPING
    };

    public static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    public static final String MODIFYING     = "org.springframework.data.jpa.repository.Modifying";

    public static final String APPLICATION_CONTEXT = "org.springframework.context.ApplicationContext";
}
