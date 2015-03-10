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
package org.apache.felix.http.base.internal;

import java.util.Hashtable;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.apache.felix.http.base.internal.console.HttpServicePlugin;
import org.apache.felix.http.base.internal.dispatch.Dispatcher;
import org.apache.felix.http.base.internal.handler.HandlerRegistry;
import org.apache.felix.http.base.internal.handler.HttpSessionWrapper;
import org.apache.felix.http.base.internal.service.HttpServiceFactory;
import org.apache.felix.http.base.internal.service.listener.ServletContextAttributeListenerManager;
import org.apache.felix.http.base.internal.whiteboard.WhiteboardManager;
import org.osgi.framework.BundleContext;

public final class HttpServiceController
{
    private final BundleContext bundleContext;
    private final HandlerRegistry registry;
    private final Dispatcher dispatcher;
    private final HttpServicePlugin plugin;
    private final HttpServiceFactory httpServiceFactory;
    private final WhiteboardManager whiteboardManager;

    private volatile HttpSessionListener httpSessionListener;

    public HttpServiceController(final BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;
        this.registry = new HandlerRegistry(this.bundleContext);
        this.dispatcher = new Dispatcher(this.registry);
        this.plugin = new HttpServicePlugin(bundleContext, registry);
        this.httpServiceFactory = new HttpServiceFactory(this.bundleContext, this.registry);
        this.whiteboardManager = new WhiteboardManager(bundleContext, this.httpServiceFactory, this.registry);
    }

    Dispatcher getDispatcher()
    {
        return this.dispatcher;
    }

    ServletContextAttributeListenerManager getContextAttributeListener()
    {
        return this.httpServiceFactory.getContextAttributeListener();
    }

    HttpSessionListener getSessionListener()
    {
        // we don't need to sync here, if the object gets created several times
        // its not a problem
        if ( httpSessionListener == null )
        {
            httpSessionListener = new HttpSessionListener() {

                @Override
                public void sessionDestroyed(final HttpSessionEvent se) {
                    httpServiceFactory.getSessionListener().sessionDestroyed(se);
                    whiteboardManager.sessionDestroyed(se.getSession(), HttpSessionWrapper.getSessionContextIds(se.getSession()));
                }

                @Override
                public void sessionCreated(final HttpSessionEvent se) {
                    httpServiceFactory.getSessionListener().sessionCreated(se);
                }
            };
        }
        return httpSessionListener;
    }

    HttpSessionAttributeListener getSessionAttributeListener()
    {
        return httpServiceFactory.getSessionAttributeListener();
    }

    HttpSessionIdListener getSessionIdListener()
    {
        return new HttpSessionIdListener() {

            @Override
            public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
                // TODO Auto-generated method stub

            }
        };
    }
    public void setProperties(final Hashtable<String, Object> props)
    {
        this.httpServiceFactory.setProperties(props);
        this.whiteboardManager.setProperties(props);
    }

    public void register(final ServletContext servletContext)
    {
        this.registry.init();

        this.plugin.register();

        this.httpServiceFactory.start(servletContext);
        this.whiteboardManager.start(servletContext);

        this.dispatcher.setWhiteboardManager(this.whiteboardManager);
    }

    public void unregister()
    {
        this.plugin.unregister();

        this.dispatcher.setWhiteboardManager(null);

        if ( this.whiteboardManager != null )
        {
            this.whiteboardManager.stop();
        }

        if ( this.httpServiceFactory != null )
        {
            this.httpServiceFactory.stop();
        }

        this.registry.shutdown();
        this.httpSessionListener = null;
    }
}
