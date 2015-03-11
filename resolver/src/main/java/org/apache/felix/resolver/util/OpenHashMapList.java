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
package org.apache.felix.resolver.util;

import java.util.List;

public class OpenHashMapList<K, V> extends OpenHashMap<K, List<V>> {

    public OpenHashMapList() {
        super();
    }

    public OpenHashMapList(int initialCapacity) {
        super(initialCapacity);
    }

    public OpenHashMapList(int initialCapacity, double minLoadFactor, double maxLoadFactor) {
        super(initialCapacity, minLoadFactor, maxLoadFactor);
    }

    @Override
    public List<V> put(K key, List<V> value) {
        if (!(value instanceof CopyOnWriteList)) {
            throw new IllegalStateException();
        }
        return super.put(key, value);
    }

    public OpenHashMapList<K, V> deepClone() {
        OpenHashMapList<K, V> copy = (OpenHashMapList<K, V>) super.clone();
        Object[] values = copy.values;
        for (int i = 0, l = values.length; i < l; i++) {
            if (values[i] != null) {
                values[i] = new CopyOnWriteList<V>((CopyOnWriteList<V>) values[i]);
            }
        }
        return copy;
    }
}
