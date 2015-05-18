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
package org.apache.felix.http.base.internal.runtime.dto;

import static java.util.Arrays.asList;

import org.apache.felix.http.base.internal.handler.FilterHandler;
import org.apache.felix.http.base.internal.registry.HandlerRegistry;
import org.apache.felix.http.base.internal.registry.PathResolution;
import org.apache.felix.http.base.internal.runtime.dto.state.ServletState;
import org.osgi.service.http.runtime.dto.FilterDTO;
import org.osgi.service.http.runtime.dto.RequestInfoDTO;

public final class RequestInfoDTOBuilder
{
    private static final FilterDTO[] FILTER_DTO_ARRAY = new FilterDTO[0];

    private final HandlerRegistry registry;
    private final String path;

    public RequestInfoDTOBuilder(final HandlerRegistry registry, final String path)
    {
        this.registry = registry;
        this.path = path;
    }

    public RequestInfoDTO build()
    {
        final RequestInfoDTO requestInfoDTO = new RequestInfoDTO();
        requestInfoDTO.path = path;

        final PathResolution pr = registry.resolveServlet(path);
        if ( pr == null )
        {
            // no servlet found, return empty DTO
            requestInfoDTO.filterDTOs = FILTER_DTO_ARRAY;
            return requestInfoDTO;
        }
        requestInfoDTO.servletContextId = pr.handler.getContextServiceId();
        if (pr.handler.getServletInfo().isResource())
        {
            final ServletState state = new ServletState(pr.handler);
            state.setPatterns(pr.handler.getServletInfo().getPatterns());
            requestInfoDTO.resourceDTO = ResourceDTOBuilder.create()
                    .buildDTO(state,
                            pr.handler.getContextServiceId());
        }
        else
        {
            final ServletState state = new ServletState(pr.handler);
            state.setPatterns(pr.handler.getServletInfo().getPatterns());
            requestInfoDTO.servletDTO = ServletDTOBuilder.create()
                    .buildDTO(state, pr.handler.getContextServiceId());
        }

        final FilterHandler[] filterHandlers = registry.getFilters(pr, null, path);
        requestInfoDTO.filterDTOs = FilterDTOBuilder.create()
                .build(asList(filterHandlers), pr.handler.getContextServiceId())
                .toArray(FILTER_DTO_ARRAY);

        return requestInfoDTO;
    }
}
