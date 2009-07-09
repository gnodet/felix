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
package org.apache.felix.karaf.gshell.features.completers;

import java.util.Collection;
import java.util.List;

import org.apache.felix.karaf.gshell.features.management.ManagedFeature;
import org.apache.felix.karaf.gshell.features.management.ManagedFeaturesRegistry;
import org.apache.felix.karaf.gshell.console.completer.StringsCompleter;
import org.apache.felix.karaf.gshell.console.Completer;

/**
 * {@link jline.Completor} for available features.
 *
 * Displays a list of available features from installed repositories.
 *
 */
public class AvailableFeatureCompleter implements Completer {

    private ManagedFeaturesRegistry featuresRegistry;
    private StringsCompleter delegate;

    public void setFeaturesRegistry(ManagedFeaturesRegistry featuresRegistry) {
        this.featuresRegistry = featuresRegistry;
    }

    public int complete(final String buffer, final int cursor, final List candidates) {

        Collection<ManagedFeature> features = featuresRegistry.getAvailableFeatures().values();
        delegate = new StringsCompleter();

        for (ManagedFeature feature : features) {
            delegate.getStrings().add(feature.getName());
        }

        return delegate.complete(buffer, cursor, candidates);
    }


}
