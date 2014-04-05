package org.apache.felix.resolver;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

import junit.framework.TestCase;
import org.osgi.resource.Resource;

public class FELIX4478Test extends TestCase {

     public void testResolveFragmentsImportExport() throws Exception {
        Set<Resource> resources = new HashSet<Resource>();
        resources.add(buildResource("aries.blueprint.core.MF"));
        resources.add(buildResource("karaf.shell.console-imports.MF"));
         resources.add(buildResource("karaf.shell.core.MF"));

         Set<Resource> allResources = new HashSet<Resource>(resources);
        allResources.add(buildResource("system.MF"));

        ResolverImpl resolver = new ResolverImpl(new Logger(Logger.LOG_DEBUG));
        ResolveContextImpl rc = new ResolveContextImpl(
                resources,
                Collections.<Resource>emptySet(),
                new StaticRepository(allResources),
                false
        );
        resolver.resolve(rc);
    }

    public void testResolveErrorWrappedResource() throws Exception {
        Set<Resource> resources = new HashSet<Resource>();
        resources.add(buildResource("aries.blueprint.core.MF"));
        resources.add(buildResource("aries.blueprint.core.compat.MF"));
        resources.add(buildResource("karaf.jaas.blueprint.jasypt.MF"));
        resources.add(buildResource("karaf.shell.console-noimports.MF"));
        resources.add(buildResource("karaf.shell.core.MF"));

        Set<Resource> allResources = new HashSet<Resource>(resources);
        allResources.add(buildResource("system.MF"));

        ResolverImpl resolver = new ResolverImpl(new Logger(Logger.LOG_DEBUG));
        ResolveContextImpl rc = new ResolveContextImpl(
                resources,
                Collections.<Resource>emptySet(),
                new StaticRepository(allResources),
                false);
        resolver.resolve(rc);
    }


    private Resource buildResource(String uri) throws Exception {
        Manifest man  = new Manifest(getClass().getResourceAsStream(uri));
        Map<String, String> headers = new HashMap<String, String>();
        for (Map.Entry attr : man.getMainAttributes().entrySet()) {
            headers.put(attr.getKey().toString(), attr.getValue().toString());
        }
        return ResourceBuilder.build(uri, headers);
    }

}
