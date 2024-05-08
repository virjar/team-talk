package com.virjar.tk.server.framework.web.servlet;

import java.io.IOException;

public class ClientAbortException extends IOException {
    public ClientAbortException(String message) {
        super(message);
    }
}
