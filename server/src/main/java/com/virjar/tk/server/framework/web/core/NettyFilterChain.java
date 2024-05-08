package com.virjar.tk.server.framework.web.core;

import jakarta.servlet.*;

import java.io.IOException;
import java.util.Iterator;

import static com.google.common.base.Preconditions.checkNotNull;


public class NettyFilterChain implements FilterChain {

    private final Iterator<Filter> filterIterator;
    private final Servlet servlet;

    public NettyFilterChain(Servlet servlet, Iterable<Filter> filters) throws ServletException {
        this.filterIterator = checkNotNull(filters).iterator();
        this.servlet = checkNotNull(servlet);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
        if (filterIterator.hasNext()) {
            filterIterator.next().doFilter(request, response, this);
        } else {
            servlet.service(request, response);
        }
    }
}
