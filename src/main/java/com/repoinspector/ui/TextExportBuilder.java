package com.repoinspector.ui;

import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Formats a call-chain analysis result as plain text for clipboard export.
 *
 * <p>Extracted from {@link CallChainPanel} to satisfy SRP: the panel is
 * responsible for <em>displaying</em> results; this class is responsible for
 * <em>serialising</em> them to text.  Changing the export format requires
 * touching only this class.
 */
public final class TextExportBuilder {

    private static final int SEPARATOR_LEN = 60;
    private static final String SEPARATOR  = "\u2500".repeat(SEPARATOR_LEN);

    private TextExportBuilder() {}

    /**
     * Builds the plain-text export string for the given endpoint and its call chain.
     *
     * @param endpoint the traced endpoint (header info)
     * @param nodes    flat ordered list of call-chain nodes
     * @return formatted multi-line string ready for the clipboard
     */
    public static String build(EndpointInfo endpoint, List<CallChainNode> nodes) {
        StringBuilder sb = new StringBuilder();

        appendHeader(sb, endpoint);
        appendCallChain(sb, nodes);
        appendRepositorySummary(sb, nodes);

        return sb.toString();
    }

    // -------------------------------------------------------------------------

    private static void appendHeader(StringBuilder sb, EndpointInfo endpoint) {
        sb.append("API Endpoint : ").append(endpoint.httpMethod())
          .append(' ').append(endpoint.path())
          .append("  \u2192  ")
          .append(endpoint.controllerName()).append('.').append(endpoint.methodSignature())
          .append('\n');
        sb.append("Generated    : ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
          .append('\n');
        sb.append(SEPARATOR).append('\n');
    }

    private static void appendCallChain(StringBuilder sb, List<CallChainNode> nodes) {
        for (CallChainNode node : nodes) {
            if (node.depth() == 0) continue;   // endpoint row is already in the header
            sb.append("  ".repeat(node.depth()))
              .append(node.displayLabel())
              .append("  [depth=").append(node.depth()).append(']')
              .append('\n');
        }
    }

    private static void appendRepositorySummary(StringBuilder sb, List<CallChainNode> nodes) {
        List<CallChainNode> repoNodes = nodes.stream()
                .filter(CallChainNode::isRepository)
                .toList();

        if (repoNodes.isEmpty()) return;

        sb.append(SEPARATOR).append('\n');
        sb.append("Repository Methods Summary:\n");

        for (CallChainNode repo : repoNodes) {
            sb.append("  - ")
              .append(repo.className()).append('.').append(repo.methodSignature())
              .append("  \u2192  ").append(repo.operationType().name());
            if (!repo.entityName().isEmpty()) {
                sb.append("  (").append(repo.entityName()).append(')');
            }
            sb.append('\n');
        }
    }
}
