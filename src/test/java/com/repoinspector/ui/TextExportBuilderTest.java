package com.repoinspector.ui;

import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextExportBuilderTest {

    private static final EndpointInfo ENDPOINT = new EndpointInfo(
            "GET", "/api/users", "UserController", "getUsers()",
            "com.example.UserController", null);

    @Test
    void build_headerContainsEndpointInfo() {
        String result = TextExportBuilder.build(ENDPOINT, List.of());
        assertTrue(result.startsWith("API Endpoint : GET /api/users"),
                "output must start with endpoint header: " + result);
        assertTrue(result.contains("UserController.getUsers()"));
    }

    @Test
    void build_emptyNodes_noRepositorySummarySection() {
        String result = TextExportBuilder.build(ENDPOINT, List.of());
        assertFalse(result.contains("Repository Methods Summary"),
                "no repo nodes means no summary section");
    }

    @Test
    void build_depth0NodeSkippedInCallChainSection() {
        CallChainNode epNode = node("UserController", "getUsers()", 0, false, OperationType.UNKNOWN, "");
        String result = TextExportBuilder.build(ENDPOINT, List.of(epNode));
        // depth-0 node is the endpoint itself, already in header — not repeated
        long occurrences = result.lines()
                .filter(l -> l.contains("UserController.getUsers()"))
                .count();
        assertEquals(1, occurrences, "depth-0 node must appear only in header, not repeated");
    }

    @Test
    void build_serviceNode_indentedByDepth() {
        CallChainNode serviceNode = node("UserService", "getUser(Long)", 1, false, OperationType.UNKNOWN, "");
        String result = TextExportBuilder.build(ENDPOINT, List.of(serviceNode));
        assertTrue(result.contains("  UserService.getUser(Long)"),
                "depth-1 node must be indented by 2 spaces: " + result);
    }

    @Test
    void build_repoNode_appearsInSummary() {
        CallChainNode repoNode = node("UserRepository", "findById(Long)", 2, true, OperationType.READ, "");
        String result = TextExportBuilder.build(ENDPOINT, List.of(repoNode));
        assertTrue(result.contains("Repository Methods Summary"), "must contain summary section");
        assertTrue(result.contains("UserRepository.findById(Long)"), "repo method must appear in summary");
        assertTrue(result.contains("READ"), "operation type must appear in summary");
    }

    @Test
    void build_repoNode_withEntityName_appearsInSummary() {
        CallChainNode repoNode = node("UserRepository", "save(User)", 2, true, OperationType.WRITE, "User");
        String result = TextExportBuilder.build(ENDPOINT, List.of(repoNode));
        assertTrue(result.contains("(User)"), "entity name must appear in summary: " + result);
    }

    @Test
    void build_mixedNodes_onlyRepoNodesInSummary() {
        CallChainNode service = node("UserService", "getUser(Long)", 1, false, OperationType.UNKNOWN, "");
        CallChainNode repo = node("UserRepository", "findById(Long)", 2, true, OperationType.READ, "");
        String result = TextExportBuilder.build(ENDPOINT, List.of(service, repo));
        assertTrue(result.contains("Repository Methods Summary"));
        assertFalse(result.contains("  - UserService"),
                "non-repo nodes must not appear in summary");
    }

    private static CallChainNode node(String cls, String sig, int depth,
                                       boolean isRepo, OperationType op, String entity) {
        return new CallChainNode(cls, sig, depth, isRepo, op, entity, false, false, null, null);
    }
}
