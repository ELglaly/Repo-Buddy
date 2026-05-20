package com.repoinspector.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EndpointInfoTest {

    @Test
    void toString_formatsCorrectly() {
        EndpointInfo ep = new EndpointInfo(
                "GET", "/api/users", "UserController", "getUser(Long)",
                "com.example.UserController", null);
        assertEquals("GET /api/users  [UserController.getUser(Long)]", ep.toString());
    }

    @Test
    void withoutPsi_preservesFields_stripsMethod() {
        EndpointInfo original = new EndpointInfo(
                "POST", "/api/orders", "OrderController", "placeOrder()",
                "com.example.OrderController", null);
        EndpointInfo stripped = original.withoutPsi();

        assertNull(stripped.psiMethod());
        assertEquals(original.httpMethod(), stripped.httpMethod());
        assertEquals(original.path(), stripped.path());
        assertEquals(original.controllerName(), stripped.controllerName());
        assertEquals(original.methodSignature(), stripped.methodSignature());
        assertEquals(original.qualifiedControllerName(), stripped.qualifiedControllerName());
    }
}
