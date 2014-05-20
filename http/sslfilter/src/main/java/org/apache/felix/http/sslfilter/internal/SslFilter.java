/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.http.sslfilter.internal;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Dictionary;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.log.LogService;

public class SslFilter implements Filter
{
    public static final String PID = "org.apache.felix.http.sslfilter.SslFilter";

    private static final String DEFAULT_SSL_HEADER = "X-Forwarded-SSL";
    private static final String DEFAULT_SSL_VALUE = "on";
    private static final String DEFAULT_CERT_HEADER = "X-Forwarded-SSL-Certificate";

    private static final String PROP_SSL_HEADER = "ssl-forward.header";
    private static final String PROP_SSL_VALUE = "ssl-forward.value";
    private static final String PROP_SSL_CERT_KEY = "ssl-forward-cert.header";

    private volatile ConfigHolder config;

    SslFilter()
    {
        this.config = new ConfigHolder(DEFAULT_SSL_HEADER, DEFAULT_SSL_VALUE, DEFAULT_CERT_HEADER);
    }

    public void destroy()
    {
        // No explicit destroy needed...
    }

    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException
    {
        final ConfigHolder cfg = this.config;

        HttpServletRequest httpReq = (HttpServletRequest) req;
        if (cfg.sslValue.equalsIgnoreCase(httpReq.getHeader(cfg.sslHeader)))
        {
            try
            {
                // In case this fails, we fall back to the original HTTP
                // request, which is better than nothing...
                httpReq = new SslFilterRequest(httpReq, httpReq.getHeader(cfg.certHeader));
            }
            catch (CertificateException e)
            {
                SystemLogger.log(LogService.LOG_WARNING, "Failed to create SSL filter request! Problem parsing client certificates?! Client certificate will *not* be forwarded...", e);
            }
        }

        // forward the request making sure any certificate is removed
        // again after the request processing gets back here
        try
        {
            chain.doFilter(httpReq, res);
        }
        finally
        {
            if (httpReq instanceof SslFilterRequest)
            {
                ((SslFilterRequest) httpReq).done();
            }
        }
    }

    public void init(FilterConfig config)
    {
        // make sure there is some configuration
    }

    void configure(@SuppressWarnings("rawtypes") final Dictionary properties) throws ConfigurationException
    {
        String certHeader = DEFAULT_CERT_HEADER;
        String sslHeader = DEFAULT_SSL_HEADER;
        String sslValue = DEFAULT_SSL_VALUE;

        if (properties != null)
        {
            certHeader = getOptionalString(properties, PROP_SSL_CERT_KEY);
            sslHeader = getMandatoryString(properties, PROP_SSL_HEADER);
            sslValue = getMandatoryString(properties, PROP_SSL_VALUE);
        }

        this.config = new ConfigHolder(sslHeader, sslValue, certHeader);

        SystemLogger.log(LogService.LOG_INFO, "SSL filter (re)configured with: " + "SSL forward header = '" + sslHeader + "'; SSL forward value = '" + sslValue + "'; SSL certificate header = '"
            + certHeader + "'.");
    }

    private String getOptionalString(@SuppressWarnings("rawtypes") Dictionary properties, String key) throws ConfigurationException
    {
        Object raw = properties.get(key);
        if (raw == null || "".equals(((String) raw).trim()))
        {
            return null;
        }
        if (!(raw instanceof String))
        {
            throw new ConfigurationException(key, "invalid value");
        }
        return ((String) raw).trim();
    }

    private String getMandatoryString(@SuppressWarnings("rawtypes") Dictionary properties, String key) throws ConfigurationException
    {
        String value = getOptionalString(properties, key);
        if (value == null)
        {
            throw new ConfigurationException(key, "missing value");
        }
        return value;
    }

    static class ConfigHolder
    {
        final String certHeader;
        final String sslHeader;
        final String sslValue;

        public ConfigHolder(String sslHeader, String sslValue, String certHeader)
        {
            this.sslHeader = sslHeader;
            this.sslValue = sslValue;
            this.certHeader = certHeader;
        }
    }
}
