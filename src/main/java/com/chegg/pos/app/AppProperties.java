package com.chegg.pos.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/** Typed access to application.properties values. */
@Component
public class AppProperties {

    @Value("${app.name:Chegg POS}")
    private String name;

    @Value("${app.version:1.0.0}")
    private String version;

    @Value("${app.terminalId:}")
    private String terminalId;

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /**
     * Terminal identity used for shift isolation. Configured value wins,
     * otherwise COMPUTERNAME / HOSTNAME, otherwise the local hostname.
     */
    public String resolvedTerminalId() {
        if (terminalId != null && !terminalId.isBlank()) {
            return terminalId.trim();
        }
        String computerName = System.getenv("COMPUTERNAME");
        if (computerName != null && !computerName.isBlank()) {
            return computerName.trim();
        }
        String hostName = System.getenv("HOSTNAME");
        if (hostName != null && !hostName.isBlank()) {
            return hostName.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "TERMINAL-1";
        }
    }
}
