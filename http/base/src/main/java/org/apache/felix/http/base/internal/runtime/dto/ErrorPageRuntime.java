/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.http.base.internal.runtime.dto;

import java.util.Collection;

import javax.servlet.Servlet;

import org.apache.felix.http.base.internal.runtime.ServletInfo;

public class ErrorPageRuntime implements ServletRuntime
{
    private final ServletRuntime servletRuntime;
    private final Collection<Long> errorCodes;
    private final Collection<String> exceptions;

    public ErrorPageRuntime(ServletRuntime servletRuntime,
            Collection<Long> errorCodes,
            Collection<String> exceptions)
    {
        this.servletRuntime = servletRuntime;
        this.errorCodes = errorCodes;
        this.exceptions = exceptions;
    }

    public Collection<Long> getErrorCodes()
    {
        return errorCodes;
    }

    public Collection<String> getExceptions()
    {
        return exceptions;
    }

    @Override
    public long getContextServiceId()
    {
        return servletRuntime.getContextServiceId();
    }

    @Override
    public Servlet getServlet()
    {
        return servletRuntime.getServlet();
    }

    @Override
    public ServletInfo getServletInfo()
    {
        return servletRuntime.getServletInfo();
    }
}