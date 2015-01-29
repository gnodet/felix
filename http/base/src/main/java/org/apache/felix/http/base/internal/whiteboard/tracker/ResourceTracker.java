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
package org.apache.felix.http.base.internal.whiteboard.tracker;

import org.apache.felix.http.base.internal.whiteboard.ExtenderManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

public final class ResourceTracker extends AbstractTracker<Object>
{
    private final ExtenderManager manager;

    private static Filter createFilter(final BundleContext btx)
    {
        try
        {
            return btx.createFilter(String.format("(&(%s=*)(%s=*))",
                    HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PATTERN,
                    HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PREFIX));
        }
        catch ( final InvalidSyntaxException ise)
        {
            // we can safely ignore it as the above filter is a constant
        }
        return null; // we never get here - and if we get an NPE which is fine
    }

    public ResourceTracker(final BundleContext context, final ExtenderManager manager)
    {
        super(context, createFilter(context));
        this.manager = manager;
    }

    @Override
    protected void added(final Object service, final ServiceReference ref)
    {
        this.manager.addResource(ref);
    }

    @Override
    protected void modified(final Object service, final ServiceReference ref)
    {
        removed(service, ref);
        added(service, ref);
    }

    @Override
    protected void removed(final Object service, final ServiceReference ref)
    {
        this.manager.removeResource(ref);
    }
}
