package io.github.mosadie.mcsde.core.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.mosadie.mcsde.core.MCSDECore;
import io.github.mosadie.mcsde.core.Util;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

public class JoinServerHandler implements HttpHandler {

    private final MCSDECore core;

    public JoinServerHandler(MCSDECore core) {
        this.core = core;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        core.getExecutor().log("JoinServer started");
        Map<String, Object> parameters = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        try {
            Util.parseQuery(query, parameters);
        } catch (UnsupportedEncodingException e) {
            core.getExecutor().log("Exception occurred parsing query!");
            parameters = new HashMap<>();
        }
        if (!Util.trustCheck(parameters, exchange, core))
            return;

        if (parameters.containsKey("serverip")) {
            core.getExecutor().log("Joining server");
            String response = "Joining server";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();
           core.getExecutor().joinServer(parameters.get("serverip").toString());
        } else {
            core.getExecutor().log("JoinServer failed");
            String response = "Server not defined";
            exchange.sendResponseHeaders(400, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();
        }


    }
}
