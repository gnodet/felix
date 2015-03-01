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
package org.apache.felix.dm.annotation.plugin.bnd;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import aQute.bnd.osgi.Annotation;

/**
 * This class encodes a component descriptor entry line, using json.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class EntryWriter
{
    // Every descriptor entries contains a type parameter for identifying the kind of entry
    private final static String TYPE = "type";

    /** All parameters as stored in a json object */
    private JSONObject m_json;

    /** The entry type */
    private EntryType m_type;

    /**
     * Makes a new component descriptor entry.
     */
    public EntryWriter(EntryType type)
    {
        m_type = type;
        m_json = new JSONObject();
        try
        {
            m_json.put("type", type.toString());
        }
        catch (JSONException e)
        {
            throw new RuntimeException("could not initialize json object", e);
        }
    }

    /**
     * Returns this entry type.
     */
    EntryType getEntryType()
    {
        return m_type;
    }

    /**
     * Returns a string representation for the given component descriptor entry.
     */
    @Override
    public String toString()
    {
        return m_json.toString();
    }

    /**
     * Put a String parameter in this descritor entry.
     */
    public void put(EntryParam param, String value)
    {
        checkType(param.toString());
        try
        {
            m_json.put(param.toString(), value);
        }
        catch (JSONException e)
        {
            throw new IllegalArgumentException("could not add param " + param + ":" + value, e);
        }
    }

    /**
     * Put a String[] parameter in this descriptor entry.
     */
    public void put(EntryParam param, String[] array)
    {
        checkType(param.toString());
        try
        {
            m_json.put(param.toString(), new JSONArray(Arrays.asList(array)));
        }
        catch (JSONException e)
        {
            throw new IllegalArgumentException("could not add param " + param + ":"
                + Arrays.toString(array), e);
        }
    }

    /**
     * Puts a json object.
     * @throws JSONException 
     */
    public void putJsonObject(EntryParam param, JSONObject jsonObject) throws JSONException
    {
        m_json.put(param.toString(),  jsonObject);
    }

    /**
     * Get a String attribute value from an annotation and write it into this descriptor entry.
     */
    public String putString(Annotation annotation, EntryParam param, String def)
    {
        checkType(param.toString());
        Object value = annotation.get(param.toString());
        if (value == null && def != null)
        {
            value = def;
        }
        if (value != null)
        {
            put(param, value.toString());
        }
        return value == null ? null : value.toString();
    }

    /**
     * Get a String array attribute value from an annotation and write it into this descriptor entry.
     */
    public void putStringArray(Annotation annotation, EntryParam param, String[] def)
    {
        checkType(param.toString());
        Object value = annotation.get(param.toString());
        if (value == null && def != null)
        {
            value = def;
        }
        if (value != null)
        {
            for (Object v: ((Object[]) value))
            {
                try
                {
                    m_json.append(param.toString(), v.toString());
                }
                catch (JSONException e)
                {
                    throw new IllegalArgumentException("Could not add param " + param + ":"
                        + value.toString(), e);
                }
            }
        }
    }

    /**
     * Get a class attribute value from an annotation and write it into this descriptor entry.
     */
    public void putClass(Annotation annotation, EntryParam param, Object def)
    {
        checkType(param.toString());

        Pattern pattern = Patterns.CLASS;
        Object value = annotation.get(param.toString());
        if (value == null && def != null)
        {
            value = def;
            pattern = null;
        }
        if (value != null)
        {
            if (pattern != null)
            {
                value = Patterns.parseClass(value.toString(), pattern, 1);
            }
            put(param, value.toString());
        }
    }

    /**
     * Get a class array attribute value from an annotation and write it into this descriptor entry.
     * Also collect classes found from the array into a given Set.
     * @return the class array size.
     */
    public int putClassArray(Annotation annotation, EntryParam param, Object def, Set<String> collect)
    {
        checkType(param.toString());

        Pattern pattern = Patterns.CLASS;
        Object value = annotation.get(param.toString());
        if (value == null && def != null)
        {
            value = def;
            pattern = null;
        }
        if (value != null)
        {
            if (!(value instanceof Object[]))
            {
                throw new IllegalArgumentException("annotation parameter " + param
                    + " has not a class array type");
            }

            for (Object v: ((Object[]) value))
            {
                if (pattern != null)
                {
                    v = Patterns.parseClass(v.toString(), pattern, 1);
                }
                try
                {
                    m_json.append(param.toString(), v.toString());
                    collect.add(v.toString());
                }
                catch (JSONException e)
                {
                    throw new IllegalArgumentException("Could not add param " + param + ":"
                            + value.toString(), e);
                }
            }
            
            return ((Object[]) value).length;
        }
        
        return 0;
    }

    /**
     * Check if the written key is not equals to "type" ("type" is an internal attribute we are using
     * in order to identify a kind of descriptor entry (Service, ServiceDependency, etc ...).
     */
    private void checkType(String key)
    {
        if (TYPE.equals(key))
        {
            throw new IllegalArgumentException("\"" + TYPE + "\" parameter can't be overriden");
        }
    }
}
