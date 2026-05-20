package com.repoinspector.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CallChainNodeTest {

    @Test
    void displayLabel_regularNode() {
        CallChainNode node = node("UserService", "getUser(Long)", 1,
                false, OperationType.UNKNOWN, "", false, false);
        assertEquals("UserService.getUser(Long)", node.displayLabel());
    }

    @Test
    void displayLabel_repositoryNode_withOperationType() {
        CallChainNode node = node("UserRepository", "findById(Long)", 2,
                true, OperationType.READ, "", false, false);
        String label = node.displayLabel();
        assertTrue(label.contains("[READ]"), "repo label must contain [READ]: " + label);
        assertTrue(label.contains("UserRepository.findById(Long)"));
    }

    @Test
    void displayLabel_repositoryNode_withEntityName() {
        CallChainNode node = node("UserRepository", "save(User)", 2,
                true, OperationType.WRITE, "User", false, false);
        String label = node.displayLabel();
        assertTrue(label.contains("entity=User"), "label must contain entity=User: " + label);
        assertTrue(label.contains("[WRITE]"));
    }

    @Test
    void displayLabel_transactionalNode() {
        CallChainNode node = node("OrderService", "placeOrder()", 1,
                false, OperationType.UNKNOWN, "", true, false);
        String label = node.displayLabel();
        assertTrue(label.contains("[@Transactional]"), "label must contain @Transactional: " + label);
    }

    @Test
    void displayLabel_dynamicNode_prefixedWithDynamic() {
        CallChainNode node = node("SomeDynamic", "call()", 1,
                false, OperationType.UNKNOWN, "", false, true);
        String label = node.displayLabel();
        assertTrue(label.startsWith("[DYNAMIC - cannot trace]"),
                "dynamic label must start with [DYNAMIC - cannot trace]: " + label);
    }

    @Test
    void withoutPsi_stripsMethodRef() {
        CallChainNode original = node("UserRepository", "findById(Long)", 1,
                true, OperationType.READ, "User", false, false);
        CallChainNode stripped = original.withoutPsi();

        assertNull(stripped.psiMethod());
        assertEquals(original.className(), stripped.className());
        assertEquals(original.methodSignature(), stripped.methodSignature());
        assertEquals(original.depth(), stripped.depth());
        assertEquals(original.isRepository(), stripped.isRepository());
        assertEquals(original.operationType(), stripped.operationType());
        assertEquals(original.entityName(), stripped.entityName());
    }

    private static CallChainNode node(String className, String sig, int depth,
                                       boolean isRepo, OperationType opType, String entity,
                                       boolean isTx, boolean isDynamic) {
        return new CallChainNode(className, sig, depth, isRepo, opType, entity, isTx, isDynamic, null, null);
    }
}
