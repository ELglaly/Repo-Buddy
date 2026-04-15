package com.repoinspector.agent.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repoinspector.agent.dto.ExecutionRequest;
import com.repoinspector.agent.dto.ExecutionResult;
import com.repoinspector.agent.service.RepoExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server for the RepoBuddy agent.
 *
 * <p>Binds to port 0 so the OS assigns a free port automatically — port
 * conflicts with Tomcat or any other service are impossible by design.
 * The actual port is written to {@value #PORT_FILE_NAME} in the system
 * temp directory so the IntelliJ plugin can discover it without any
 * configuration.
 *
 * <p>This server uses Java's built-in {@link HttpServer}, completely
 * independent of Tomcat and Spring Security.  No authentication filter,
 * JWT validator, or OAuth2 resource-server can ever intercept requests
 * to it.
 */
public class RepoBuddyAgentServer implements InitializingBean, DisposableBean {

    /** Temp-file name written by the agent and read by the plugin. */
    public static final String PORT_FILE_NAME = "repoBuddy-agent.port";

    private final RepoExecutionService service;
    private final ObjectMapper mapper;
    private HttpServer server;
    private Path portFile;

    public RepoBuddyAgentServer(RepoExecutionService service) {
        this.service = service;
        this.mapper  = new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.findAndRegisterModules();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // Port 0 → OS picks any free port; impossible to clash with Tomcat.
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repoinspector/execute", this::handleExecute);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "repoBuddy-agent");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        int actualPort = ((InetSocketAddress) server.getAddress()).getPort();

        // Advertise the port so the IntelliJ plugin can connect without configuration.
        portFile = Path.of(System.getProperty("java.io.tmpdir"), PORT_FILE_NAME);
        Files.writeString(portFile, String.valueOf(actualPort),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("[RepoBuddy] Agent listening on http://localhost:"
                + actualPort + "/repoinspector/execute");
        System.out.println("[RepoBuddy] Port advertised via: " + portFile);
    }

    @Override
    public void destroy() {
        if (server != null) server.stop(0);
        if (portFile != null) {
            try { Files.deleteIfExists(portFile); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------

    private void handleExecute(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            byte[] body          = exchange.getRequestBody().readAllBytes();
            ExecutionRequest req = mapper.readValue(body, ExecutionRequest.class);
            ExecutionResult result = service.execute(req);

            byte[] response = mapper.writeValueAsBytes(result);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Exception ex) {
            try {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                byte[] response = ("{\"status\":\"ERROR\",\"exception\":\""
                        + msg.replace("\"", "'") + "\"}").getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) {
        try {
            byte[] response = message.getBytes();
            exchange.sendResponseHeaders(code, response.length);
            exchange.getResponseBody().write(response);
        } catch (Exception ignored) {
        } finally {
            exchange.close();
        }
    }
}
