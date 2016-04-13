<?xml version="1.0" encoding="UTF-8"?>
<!--

    Licensed to the Apache Software Foundation (ASF) under one or more
    contributor license agreements.  See the NOTICE file distributed with
    this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with
    the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

-->
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:jpa="http://java.sun.com/xml/ns/persistence">

    <xsl:output method="text" />

    <!-- Transformer properties -->
    <xsl:param name="jpa-enable"/>
    <xsl:param name="jpa-implementation"/>

    <xsl:variable name="nl">
        <xsl:text>&#xD;</xsl:text>
    </xsl:variable>

    <xsl:template match="/">
        <xsl:if test="$jpa-enable = 'true'">

            <xsl:for-each select="//jpa:persistence-unit">
                <xsl:variable name="attrs" select="concat('osgi.unit.name=&quot;', @name, '&quot;')"/>
                <!-- Standard interfaces -->
                <xsl:call-template name="service-capability">
                    <xsl:with-param name="interface" select="'javax.persistence.EntityManager'"/>
                    <xsl:with-param name="attributes" select="$attrs"/>
                </xsl:call-template>
                <xsl:call-template name="service-capability">
                    <xsl:with-param name="interface" select="'javax.persistence.EntityManagerFactory'"/>
                    <xsl:with-param name="attributes" select="$attrs"/>
                </xsl:call-template>
                <!-- Aries JPA specific interfaces -->
                <xsl:if test="$jpa-implementation = 'aries'">
                    <xsl:call-template name="service-capability">
                        <xsl:with-param name="interface" select="'org.apache.aries.jpa.template.JpaTemplate'"/>
                        <xsl:with-param name="attributes" select="$attrs"/>
                    </xsl:call-template>
                    <xsl:call-template name="service-capability">
                        <xsl:with-param name="interface" select="'org.apache.aries.jpa.supplier.EmSupplier'"/>
                        <xsl:with-param name="attributes" select="$attrs"/>
                    </xsl:call-template>
                </xsl:if>
            </xsl:for-each>

            <xsl:if test="$jpa-implementation = 'aries'">
                <xsl:text>
                    Require-Capability: osgi.extender;osgi.extender=aries.jpa
                </xsl:text>
            </xsl:if>

            <!-- Service requirement for the provider -->
            <xsl:for-each select="//jpa:persistence-unit/jpa:provider">
                <xsl:call-template name="service-requirement">
                    <xsl:with-param name="interface" select="'javax.persistence.spi.PersistenceProvider'"/>
                    <xsl:with-param name="attributes" select="concat('javax.persistence.provider=', text())"/>
                </xsl:call-template>
            </xsl:for-each>

            <xsl:if test="//jpa:persistence-unit/jpa:provider[text()='org.hibernate.jpa.HibernatePersistenceProvider']">
                <xsl:text>
                    Import-Package: org.hibernate.proxy;javassist.util.proxy;resolution:=optional
                    DynamicImport-Package: org.hibernate.proxy;javassist.util.proxy
                </xsl:text>
            </xsl:if>

            <xsl:if test="//jpa:persistence-unit[@transaction-type='JTA']">
                <xsl:call-template name="service-requirement">
                    <xsl:with-param name="interface" select="'javax.transaction.TransactionManager'"/>
                </xsl:call-template>
            </xsl:if>
        </xsl:if>
    </xsl:template>

    <xsl:template name="service-capability">
        <xsl:param name="interface"/>
        <xsl:param name="attributes" select="''"/>
        <xsl:value-of select="concat('Provide-Capability: osgi.service;effective:=active;',
                                         'objectClass=', $interface, ';',
                                         $attributes,
                                         $nl)"/>
    </xsl:template>

    <xsl:template name="service-requirement">
        <xsl:param name="interface"/>
        <xsl:param name="attributes" select="''"/>
        <xsl:value-of select="concat('Require-Capability: osgi.service;effective:=active;',
                                         'objectClass=', $interface, ';',
                                         $attributes,
                                         $nl)"/>
    </xsl:template>

</xsl:stylesheet>

