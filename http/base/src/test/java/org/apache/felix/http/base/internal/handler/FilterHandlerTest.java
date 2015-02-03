/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.http.base.internal.handler;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static javax.servlet.http.HttpServletResponse.SC_PAYMENT_REQUIRED;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.http.base.internal.runtime.FilterInfo;
import org.junit.Before;
import org.junit.Test;

public class FilterHandlerTest extends AbstractHandlerTest
{
    private Filter filter;

    @Override
    @Before
    public void setUp()
    {
        super.setUp();
        this.filter = mock(Filter.class);
    }

    @Test
    public void testCompare()
    {
        FilterHandler h1 = createHandler(0, "a");
        FilterHandler h2 = createHandler(10, "b");
        FilterHandler h3 = createHandler(10, "c");

        assertEquals(0, h1.compareTo(h1));

        assertEquals(1, h1.compareTo(h2));
        assertEquals(-1, h2.compareTo(h1));

        // h2 is actually registered first, so should be called first...
        assertEquals(-1, h2.compareTo(h3));
        assertEquals(1, h3.compareTo(h2));
    }

    @Test
    public void testDestroy()
    {
        FilterHandler h1 = createHandler(0, "/a");
        h1.destroy();
        verify(this.filter).destroy();
    }

    @Test
    public void testHandleFound() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/a");
        HttpServletRequest req = createServletRequest();
        HttpServletResponse res = createServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(this.context.handleSecurity(req, res)).thenReturn(true);

        when(req.getRequestURI()).thenReturn("/a");
        h1.handle(req, res, chain);

        verify(this.filter).doFilter(req, res, chain);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    public void testHandleFoundContextRoot() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/");
        HttpServletRequest req = createServletRequest();
        HttpServletResponse res = createServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(this.context.handleSecurity(req, res)).thenReturn(true);

        when(req.getRequestURI()).thenReturn(null);
        h1.handle(req, res, chain);

        verify(this.filter).doFilter(req, res, chain);
        verify(chain, never()).doFilter(req, res);
    }

    /**
     * FELIX-3988: only send an error for uncomitted responses with default status codes.
     */
    @Test
    public void testHandleFoundForbidden() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/a");
        HttpServletRequest req = createServletRequest();
        HttpServletResponse res = createServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(req.getRequestURI()).thenReturn("/a");
        // Default behaviour: uncomitted response and default status code...
        when(res.isCommitted()).thenReturn(false);
        when(res.getStatus()).thenReturn(SC_OK);

        when(this.context.handleSecurity(req, res)).thenReturn(false);

        h1.handle(req, res, chain);

        verify(this.filter, never()).doFilter(req, res, chain);
        verify(chain, never()).doFilter(req, res);
        verify(res).sendError(SC_FORBIDDEN);
    }

    /**
     * FELIX-3988: do not try to write to an already committed response.
     */
    @Test
    public void testHandleFoundForbiddenCommittedOwnResponse() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/a");
        HttpServletRequest req = createServletRequest();
        HttpServletResponse res = createServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(req.getRequestURI()).thenReturn("/a");
        // Simulate an already committed response...
        when(res.isCommitted()).thenReturn(true);
        when(res.getStatus()).thenReturn(SC_OK);

        when(this.context.handleSecurity(req, res)).thenReturn(false);

        h1.handle(req, res, chain);

        verify(this.filter, never()).doFilter(req, res, chain);
        verify(chain, never()).doFilter(req, res);
        // Should not be called from our handler...
        verify(res, never()).sendError(SC_FORBIDDEN);
    }

    /**
     * FELIX-3988: do not overwrite custom set status code.
     */
    @Test
    public void testHandleFoundForbiddenCustomStatusCode() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/a");
        HttpServletRequest req = createServletRequest();
        HttpServletResponse res = createServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(req.getRequestURI()).thenReturn("/a");
        // Simulate an uncommitted response with a non-default status code...
        when(res.isCommitted()).thenReturn(false);
        when(res.getStatus()).thenReturn(SC_PAYMENT_REQUIRED);

        when(this.context.handleSecurity(req, res)).thenReturn(false);

        h1.handle(req, res, chain);

        verify(this.filter, never()).doFilter(req, res, chain);
        verify(chain, never()).doFilter(req, res);
        // Should not be called from our handler...
        verify(res, never()).sendError(SC_FORBIDDEN);
    }

    @Test
    public void testHandleNotFound() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/a");
        HttpServletRequest req = createServletRequest();
        HttpServletResponse res = createServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(req.getRequestURI()).thenReturn("/");
        h1.handle(req, res, chain);

        verify(this.filter, never()).doFilter(req, res, chain);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    public void testHandleNotFoundContextRoot() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/a");
        HttpServletRequest req = createServletRequest();
        HttpServletResponse res = createServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(req.getRequestURI()).thenReturn(null);
        h1.handle(req, res, chain);

        verify(this.filter, never()).doFilter(req, res, chain);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    public void testInit() throws Exception
    {
        FilterHandler h1 = createHandler(0, "/a");
        h1.init();
        verify(this.filter).init(any(FilterConfig.class));
    }

   @Override
    protected AbstractHandler createHandler()
    {
        return createHandler(0, "dummy");
    }

    @Override
    protected AbstractHandler createHandler(final Map<String, String> initParams)
    {
        return createHandler("dummy", 0, initParams);
    }

    private FilterHandler createHandler(int ranking, String pattern)
    {
        return createHandler(pattern, ranking, null);
    }

    private FilterHandler createHandler(String pattern, int ranking, Map<String, String> initParams)
    {
        if ( initParams == null )
        {
            initParams = Collections.emptyMap();
        }
        final FilterInfo info = new FilterInfo(null, pattern, ranking, initParams);
        return new FilterHandler(null, this.context, this.filter, info);
    }

    private HttpServletRequest createServletRequest()
    {
        return createServletRequest(DispatcherType.REQUEST);
    }

    private HttpServletRequest createServletRequest(DispatcherType type)
    {
        HttpServletRequest result = mock(HttpServletRequest.class);
        when(result.getDispatcherType()).thenReturn(type);
        return result;
    }

    private HttpServletResponse createServletResponse()
    {
        return mock(HttpServletResponse.class);
    }
}
