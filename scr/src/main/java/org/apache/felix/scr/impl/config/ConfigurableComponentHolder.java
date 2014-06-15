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
package org.apache.felix.scr.impl.config;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.felix.scr.impl.Activator;
import org.apache.felix.scr.impl.BundleComponentActivator;
import org.apache.felix.scr.impl.TargetedPID;
import org.apache.felix.scr.impl.helper.ComponentMethods;
import org.apache.felix.scr.impl.helper.SimpleLogger;
import org.apache.felix.scr.impl.manager.AbstractComponentManager;
import org.apache.felix.scr.impl.manager.SingleComponentManager;
import org.apache.felix.scr.impl.manager.ServiceFactoryComponentManager;
import org.apache.felix.scr.impl.metadata.ComponentMetadata;
import org.apache.felix.scr.impl.metadata.ServiceMetadata.Scope;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.log.LogService;


/**
 * The <code>ImmediateComponentHolder</code> class is a
 * {@link ComponentHolder} for automatically configured components instances 
 * that may or may not be configured through Config Admin.
 * <p>
 * The holder copes with three situations:
 * <ul>
 * <li>No configuration is available for the held component. That is there is
 * no configuration whose <code>service.pid</code> or
 * <code>service.factoryPid</code> equals the component name.</li>
 * <li>A singleton configuration is available whose <code>service.pid</code>
 * equals the component name.</li>
 * <li>One or more factory configurations exist whose
 * <code>service.factoryPid</code> equals the component name.</li>
 * </ul>
 */
public class ConfigurableComponentHolder<S> implements ComponentHolder<S>, ComponentContainer<S>, SimpleLogger
{

    /**
     * The activator owning the per-bundle components
     */
    private final BundleComponentActivator m_activator;

    /**
     * The {@link ComponentMetadata} describing the held component(s)
     */
    private final ComponentMetadata m_componentMetadata;

    /** the targeted pids corresponding to the pids specified in the config metadata, except possibly for the single 
     * factory pid
     */
    private final TargetedPID[] m_targetedPids;

    private final Long[] m_changeCount;

    private final Map<String, Long> m_factoryChangeCount = new HashMap<String, Long>();

    /**
     * the index in metadata.getConfigurationPid() of the base factory pid, if any.  Each component created from a factory configuration
     * might have a different targeted pid.
     */
    private Integer m_factoryPidIndex;

    /**
     * the non-factory configurations shared between all instances.
     */
    private final Dictionary<String, Object>[] m_configurations;

    /**
     * the factory configurations indexed by pid (which cannot be a TargetedPID since it's generated by CA).  We have to track these since
     * other required configs may not yet be present so we can't create the component manager yet.
     */
    private final Map<String, Dictionary<String, Object>> m_factoryConfigurations = new HashMap<String, Dictionary<String, Object>>();

    /**
     * Each factory config may be from a different TargetedPID (sharing the same base service pid, but with different level of detail)
     */
    private final Map<String, TargetedPID> m_factoryTargetedPids = new HashMap<String, TargetedPID>();
    /**
     * A map of components configured with factory configuration. The indices
     * are the PIDs (<code>service.pid</code>) of the configuration objects.
     * The values are the {@link SingleComponentManager<S> component instances}
     * created on behalf of the configurations.
     */
    private final Map<String, AbstractComponentManager<S>> m_components;

    /**
     * The special component used if there is no configuration or a singleton
     * configuration. This field is only <code>null</code> once all components
     * held by this holder have been disposed of by
     * {@link #disposeComponents(int)} and is first created in the constructor.
     * As factory configurations are provided this instance may be configured
     * or "deconfigured".
     * <p>
     * Expected invariants:
     * <ul>
     * <li>This field is only <code>null</code> after disposal of all held
     * components</li>
     * <li>The {@link #m_components} map is empty or the component pointed to
     * by this field is also contained in the map</li>
     * <ul>
     */
    private AbstractComponentManager<S> m_singleComponent;

    /**
     * Whether components have already been enabled by calling the
     * {@link #enableComponents(boolean)} method. If this field is <code>true</code>
     * component instances created per configuration by the
     * {@link #configurationUpdated(TargetedPID, TargetedPID, Dictionary, long)} method are also
     * enabled. Otherwise they are not enabled immediately.
     */
    private volatile boolean m_enabled;

    private final ComponentMethods m_componentMethods;    

    public ConfigurableComponentHolder( final BundleComponentActivator activator, final ComponentMetadata metadata )
    {
        this.m_activator = activator;
        this.m_componentMetadata = metadata;
        int pidCount = metadata.getConfigurationPid().size();
        this.m_targetedPids = new TargetedPID[pidCount];
        this.m_configurations = new Dictionary[pidCount];
        this.m_changeCount = new Long[pidCount];
        this.m_components = new HashMap<String, AbstractComponentManager<S>>();
        this.m_componentMethods = new ComponentMethods();
        this.m_enabled = false;
    }

    protected SingleComponentManager<S> createComponentManager()
    {

        SingleComponentManager<S> manager;
        if ( m_componentMetadata.isFactory() )
        {
            throw new IllegalArgumentException( "Cannot create component factory for " + m_componentMetadata.getName() );
        }
        else if ( m_componentMetadata.getServiceScope() == Scope.bundle )
        {
            manager = new ServiceFactoryComponentManager<S>( this, m_componentMethods );
        }

        else if ( m_componentMetadata.getServiceScope() == Scope.prototype )
        {
            manager = null;// NYI
            //            manager = new ServiceFactoryComponentManager<S>( m_activator, this, m_componentMetadata, m_componentMethods );
        }

        else
        {
            //immediate or delayed
            manager = new SingleComponentManager<S>( this, m_componentMethods );
        }

        return manager;
    }


    public final BundleComponentActivator getActivator()
    {
        return m_activator;
    }


    public final ComponentMetadata getComponentMetadata()
    {
        return m_componentMetadata;
    }


    /**
     * The configuration with the given <code>pid</code>
     * (<code>service.pid</code> of the configuration object) is deleted.
     * <p>
     * The following situations are supported:
     * <ul>
     * <li>The configuration was a singleton configuration (pid equals the
     * component name). In this case the internal component map is empty and
     * the single component has been configured by the singleton configuration
     * and is no "deconfigured".</li>
     * <li>A factory configuration object has been deleted and the configured
     * object is set as the single component. If the single component held the
     * last factory configuration object, it is deconfigured. Otherwise the
     * single component is disposed off and replaced by another component in
     * the map of existing components.</li>
     * <li>A factory configuration object has been deleted and the configured
     * object is not set as the single component. In this case the component is
     * simply disposed off and removed from the internal map.</li>
     * </ul>
     */
    public void configurationDeleted( final TargetedPID pid, TargetedPID factoryPid )
    {
        log( LogService.LOG_DEBUG, "ImmediateComponentHolder configuration deleted for pid {0}",
            new Object[] {pid}, null);

        // component to deconfigure or dispose of
        final Map<AbstractComponentManager<S>, Map<String, Object>> scms = new HashMap<AbstractComponentManager<S>, Map<String, Object>>();
        boolean reconfigure = false;

        synchronized ( m_components )
        {
            //            // FELIX-2231: nothing to do any more, all components have been disposed off
            //            if (m_singleComponent == null) 
            //            {
            //                return;
            //            }
            if (factoryPid != null) {
                checkFactoryPidIndex(factoryPid);
                String servicePid = pid.getServicePid();
                m_factoryTargetedPids.remove(servicePid);
                m_factoryChangeCount.remove(servicePid);
                m_factoryConfigurations.remove(servicePid);
                AbstractComponentManager<S> scm = m_components.remove(servicePid);
                if ( m_factoryConfigurations.isEmpty() )
                {
                    m_factoryPidIndex = null;
                }
                if ( !m_enabled || scm == null )
                {
                    return;
                }
                reconfigure = m_componentMetadata.isConfigurationOptional() && m_components.isEmpty();
                if ( reconfigure )
                {
                    m_singleComponent = scm;
                    scms.put( scm, mergeProperties(null) );
                }
                else
                {
                    scms.put( scm,  null );
                }
            }
            else
            {
                //singleton pid
                int index = getSingletonPidIndex(pid);
                m_targetedPids[index] = null;
                m_changeCount[index] = null;
                m_configurations[index] = null;
                if ( !m_enabled )
                {
                    return;
                }
                reconfigure = m_componentMetadata.isConfigurationOptional();

                if ( m_factoryPidIndex == null)
                {
                    if ( m_singleComponent != null ) {
                        if (reconfigure) {
                            scms.put(m_singleComponent, mergeProperties(null));
                        } else {
                            scms.put(m_singleComponent, null);
                            m_singleComponent = null;
                        }
                    }
                }
                else
                {
                    if (reconfigure) {
                        for (Map.Entry<String, AbstractComponentManager<S>> entry : m_components.entrySet()) {
                            scms.put(entry.getValue(), mergeProperties(entry.getKey()));
                        }
                    }
                    else
                    {
                        for (Map.Entry<String, AbstractComponentManager<S>> entry : m_components.entrySet()) {
                            scms.put(entry.getValue(), null );
                        }	
                        m_components.clear();
                    }
                }

            }
        }

        for ( Map.Entry<AbstractComponentManager<S>,Map<String, Object>> entry: scms.entrySet())
        {
            if ( reconfigure ) {
                entry.getKey().reconfigure( entry.getValue(), true);
            } else {
                entry.getKey().dispose(ComponentConstants.DEACTIVATION_REASON_CONFIGURATION_DELETED);
            }
        }
    }


    /**
     * Configures a component with the given configuration. This configuration
     * update may happen in various situations:
     * <ul>
     * <li>The <code>pid</code> equals the component name. Hence we have a
     * singleton configuration for the single component held by this holder</li>
     * <li>The configuration is a factory configuration and is the first
     * configuration provided. In this case the single component is provided
     * with the configuration and also stored in the map.</li>
     * <li>The configuration is a factory configuration but not the first. In
     * this case a new component is created, configured and stored in the map</li>
     * </ul>
     * @return true if a new configuration was created, false otherwise. //TODO there are now 3 states..... still not satisfied, existing, and new
     */
    public boolean configurationUpdated( TargetedPID pid, TargetedPID factoryPid, final Dictionary<String, Object> props, long changeCount )
    {
        log( LogService.LOG_DEBUG, "ConfigurableComponentHolder configuration updated for pid {0} with properties {1}",
            new Object[] {pid, props}, null);

        // component to update or create
        final Map<AbstractComponentManager<S>, Map<String, Object>> scms = new HashMap< AbstractComponentManager<S>, Map<String, Object>>();
        boolean created = false;

        //TODO better change count tracking
        synchronized (m_components) {
            //Find or create the component manager, or return if not satisfied.
            if (factoryPid != null) {
                checkFactoryPidIndex(factoryPid);
                m_factoryConfigurations.put(pid.getServicePid(), props);
                m_factoryTargetedPids.put(pid.getServicePid(), factoryPid);
                m_factoryChangeCount.put(pid.getServicePid(), changeCount);
                if (m_enabled && isSatisfied()) {
                    if (m_singleComponent != null) {
                        AbstractComponentManager<S> scm = m_singleComponent;
                        scms.put( scm, mergeProperties( pid.getServicePid() ) );
                        m_singleComponent = null;
                        m_components.put(pid.getServicePid(), scm);
                    } else if (m_components.containsKey(pid.getServicePid())) {
                        scms.put( m_components.get(pid.getServicePid()), mergeProperties( pid.getServicePid())  );
                    } else {
                        AbstractComponentManager<S> scm = createComponentManager();
                        m_components.put(pid.getServicePid(), scm);
                        scms.put( scm, mergeProperties( pid.getServicePid())  );
                        created = true;
                    }
                } else {
                    return false;
                }

            } else {
                //singleton pid
                int index = getSingletonPidIndex(pid);
                m_targetedPids[index] = pid;
                m_changeCount[index] = changeCount;
                m_configurations[index] = props;
                if (m_enabled && isSatisfied()) {
                    if (m_singleComponent != null) {
                        scms.put( m_singleComponent, mergeProperties( pid.getServicePid() ) );
                    } 
                    else if ( m_factoryPidIndex != null) 
                    {
                        for (Map.Entry<String, AbstractComponentManager<S>> entry: m_components.entrySet()) 
                        {
                            scms.put(entry.getValue(), mergeProperties( entry.getKey()));
                        }
                    }
                    else
                    {
                        m_singleComponent = createComponentManager();
                        scms.put( m_singleComponent, mergeProperties( pid.getServicePid() ) );
                        created = true;
                    }
                } else {
                    return false;
                }

            }

        }


        // we have the icm.
        //properties is all the configs merged together (without any possible component factory info.

        final boolean enable = created && m_enabled;// TODO WTF?? && getComponentMetadata().isEnabled();
        for ( Map.Entry<AbstractComponentManager<S>,Map<String, Object>> entry: scms.entrySet())
        {
            // configure the component
            entry.getKey().reconfigure(entry.getValue(), false);
            log(LogService.LOG_DEBUG,
                "ImmediateComponentHolder Finished configuring the dependency managers for component for pid {0} ",
                new Object[] { pid }, null);
            if (enable) {
                entry.getKey().enable(false);
                log(LogService.LOG_DEBUG,
                    "ImmediateComponentHolder Finished enabling component for pid {0} ",
                    new Object[] { pid }, null);
            } else {
                log(LogService.LOG_DEBUG,
                    "ImmediateComponentHolder Will not enable component for pid {0}: holder enabled state: {1}, metadata enabled: {2} ",
                    new Object[] { pid, m_enabled,
                    m_componentMetadata.isEnabled() }, null);
            }
        }
        return created;
    }

    private Map<String, Object> mergeProperties(String servicePid) {
        Map<String, Object> properties;
        properties = new HashMap<String, Object>(m_componentMetadata.getProperties());
        for (int i = 0; i < m_configurations.length; i++)
        {
            if ( m_factoryPidIndex != null && i == m_factoryPidIndex)
            {
                copyTo(properties, m_factoryConfigurations.get(servicePid));
            }
            else if ( m_configurations[i] != null )
            {
                copyTo(properties, m_configurations[i]);
            }
        }
        return properties;
    }

    private int getSingletonPidIndex(TargetedPID pid) {
        int index = m_componentMetadata.getPidIndex(pid);
        if (index == -1) {
            log(LogService.LOG_ERROR,
                "Unrecognized pid {0}, expected one of {1}",
                new Object[] { pid,
                m_componentMetadata.getConfigurationPid() },
                null);
            throw new IllegalArgumentException("Unrecognized pid "
                + pid);
        }
        if (m_factoryPidIndex != null && index == m_factoryPidIndex) {
            log(LogService.LOG_ERROR,
                "singleton pid {0} supplied, but matches an existing factory pid at index: {1}",
                new Object[] { pid, m_factoryPidIndex }, null);
            throw new IllegalStateException(
                "Singleton pid supplied matching a previous factory pid "
                    + pid);
        }
        return index;
    }

    //TODO update error messages so they make sense for deleting config too. 
    private void checkFactoryPidIndex(TargetedPID factoryPid) {
        int index = m_componentMetadata.getPidIndex(factoryPid);
        if (index == -1) {
            log(LogService.LOG_ERROR,
                "Unrecognized factory pid {0}, expected one of {1}",
                new Object[] { factoryPid,
                m_componentMetadata.getConfigurationPid() },
                null);
            throw new IllegalArgumentException(
                "Unrecognized factory pid " + factoryPid);
        }
        if (m_configurations[index] != null) {
            log(LogService.LOG_ERROR,
                "factory pid {0}, but this pid is already supplied as a singleton: {1} at index {2}",
                new Object[] { factoryPid, Arrays.asList(m_targetedPids), index }, null);
            throw new IllegalStateException(
                "Factory pid supplied after all non-factory configurations supplied "
                    + factoryPid);
        }
        if (m_factoryPidIndex == null) {
            m_factoryPidIndex = index;
        } else if (index != m_factoryPidIndex) {
            log(LogService.LOG_ERROR,
                "factory pid {0} supplied for index {1}, but a factory pid previously supplied at index {2}",
                new Object[] { factoryPid, index, m_factoryPidIndex },
                null);
            throw new IllegalStateException(
                "Factory pid supplied at wrong index " + factoryPid);
        }
    }

    protected static void copyTo( Map<String, Object> target, Dictionary<String, ?> source )
    {

        for ( Enumeration<String> keys = source.keys(); keys.hasMoreElements(); )
        {
            String key = keys.nextElement();
            Object value = source.get(key);
            target.put(key, value);
        }
    }

    /**
     * Determine if the holder is satisfied with configurations
     * @return true if configuration optional or all pids supplied with configurations
     */
    private boolean isSatisfied() {
        if ( m_componentMetadata.isConfigurationOptional() || m_componentMetadata.isConfigurationIgnored() ) 
        {
            return true;
        }
        for ( int i = 0; i < m_componentMetadata.getConfigurationPid().size(); i++)
        {
            if ( m_configurations[i] != null)
            {
                continue;
            }
            if ( m_factoryPidIndex != null && m_factoryPidIndex == i)
            {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * @param pid the Targeted PID we need the change count for
     * @param targetedPid the targeted factory pid for a factory configuration or the pid for a singleton configuration
     * @return pid for this service pid.
     */
    public long getChangeCount( TargetedPID pid, TargetedPID targetedPid)
    {
        int index = m_componentMetadata.getPidIndex(targetedPid);
        Long result;
        if ( index == -1 )
        {
            throw new IllegalArgumentException("pid not recognized as for this component: " + pid);
        }
        if ( m_factoryPidIndex != null && index == m_factoryPidIndex )
        {
            result = m_factoryChangeCount.get(pid.getServicePid());
        }
        else
        {
            result = m_changeCount[index];
        }
        return result == null? -1: result;

    }

    public List<? extends ComponentManager<?>> getComponents()
        {
        synchronized ( m_components )
        {
            return getComponentManagers( false );
        }
        }


    public boolean isEnabled() {
        return m_enabled;
    }

    public void enableComponents( final boolean async )
    {
        List<AbstractComponentManager<S>> cms = new ArrayList<AbstractComponentManager<S>>();
        synchronized ( m_components )
        {
            if ( isSatisfied() )
            {
                if ( m_factoryPidIndex == null)
                {
                    m_singleComponent = createComponentManager();
                    cms.add( m_singleComponent );
                    m_singleComponent.reconfigure(mergeProperties( null ), false);
                }
                else
                {
                    for (String pid: m_factoryConfigurations.keySet()) {
                        SingleComponentManager<S> scm = createComponentManager();
                        m_components.put(pid, scm);
                        scm.reconfigure( mergeProperties( pid ), false);
                        cms.add( scm );
                    }
                }
            }
            m_enabled = true;
        }
        for ( AbstractComponentManager<S> cm : cms )
        {
            cm.enable( async );
        }
    }


    public void disableComponents( final boolean async )
    {
        List<AbstractComponentManager<S>> cms;
        synchronized ( m_components )
        {
            m_enabled = false;

            cms = getComponentManagers( true );
            //       		if (m_singleComponent != null && m_factoryPidIndex == null && 
            //    				(m_componentMetadata.isConfigurationIgnored() || m_componentMetadata.isConfigurationOptional()))
            //    		{
            //    			m_singleComponent = null;
            //    		}
        }
        for ( AbstractComponentManager<S> cm : cms )
        {
            cm.disable( async );
        }
    }


    public void disposeComponents( final int reason )
    {
        List<AbstractComponentManager<S>> cms;
        synchronized ( m_components )
        {
            cms = getComponentManagers( true );
        }
        for ( AbstractComponentManager<S> cm : cms )
        {
            cm.dispose( reason );
        }
    }


    public void disposed( SingleComponentManager<S> component )
    {
        // ensure the component is removed from the components map
        synchronized ( m_components )
        {
            if ( !m_components.isEmpty() )
            {
                for ( Iterator<AbstractComponentManager<S>> vi = m_components.values().iterator(); vi.hasNext(); )
                {
                    if ( component == vi.next() )
                    {
                        vi.remove();
                        break;
                    }
                }
            }

            if ( component == m_singleComponent )
            {
                m_singleComponent = null;
            }
        }
    }

    /**
     * Compares this {@code ImmediateComponentHolder} object to another object.
     * 
     * <p>
     * A ImmediateComponentHolder is considered to be <b>equal to </b> another 
     * ImmediateComponentHolder if the component names are equal(using 
     * {@code String.equals}) and they have the same bundle activator
     * 
     * @param object The {@code ImmediateComponentHolder} object to be compared.
     * @return {@code true} if {@code object} is a
     *         {@code ImmediateComponentHolder} and is equal to this object;
     *         {@code false} otherwise.
     */
    public boolean equals(Object object)
    {
        if (!(object instanceof ConfigurableComponentHolder))
        {
            return false;
        }

        ConfigurableComponentHolder<S> other = (ConfigurableComponentHolder<S>) object;
        return m_activator == other.m_activator
            && getName().equals(other.getName());
    }

    /**
     * Returns a hash code value for the object.
     * 
     * @return An integer which is a hash code value for this object.
     */
    @Override
    public int hashCode()
    {
        return getName().hashCode();
    }

    @Override
    public String toString()
    {
        return "[ImmediateComponentHolder:" + getName() + "]";
    }

    String getName()
    {
        return m_componentMetadata.getName();
    }

    //---------- internal

    /**
     * Returns all component managers from the map and the single component manager, optionally also removing them
     * from the map. If there are no component managers, <code>null</code>
     * is returned.  Must be called synchronized on m_components.
     * 
     * @param clear If true, clear the map and the single component manager.
     */
    List<AbstractComponentManager<S>> getComponentManagers( final boolean clear )
    {
        List<AbstractComponentManager<S>> cm;
        if ( m_components.isEmpty() )
        {
            if ( m_singleComponent != null)
            {
                cm = Collections.singletonList(m_singleComponent);
            }
            else 
            {
                cm = Collections.emptyList();
            }
        }

        else
        {
            cm = new ArrayList<AbstractComponentManager<S>>(m_components.values());
        }
        if ( clear )
        {
            m_components.clear();
            m_singleComponent = null;
        }
        return cm;
    }

    public boolean isLogEnabled( int level )
    {
        return Activator.isLogEnabled( level );
    }

    public void log( int level, String message, Throwable ex )
    {
        BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            activator.log( level, message, getComponentMetadata(), null, ex );
        }
    }

    public void log( int level, String message, Object[] arguments, Throwable ex )
    {
        BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            activator.log( level, message, arguments, getComponentMetadata(), null, ex );
        }
    }

    public TargetedPID getConfigurationTargetedPID(TargetedPID pid, TargetedPID factoryPid)
    {
        if ( factoryPid == null )
        {
            int index = m_componentMetadata.getPidIndex(pid);
            if (index != -1)
            {
                return m_targetedPids[index];
            }
            return null;
        }
        //each factory configured component may have a different factory targeted pid.
        synchronized (m_components)
        {
            return m_factoryTargetedPids.get(pid.getServicePid());
        }
    }

}
