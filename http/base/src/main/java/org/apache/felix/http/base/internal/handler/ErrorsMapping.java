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

import static org.apache.felix.http.base.internal.util.CollectionUtils.sortedUnion;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.felix.http.base.internal.util.ErrorPageUtil;
import org.apache.felix.http.base.internal.whiteboard.RegistrationFailureException;
import org.osgi.service.http.runtime.dto.DTOConstants;

public final class ErrorsMapping
{
    private final Map<Integer, ServletHandler> errorCodesMap;
    private final Map<String, ServletHandler> exceptionsMap;

    ErrorsMapping()
    {
        this(new HashMap<Integer, ServletHandler>(), new HashMap<String, ServletHandler>());
    }

    ErrorsMapping(Map<Integer, ServletHandler> errorCodesMap, Map<String, ServletHandler> exceptionsMap)
    {
        this.errorCodesMap = errorCodesMap;
        this.exceptionsMap = exceptionsMap;
    }

    ErrorsMapping update(Map<String, ServletHandler> add, Map<String, ServletHandler> remove) throws RegistrationFailureException
    {
        Map<Integer, ServletHandler> newErrorCodesMap = new HashMap<Integer, ServletHandler>(this.errorCodesMap);
        Map<String, ServletHandler> newExceptionsMap = new HashMap<String, ServletHandler>(this.exceptionsMap);;

        for (Map.Entry<String, ServletHandler> errorPage : remove.entrySet())
        {
            String errorString = errorPage.getKey();
            if (ErrorPageUtil.isErrorCode(errorString))
            {
                Integer errorCode = Integer.valueOf(errorString);
                newErrorCodesMap.remove(errorCode);
            }
            else
            {
                newExceptionsMap.remove(errorString);
            }
        }

        for (Map.Entry<String, ServletHandler> errorPage : add.entrySet())
        {
            String errorString = errorPage.getKey();
            if (ErrorPageUtil.isErrorCode(errorString))
            {
                Integer errorCode = Integer.valueOf(errorString);
                addErrorServlet(errorCode, errorPage.getValue(), newErrorCodesMap);
            }
            else
            {
                addErrorServlet(errorString, errorPage.getValue(), newExceptionsMap);
            }
        }

        return new ErrorsMapping(newErrorCodesMap, newExceptionsMap);
    }

    private <E> void addErrorServlet(E error, ServletHandler handler, Map<E, ServletHandler> index) throws RegistrationFailureException
    {
        if (index.containsKey(error))
        {
            throw new RegistrationFailureException(handler.getServletInfo(), DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE,
                "Handler for error " + error + " already registered");
        }
        index.put(error, handler);
    }

    void clear()
    {
        this.errorCodesMap.clear();
        this.exceptionsMap.clear();
    }


    public ServletHandler get(int errorCode)
    {
        return this.errorCodesMap.get(errorCode);
    }

    public ServletHandler get(String exception)
    {
        return this.exceptionsMap.get(exception);
    }


    @SuppressWarnings("unchecked")
    Collection<ServletHandler> values()
    {
        return sortedUnion(errorCodesMap.values(), exceptionsMap.values());
    }
}
