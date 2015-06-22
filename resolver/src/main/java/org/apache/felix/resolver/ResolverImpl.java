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
package org.apache.felix.resolver;

import java.util.*;
import java.util.Map.Entry;

import org.apache.felix.resolver.util.ArrayMap;
import org.apache.felix.resolver.util.LinkedOpenHashMap;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Namespace;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;
import org.osgi.service.resolver.HostedCapability;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.ResolveContext;
import org.osgi.service.resolver.Resolver;

public class ResolverImpl implements Resolver
{
    private final Logger m_logger;

    // Note this class is not thread safe.
    // Only use in the context of a single thread.
    class ResolveSession
    {
        // Holds the resolve context for this session
        private final ResolveContext m_resolveContext;
        // Holds candidate permutations based on permutating "uses" chains.
        // These permutations are given higher priority.
        private final List<Candidates> m_usesPermutations = new ArrayList<Candidates>();
        // Holds candidate permutations based on permutating requirement candidates.
        // These permutations represent backtracking on previous decisions.
        private final List<Candidates> m_importPermutations = new ArrayList<Candidates>();
        // Holds candidate permutations based on removing candidates that satisfy
        // multiple cardinality requirements.
        // This permutation represents a permutation that is consistent because we have
        // removed the offending capabilities
        private Candidates m_multipleCardCandidates = null;

        private final Map<Capability, Set<Capability>> m_packageSourcesCache = new HashMap<Capability, Set<Capability>>(256);

        private final LinkedOpenHashMap<String, List<String>> m_usesCache = new LinkedOpenHashMap<String, List<String>>() {
            public List<String> compute(String uses) {
                return parseUses(uses);
            }
        };

        ResolveSession(ResolveContext resolveContext)
        {
            m_resolveContext = resolveContext;
        }

        List<Candidates> getUsesPermutations()
        {
            return m_usesPermutations;
        }

        List<Candidates> getImportPermutations()
        {
            return m_importPermutations;
        }

        Candidates getMultipleCardCandidates()
        {
            return m_multipleCardCandidates;
        }

        void setMultipleCardCandidates(Candidates multipleCardCandidates)
        {
            m_multipleCardCandidates = multipleCardCandidates;
        }

        Map<Capability, Set<Capability>> getPackageSourcesCache()
        {
            return m_packageSourcesCache;
        }

        ResolveContext getContext()
        {
            return m_resolveContext;
        }

        public LinkedOpenHashMap<String, List<String>> getUsesCache() {
            return m_usesCache;
        }
    }

    public ResolverImpl(Logger logger)
    {
        m_logger = logger;
    }

    public Map<Resource, List<Wire>> resolve(ResolveContext rc) throws ResolutionException
    {
        ResolveSession session = new ResolveSession(rc);
        Map<Resource, List<Wire>> wireMap = new HashMap<Resource, List<Wire>>();

        // Make copies of arguments in case we want to modify them.
        Collection<Resource> mandatoryResources = new ArrayList<Resource>(rc.getMandatoryResources());
        Collection<Resource> optionalResources = new ArrayList<Resource>(rc.getOptionalResources());
        // keeps track of valid on demand fragments that we have seen.
        // a null value or TRUE indicate it is valid
        Map<Resource, Boolean> validOnDemandResources = new HashMap<Resource, Boolean>(0);

        boolean retry;
        do
        {
            retry = false;
            try
            {
                // Create object to hold all candidates.
                Candidates allCandidates = new Candidates(validOnDemandResources);

                // Populate mandatory resources; since these are mandatory
                // resources, failure throws a resolve exception.
                for (Iterator<Resource> it = mandatoryResources.iterator();
                    it.hasNext();)
                {
                    Resource resource = it.next();
                    if (Util.isFragment(resource) || (rc.getWirings().get(resource) == null))
                    {
                        ResolutionError error = allCandidates.populate(rc, resource, Candidates.MANDATORY);
                        if (error != null)
                        {
                            throw error.toException();
                        }
                    }
                    else
                    {
                        it.remove();
                    }
                }

                // Populate optional resources; since these are optional
                // resources, failure does not throw a resolve exception.
                for (Resource resource : optionalResources)
                {
                    boolean isFragment = Util.isFragment(resource);
                    if (isFragment || (rc.getWirings().get(resource) == null))
                    {
                        ResolutionError error = allCandidates.populate(rc, resource, Candidates.OPTIONAL);
                        if (error != null)
                        {
                            throw error.toException();
                        }
                    }
                }

                // Merge any fragments into hosts.
                ResolutionError rethrow = allCandidates.prepare(rc);
                if (rethrow != null)
                {
                    throw rethrow.toException();
                }

                // Create a combined list of populated resources; for
                // optional resources. We do not need to consider ondemand
                // fragments, since they will only be pulled in if their
                // host is already present.
                Set<Resource> allResources =
                    new LinkedHashSet<Resource>(mandatoryResources);
                for (Resource resource : optionalResources)
                {
                    if (allCandidates.isPopulated(resource))
                    {
                        allResources.add(resource);
                    }
                }

                List<Candidates> usesPermutations = session.getUsesPermutations();
                List<Candidates> importPermutations = session.getImportPermutations();

                // Record the initial candidate permutation.
                usesPermutations.add(allCandidates);

                // If a populated resource is a fragment, then its host
                // must ultimately be verified, so store its host requirement
                // to use for package space calculation.
                Map<Resource, Requirement> hostReqs = new HashMap<Resource, Requirement>();
                for (Resource resource : allResources)
                {
                    if (Util.isFragment(resource))
                    {
                        hostReqs.put(
                            resource,
                            resource.getRequirements(HostNamespace.HOST_NAMESPACE).get(0));
                    }
                }

                Set<Object> processedDeltas = new HashSet<Object>();
                Map<Resource, ResolutionError> faultyResources = null;
                do
                {
                    allCandidates = (usesPermutations.size() > 0)
                            ? usesPermutations.remove(0)
                            : (importPermutations.size() > 0
                                    ? importPermutations.remove(0)
                                    : null);
                    if (allCandidates == null)
                    {
                        break;
                    }
                    // The delta is used to detect that we have already processed this particular permutation
                    if (!processedDeltas.add(allCandidates.getDelta()))
                    {
                        // This permutation has already been tried
                        // Don't try it again
                        continue;
                    }

                    session.getPackageSourcesCache().clear();
                    // Null out each time a new permutation is attempted.
                    // We only use this to store a valid permutation which is a
                    // delta of the current permutation.
                    session.setMultipleCardCandidates(null);

//allCandidates.dump();

                    rethrow = allCandidates.checkSubstitutes(importPermutations);
                    if (rethrow != null)
                    {
                        continue;
                    }

                    Map<Resource, Resource> hosts = new LinkedOpenHashMap<Resource, Resource>();
                    for (Resource resource : allResources)
                    {
                        // If we are resolving a fragment, then get its
                        // host candidate and verify it instead.
                        Requirement hostReq = hostReqs.get(resource);
                        if (hostReq != null)
                        {
                            Capability hostCap = allCandidates.getFirstCandidate(hostReq);
                            // If the resource is an already resolved fragment and can not
                            // be attached to new hosts, there will be no matching host,
                            // so ignore this resource
                            if (hostCap == null)
                            {
                                continue;
                            }
                            resource = hostCap.getResource();
                        }
                        hosts.put(resource, allCandidates.getWrappedHost(resource));
                    }

                    Map<Resource, ResolutionError> currentFaultyResources = new HashMap<Resource, ResolutionError>();
                    rethrow = checkConsistency(session, allCandidates, currentFaultyResources, hosts);

                    if (!currentFaultyResources.isEmpty())
                    {
                        if (faultyResources == null)
                        {
                            faultyResources = currentFaultyResources;
                        }
                        else if (faultyResources.size() > currentFaultyResources.size())
                        {
                            // save the optimal faultyResources which has less
                            faultyResources = currentFaultyResources;
                        }
                    }
                }
                while (rethrow != null);

                // If there is a resolve exception, then determine if an
                // optionally resolved resource is to blame (typically a fragment).
                // If so, then remove the optionally resolved resolved and try
                // again; otherwise, rethrow the resolve exception.
                if (rethrow != null)
                {
                    if (faultyResources != null)
                    {
                        Set<Resource> resourceKeys = faultyResources.keySet();
                        retry = (optionalResources.removeAll(resourceKeys));
                        for (Resource faultyResource : resourceKeys)
                        {
                            Boolean valid = validOnDemandResources.get(faultyResource);
                            if (valid != null && valid)
                            {
                                // This was an ondemand resource.
                                // Invalidate it and try again.
                                validOnDemandResources.put(faultyResource, Boolean.FALSE);
                                retry = true;
                            }
                        }
                        // log all the resolution exceptions for the uses constraint violations
                        for (Map.Entry<Resource, ResolutionError> usesError : faultyResources.entrySet())
                        {
                            m_logger.logUsesConstraintViolation(usesError.getKey(), usesError.getValue());
                        }
                    }
                    if (!retry)
                    {
                        throw rethrow.toException();
                    }
                }
                // If there is no exception to rethrow, then this was a clean
                // resolve, so populate the wire map.
                else
                {
                    if (session.getMultipleCardCandidates() != null)
                    {
                        // Candidates for multiple cardinality requirements were
                        // removed in order to provide a consistent class space.
                        // Use the consistent permutation
                        allCandidates = session.getMultipleCardCandidates();
                    }
                    for (Resource resource : allResources)
                    {
                        Resource target = resource;

                        // If we are resolving a fragment, then we
                        // actually want to populate its host's wires.
                        Requirement hostReq = hostReqs.get(resource);
                        if (hostReq != null)
                        {
                            Capability hostCap = allCandidates.getFirstCandidate(hostReq);
                            // If the resource is an already resolved fragment and can not
                            // be attached to new hosts, there will be no matching host,
                            // so ignore this resource
                            if (hostCap == null)
                            {
                                continue;
                            }
                            target = hostCap.getResource();
                        }

                        if (allCandidates.isPopulated(target))
                        {
                            wireMap = populateWireMap(
                                    rc, allCandidates.getWrappedHost(target),
                                    wireMap, allCandidates);
                        }
                    }
                }
            }
            finally
            {
                // Always clear the state.
                session.getUsesPermutations().clear();
                session.getImportPermutations().clear();
                session.setMultipleCardCandidates(null);
                // TODO this was not cleared out before; but it seems it should be
                session.getPackageSourcesCache().clear();
            }
        }
        while (retry);

        return wireMap;
    }

    private ResolutionError checkConsistency(
            ResolveSession session,
            Candidates allCandidates,
            Map<Resource, ResolutionError> currentFaultyResources,
            Map<Resource, Resource> hosts)
    {
        // Calculate package spaces
        Map<Resource, Packages> resourcePkgMap = calculatePackageSpaces(session, allCandidates, hosts.values());
        ResolutionError rethrow = null;
        // Check package consistency
        Map<Resource, Object> resultCache = new LinkedOpenHashMap<Resource, Object>(resourcePkgMap.size());
        for (Entry<Resource, Resource> entry : hosts.entrySet())
        {
            rethrow = checkPackageSpaceConsistency(
                    session, entry.getValue(),
                    allCandidates, false, resourcePkgMap, resultCache);
            if (rethrow != null)
            {
                Resource faultyResource = entry.getKey();
                // check that the faulty requirement is not from a fragment
                for (Requirement faultyReq : rethrow.getUnresolvedRequirements())
                {
                    if (faultyReq instanceof WrappedRequirement)
                    {
                        faultyResource =
                                ((WrappedRequirement) faultyReq)
                                        .getDeclaredRequirement().getResource();
                        break;
                    }
                }
                currentFaultyResources.put(faultyResource, rethrow);
            }
        }
        return rethrow;
    }

    /**
     * Resolves a dynamic requirement for the specified host resource using the
     * specified {@link ResolveContext}. The dynamic requirement may contain
     * wild cards in its filter for the package name. The matching candidates
     * are used to resolve the requirement and the resolve context is not asked
     * to find providers for the dynamic requirement. The host resource is
     * expected to not be a fragment, to already be resolved and have an
     * existing wiring provided by the resolve context.
     * <p>
     * This operation may resolve additional resources in order to resolve the
     * dynamic requirement. The returned map will contain entries for each
     * resource that got resolved in addition to the specified host resource.
     * The wire list for the host resource will only contain a single wire which
     * is for the dynamic requirement.
     *
     * @param rc the resolve context
     * @param host the hosting resource
     * @param dynamicReq the dynamic requirement
     * @param matches a list of matching capabilities
     * @return The new resources and wires required to satisfy the specified
     * dynamic requirement. The returned map is the property of the caller and
     * can be modified by the caller.
     * @throws ResolutionException
     */
    public Map<Resource, List<Wire>> resolve(
        ResolveContext rc, Resource host, Requirement dynamicReq,
        List<Capability> matches)
        throws ResolutionException
    {
        ResolveSession session = new ResolveSession(rc);
        Map<Resource, List<Wire>> wireMap = new HashMap<Resource, List<Wire>>();

        // We can only create a dynamic import if the following
        // conditions are met:
        // 1. The specified resource is resolved.
        // 2. The package in question is not already imported.
        // 3. The package in question is not accessible via require-bundle.
        // 4. The package in question is not exported by the resource.
        // 5. The package in question matches a dynamic import of the resource.
        if (!matches.isEmpty() && rc.getWirings().containsKey(host))
        {
            // Make sure all matching candidates are packages.
            for (Capability cap : matches)
            {
                if (!cap.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
                {
                    throw new IllegalArgumentException(
                        "Matching candidate does not provide a package name.");
                }
            }

            Map<Resource, Packages> resourcePkgMap = new HashMap<Resource, Packages>();
            Map<Resource, Boolean> onDemandResources = new HashMap<Resource, Boolean>();

            boolean retry;
            do
            {
                retry = false;

                try
                {
                    // Create all candidates pre-populated with the single candidate set
                    // for the resolving dynamic import of the host.
                    Candidates allCandidates = new Candidates(onDemandResources);
                    ResolutionError rethrow = allCandidates.populateDynamic(rc, host, dynamicReq, matches);
                    if (rethrow == null)
                    {
                        // Merge any fragments into hosts.
                        rethrow = allCandidates.prepare(rc);
                    }
                    if (rethrow != null)
                    {
                        throw rethrow.toException();
                    }

                    List<Candidates> usesPermutations = session.getUsesPermutations();
                    List<Candidates> importPermutations = session.getImportPermutations();

                    // Record the initial candidate permutation.
                    usesPermutations.add(allCandidates);

                    do
                    {
                        resourcePkgMap.clear();
                        session.getPackageSourcesCache().clear();

                        allCandidates = (usesPermutations.size() > 0)
                            ? usesPermutations.remove(0)
                            : importPermutations.remove(0);
//allCandidates.dump();

                        rethrow = allCandidates.checkSubstitutes(importPermutations);
                        if (rethrow != null)
                        {
                            continue;
                        }
                        // For a dynamic import, the instigating resource
                        // will never be a fragment since fragments never
                        // execute code, so we don't need to check for
                        // this case like we do for a normal resolve.

                        Map<Resource, Resource> hosts = Collections.singletonMap(host, allCandidates.getWrappedHost(host));
                        resourcePkgMap = calculatePackageSpaces(
                                session,
                                allCandidates,
                                hosts.values());
//System.out.println("+++ PACKAGE SPACES START +++");
//dumpResourcePkgMap(resourcePkgMap);
//System.out.println("+++ PACKAGE SPACES END +++");
                        rethrow = checkConsistency(
                                session,
                                allCandidates,
                                new LinkedOpenHashMap<Resource, ResolutionError>(resourcePkgMap.size()),
                                hosts);
                    }
                    while ((rethrow != null)
                        && ((usesPermutations.size() > 0) || (importPermutations.size() > 0)));

                    // If there is a resolve exception, then determine if an
                    // optionally resolved resource is to blame (typically a fragment).
                    // If so, then remove the optionally resolved resource and try
                    // again; otherwise, rethrow the resolve exception.
                    if (rethrow != null)
                    {
                        Collection<Requirement> exReqs = rethrow.getUnresolvedRequirements();
                        Requirement faultyReq = ((exReqs == null) || (exReqs.isEmpty()))
                            ? null : exReqs.iterator().next();
                        Resource faultyResource = (faultyReq == null)
                            ? null : getDeclaredResource(faultyReq.getResource());
                        // If the faulty requirement is wrapped, then it may
                        // be from a fragment, so consider the fragment faulty
                        // instead of the host.
                        if (faultyReq instanceof WrappedRequirement)
                        {
                            faultyResource =
                                ((WrappedRequirement) faultyReq)
                                .getDeclaredRequirement().getResource();
                        }
                        Boolean valid = onDemandResources.get(faultyResource);
                        if (valid != null && valid)
                        {
                            onDemandResources.put(faultyResource, Boolean.FALSE);
                            retry = true;
                        }
                        else
                        {
                            throw rethrow.toException();
                        }
                    }
                    // If there is no exception to rethrow, then this was a clean
                    // resolve, so populate the wire map.
                    else
                    {
                        if (session.getMultipleCardCandidates() != null)
                        {
                            // TODO this was not done before; but I think it should be;
                            // Candidates for multiple cardinality requirements were
                            // removed in order to provide a consistent class space.
                            // Use the consistent permutation
                            allCandidates = session.getMultipleCardCandidates();
                        }
                        wireMap = populateDynamicWireMap(rc,
                            host, dynamicReq, wireMap, allCandidates);
                    }
                }
                finally
                {
                    // Always clear the state.
                    session.getUsesPermutations().clear();
                    session.getImportPermutations().clear();
                    // TODO these were not cleared out before; but it seems they should be
                    session.setMultipleCardCandidates(null);
                    session.getPackageSourcesCache().clear();
                }
            }
            while (retry);
        }

        return wireMap;
    }

    private static List<WireCandidate> getWireCandidates(ResolveSession session, Candidates allCandidates, Resource resource)
    {
        // Create parallel lists for requirement and proposed candidate
        // capability or actual capability if resource is resolved or not.
        // We use parallel lists so we can calculate the packages spaces for
        // resolved and unresolved resources in an identical fashion.
        List<WireCandidate> wireCandidates = new ArrayList<WireCandidate>(256);
        Wiring wiring = session.getContext().getWirings().get(resource);
        if (wiring != null)
        {
            // Use wires to get actual requirements and satisfying capabilities.
            for (Wire wire : wiring.getRequiredResourceWires(null))
            {
                // Wrap the requirement as a hosted requirement if it comes
                // from a fragment, since we will need to know the host. We
                // also need to wrap if the requirement is a dynamic import,
                // since that requirement will be shared with any other
                // matching dynamic imports.
                Requirement r = wire.getRequirement();
                if (!r.getResource().equals(wire.getRequirer()) || Util.isDynamic(r))
                {
                    r = new WrappedRequirement(wire.getRequirer(), r);
                }
                // Wrap the capability as a hosted capability if it comes
                // from a fragment, since we will need to know the host.
                Capability c = wire.getCapability();
                if (!c.getResource().equals(wire.getProvider()))
                {
                    c = new WrappedCapability(wire.getProvider(), c);
                }
                wireCandidates.add(new WireCandidate(r, c));
            }

            // Since the resource is resolved, it could be dynamically importing,
            // so check to see if there are candidates for any of its dynamic
            // imports.
            //
            // NOTE: If the resource is dynamically importing, the fact that
            // the dynamic import is added here last to the parallel reqs/caps
            // list is used later when checking to see if the package being
            // dynamically imported shadows an existing provider.
            for (Requirement req : wiring.getResourceRequirements(null))
            {
                if (Util.isDynamic(req))
                {
                    // Grab first (i.e., highest priority) candidate for the current requirement.
                    Capability cap = allCandidates.getFirstCandidate(req);
                    // Optional requirements may not have any candidates.
                    if (cap == null)
                    {
                        continue;
                    }
                    wireCandidates.add(new WireCandidate(req, cap));
                    // Can only dynamically import one at a time, so break
                    // out of the loop after the first.
                    break;
                }
            }
        }
        else
        {
            for (Requirement req : resource.getRequirements(null))
            {
                if (!Util.isDynamic(req))
                {
                    // Get the candidates for the current requirement.
                    List<Capability> candCaps = allCandidates.getCandidates(req);
                    // Optional requirements may not have any candidates.
                    if (candCaps == null)
                    {
                        continue;
                    }

                    // For multiple cardinality requirements, we need to grab
                    // all candidates.
                    if (Util.isMultiple(req))
                    {
                        // Use the same requirement, but list each capability separately
                        for (Capability cap : candCaps)
                        {
                            wireCandidates.add(new WireCandidate(req, cap));
                        }
                    }
                    // Grab first (i.e., highest priority) candidate
                    else
                    {
                        Capability cap = candCaps.get(0);
                        wireCandidates.add(new WireCandidate(req, cap));
                    }
                }
            }
        }
        return wireCandidates;
    }

    private static LinkedOpenHashMap<String, Blame> getExportedPackages(
            ResolveSession session,
            Candidates allCandidates,
            Resource resource,
            LinkedOpenHashMap<String, Blame> exports)
    {
        // Get all exported packages.
        Wiring wiring = session.getContext().getWirings().get(resource);
        List<Capability> caps = (wiring != null)
                ? wiring.getResourceCapabilities(null)
                : resource.getCapabilities(null);
        for (Capability cap : caps)
        {
            if (cap.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
            {
                if (!cap.getResource().equals(resource))
                {
                    cap = new WrappedCapability(resource, cap);
                }
                exports.put(
                        (String) cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE),
                        new Blame(cap, null));
            }
        }
        // Remove substitutable exports that were imported.
        // For resolved resources Wiring.getCapabilities()
        // already excludes imported substitutable exports, but
        // for resolving resources we must look in the candidate
        // map to determine which exports are substitutable.
        if (!exports.isEmpty())
        {
            if (wiring == null)
            {
                for (Requirement req : resource.getRequirements(null))
                {
                    if (req.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
                    {
                        Capability cand = allCandidates.getFirstCandidate(req);
                        if (cand != null)
                        {
                            String pkgName = (String) cand.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
                            exports.remove(pkgName);
                        }
                    }
                }
            }
        }
        return exports;
    }

    private static void mergeCandidatePackages(
            ResolveSession session,
            Map<Resource, Packages> allPackages,
            Candidates allCandidates,
            Packages packages,
            Requirement currentReq,
            Capability candCap,
            Set<Capability> capabilityCycles,
            Set<Resource> requiredBundlesCycles)
    {
        if (!capabilityCycles.add(candCap))
        {
            return;
        }

        if (candCap.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
        {
            mergeCandidatePackage(packages.m_importedPkgs, currentReq, candCap);
        }
        else if (candCap.getNamespace().equals(BundleNamespace.BUNDLE_NAMESPACE))
        {

            // Get the candidate's package space to determine which packages
            // will be visible to the current resource.
            if (requiredBundlesCycles.add(candCap.getResource()))
            {
                // We have to merge all exported packages from the candidate,
                // since the current resource requires it.
                for (Blame blame : allPackages.get(candCap.getResource()).m_exportedPkgs.values())
                {
                    mergeCandidatePackage(
                            packages.m_requiredPkgs,
                            currentReq,
                            blame.m_cap
                    );
                }
            }

            // If the candidate requires any other bundles with reexport visibility,
            // then we also need to merge their packages too.
            Wiring candWiring = session.getContext().getWirings().get(candCap.getResource());
            if (candWiring != null)
            {
                for (Wire w : candWiring.getRequiredResourceWires(null))
                {
                    if (BundleNamespace.BUNDLE_NAMESPACE.equals(w.getRequirement().getNamespace()))
                    {
                        if (Util.isReexport(w.getRequirement()))
                        {
                            mergeCandidatePackages(
                                    session,
                                    allPackages,
                                    allCandidates,
                                    packages,
                                    currentReq,
                                    w.getCapability(),
                                    capabilityCycles,
                                    requiredBundlesCycles);
                        }
                    }
                }
            }
            else
            {
                for (Requirement req : candCap.getResource().getRequirements(null))
                {
                    if (BundleNamespace.BUNDLE_NAMESPACE.equals(req.getNamespace()))
                    {
                        if (Util.isReexport(req))
                        {
                            Capability cap = allCandidates.getFirstCandidate(req);
                            if (cap != null)
                            {
                                mergeCandidatePackages(
                                        session,
                                        allPackages,
                                        allCandidates,
                                        packages,
                                        currentReq,
                                        cap,
                                        capabilityCycles,
                                        requiredBundlesCycles);
                            }
                        }
                    }
                }
            }
        }
    }

    private static Packages getPackages(
            ResolveSession session,
            Candidates allCandidates,
            Map<Resource, List<WireCandidate>> allWireCandidates,
            Map<Resource, Packages> allPackages,
            Resource resource,
            Packages packages)
    {
        // First, all all exported packages
        // This has been done previously

        // Second, add all imported packages to the target resource's package space.
        for (WireCandidate wire : allWireCandidates.get(resource))
        {
            // If this resource is dynamically importing, then the last requirement
            // is the dynamic import being resolved, since it is added last to the
            // parallel lists above. For the dynamically imported package, make
            // sure that the resource doesn't already have a provider for that
            // package, which would be illegal and shouldn't be allowed.
            if (Util.isDynamic(wire.requirement)) {
                String pkgName = (String) wire.capability.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
                if (packages.m_exportedPkgs.containsKey(pkgName)
                        || packages.m_importedPkgs.containsKey(pkgName)
                        || packages.m_requiredPkgs.containsKey(pkgName)) {
                    throw new IllegalArgumentException(
                            "Resource "
                                    + resource
                                    + " cannot dynamically import package '"
                                    + pkgName
                                    + "' since it already has access to it.");
                }
            }

            mergeCandidatePackages(
                    session,
                    allPackages,
                    allCandidates,
                    packages,
                    wire.requirement,
                    wire.capability,
                    new HashSet<Capability>(),
                    new HashSet<Resource>());
        }

        return packages;
    }

    private static void computeUses(
            ResolveSession session,
            Map<Resource, List<WireCandidate>> allWireCandidates,
            Map<Resource, Packages> allPackages,
            Resource resource)
    {
        List<WireCandidate> candidates = allWireCandidates.get(resource);
        Packages packages = allPackages.get(resource);
        // Fourth, if the target resource is unresolved or is dynamically importing,
        // then add all the uses constraints implied by its imported and required
        // packages to its package space.
        // NOTE: We do not need to do this for resolved resources because their
        // package space is consistent by definition and these uses constraints
        // are only needed to verify the consistency of a resolving resource. The
        // only exception is if a resolved resource is dynamically importing, then
        // we need to calculate its uses constraints again to make sure the new
        // import is consistent with the existing package space.
        Wiring wiring = session.getContext().getWirings().get(resource);
        Set<Capability> usesCycleMap = new HashSet<Capability>();

        // TODO: do that slightly better
        WireCandidate dynamicWire = null;
        if (!candidates.isEmpty())
        {
            WireCandidate w = candidates.get(candidates.size() - 1);
            if (Util.isDynamic(w.requirement))
            {
                dynamicWire = w;
            }
        }

        if (wiring == null || dynamicWire != null)
        {
            // Merge uses constraints from required capabilities.
            for (WireCandidate w : candidates) {
                String namespace = w.requirement.getNamespace();
                if (!BundleNamespace.BUNDLE_NAMESPACE.equals(namespace) && !PackageNamespace.PACKAGE_NAMESPACE.equals(namespace))
                {
                    mergeUses(session, allPackages, resource, packages, w.capability, Collections.singletonList(w.requirement), w.capability, usesCycleMap);
                }
            }

            // Merge uses constraints from imported packages.
            for (List<Blame> blames : packages.m_importedPkgs.values())
            {
                for (Blame b : blames)
                {
                    mergeUses(session, allPackages, resource, packages, b.m_cap, Collections.singletonList(b.m_reqs.get(0)), null, usesCycleMap);
                }
            }

            // Merge uses constraints from required bundles.
            for (List<Blame> blames : packages.m_requiredPkgs.values())
            {
                for (Blame b : blames)
                {
                    mergeUses(session, allPackages, resource, packages, b.m_cap, Collections.singletonList(b.m_reqs.get(0)), null, usesCycleMap);
                }
            }
        }
    }

    private static void mergeUses(
            ResolveSession session,
            Map<Resource, Packages> allPackages,
            Resource resource,
            Packages packages,
            Capability mergeCap,
            List<Requirement> blameReqs,
            Capability matchingCap,
            Set<Capability> cycleMap) {
        // If there are no uses, then just return.
        // If the candidate resource is the same as the current resource,
        // then we don't need to verify and merge the uses constraints
        // since this will happen as we build up the package space.
        if (resource.equals(mergeCap.getResource())) {
            return;
        }

        // Check for cycles.
        if (!cycleMap.add(mergeCap)) {
            return;
        }

        for (Capability candSourceCap : getPackageSources(session, mergeCap, allPackages))
        {
            String s = candSourceCap.getDirectives().get(Namespace.CAPABILITY_USES_DIRECTIVE);
            if (s != null && !s.isEmpty())
            {
                Packages candSourcePkgs = allPackages.get(candSourceCap.getResource());
                // Parse these uses directive.
                List<String> uses = session.getUsesCache().getOrCompute(s);
                for (String usedPkgName : uses)
                {
                    List<Blame> candSourceBlames;
                    // Check to see if the used package is exported.
                    Blame candExportedBlame = candSourcePkgs.m_exportedPkgs.get(usedPkgName);
                    if (candExportedBlame != null)
                    {
                        candSourceBlames = Collections.singletonList(candExportedBlame);
                    }
                    else
                    {
                        // If the used package is not exported, check to see if it
                        // is required.
                        candSourceBlames = candSourcePkgs.m_requiredPkgs.get(usedPkgName);
                        // Lastly, if the used package is not required, check to see if it
                        // is imported.
                        if (candSourceBlames == null)
                        {
                            candSourceBlames = candSourcePkgs.m_importedPkgs.get(usedPkgName);
                        }
                    }
                    // If the used package cannot be found, then just ignore it
                    // since it has no impact.
                    if (candSourceBlames != null)
                    {
                        ArrayMap<Capability, UsedBlames> usedPkgBlames = packages.m_usedPkgs.getOrCompute(usedPkgName);
                        for (Blame blame : candSourceBlames)
                        {
                            if (blame.m_reqs != null)
                            {
                                List<Requirement> blameReqs2 = new ArrayList<Requirement>(blameReqs.size() + 1);
                                blameReqs2.addAll(blameReqs);
                                // Only add the last requirement in blame chain because
                                // that is the requirement wired to the blamed capability
                                blameReqs2.add(blame.m_reqs.get(blame.m_reqs.size() - 1));
                                addUsedBlame(usedPkgBlames, blame.m_cap, blameReqs2, matchingCap);
                                mergeUses(session, allPackages, resource, packages, blame.m_cap, blameReqs2, matchingCap, cycleMap);
                            }
                            else
                            {
                                addUsedBlame(usedPkgBlames, blame.m_cap, blameReqs, matchingCap);
                                mergeUses(session, allPackages, resource, packages, blame.m_cap, blameReqs, matchingCap, cycleMap);
                            }
                        }
                    }
                }
            }
        }
    }

    private static LinkedOpenHashMap<Resource, Packages> calculatePackageSpaces(
            ResolveSession session,
            Candidates allCandidates,
            Collection<Resource> hosts)
    {
        // Compute wire candidates
        LinkedOpenHashMap<Resource, List<WireCandidate>> allWireCandidates = new LinkedOpenHashMap<Resource, List<WireCandidate>>(allCandidates.getNbResources());
        Set<Resource> toCompute = new HashSet<Resource>(hosts);
        while (!toCompute.isEmpty())
        {
            Resource resource = toCompute.iterator().next();
            List<WireCandidate> wireCandidates = getWireCandidates(session, allCandidates, resource);
            allWireCandidates.put(resource, wireCandidates);
            for (WireCandidate wire : wireCandidates)
            {
                Resource r = wire.capability.getResource();
                if (!allWireCandidates.containsKey(r))
                {
                    toCompute.add(r);
                }
            }
            toCompute.remove(resource);
        }

        LinkedOpenHashMap<Resource, Packages> allPackages = new LinkedOpenHashMap<Resource, Packages>(allWireCandidates.size());

        // Get all exported packages.
        for (Resource resource : allWireCandidates.keySet())
        {
            Packages packages = new Packages(resource);
            getExportedPackages(session, allCandidates, resource, packages.m_exportedPkgs);
            allPackages.put(resource, packages);
        }

        // Compute package lists
        for (Resource resource : allWireCandidates.keySet())
        {
            getPackages(session, allCandidates, allWireCandidates, allPackages, resource, allPackages.get(resource));
        }

        // Compute uses
        for (Resource resource : allWireCandidates.keySet())
        {
            computeUses(session, allWireCandidates, allPackages, resource);
        }

        return allPackages;
    }

    private static void mergeCandidatePackage(
            LinkedOpenHashMap<String, List<Blame>> packages,
            Requirement currentReq, Capability candCap)
    {
        if (candCap.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
        {
            // Merge the candidate capability into the resource's package space
            // for imported or required packages, appropriately.

            String pkgName = (String) candCap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);

            List<Requirement> blameReqs = Collections.singletonList(currentReq);

            List<Blame> blames = packages.getOrCompute(pkgName);
            blames.add(new Blame(candCap, blameReqs));

//dumpResourcePkgs(current, currentPkgs);
        }
    }

    private static List<String> parseUses(String s) {
        int nb = 1;
        int l = s.length();
        for (int i = 0; i < l; i++) {
            if (s.charAt(i) == ',') {
                nb++;
            }
        }
        List<String> uses = new ArrayList<String>(nb);
        int start = 0;
        while (true) {
            while (start < l) {
                char c = s.charAt(start);
                if (c != ' ' && c != ',') {
                    break;
                }
                start++;
            }
            int end = start + 1;
            while (end < l) {
                char c = s.charAt(end);
                if (c == ' ' || c == ',') {
                    break;
                }
                end++;
            }
            if (start < l) {
                uses.add(s.substring(start, end));
                start = end + 1;
            } else {
                break;
            }
        }
        return uses;
    }

    private static void addUsedBlame(
            ArrayMap<Capability, UsedBlames> usedBlames,
            Capability usedCap,
            List<Requirement> blameReqs,
            Capability matchingCap)
    {
        // Create a new Blame based off the used capability and the
        // blame chain requirements.
        Blame newBlame = new Blame(usedCap, blameReqs);
        // Find or create UsedBlame that uses the same capablity as the new blame.
        UsedBlames addToBlame = usedBlames.getOrCompute(usedCap);
        // Add the new Blame and record the matching capability cause
        // in case the root requirement has multiple cardinality.
        addToBlame.addBlame(newBlame, matchingCap);
    }

    private ResolutionError checkPackageSpaceConsistency(
            ResolveSession session,
            Resource resource,
            Candidates allCandidates,
            boolean dynamic,
            Map<Resource, Packages> resourcePkgMap,
            Map<Resource, Object> resultCache)
    {
        if (resultCache.containsKey(resource))
        {
            return null;
        }
        if (!dynamic && session.getContext().getWirings().containsKey(resource))
        {
            return null;
        }

        Packages pkgs = resourcePkgMap.get(resource);

        ResolutionError error = null;
        Candidates permutation = null;
        Set<Requirement> mutated = null;

        List<Candidates> importPermutations = session.getImportPermutations();
        List<Candidates> usesPermutations = session.getUsesPermutations();

        // Check for conflicting imports from fragments.
        // TODO: Is this only needed for imports or are generic and bundle requirements also needed?
        //       I think this is only a special case for fragment imports because they can overlap
        //       host imports, which is not allowed in normal metadata.
        for (Entry<String, List<Blame>> entry : pkgs.m_importedPkgs.fast())
        {
            String pkgName = entry.getKey();
            List<Blame> blames = entry.getValue();
            if (blames.size() > 1)
            {
                Blame sourceBlame = null;
                for (Blame blame : blames)
                {
                    if (sourceBlame == null)
                    {
                        sourceBlame = blame;
                    }
                    else if (!sourceBlame.m_cap.getResource().equals(blame.m_cap.getResource()))
                    {
                        // Try to permutate the conflicting requirement.
                        allCandidates.permutate(blame.m_reqs.get(0), importPermutations);
                        // Try to permutate the source requirement.
                        allCandidates.permutate(sourceBlame.m_reqs.get(0), importPermutations);
                        // Report conflict.
                        error = new UseConstraintError(
                                session.getContext(), allCandidates,
                                resource, pkgName,
                                sourceBlame, blame);
                        if (m_logger.isDebugEnabled())
                        {
                            m_logger.debug(
                                    "Candidate permutation failed due to a conflict with a "
                                            + "fragment import; will try another if possible."
                                            + " (" + error.getMessage() + ")");
                        }
                        return error;
                    }
                }
            }
        }

        // Check if there are any uses conflicts with exported packages.
        for (Entry<String, Blame> entry : pkgs.m_exportedPkgs.fast())
        {
            String pkgName = entry.getKey();
            Blame exportBlame = entry.getValue();
            if (!pkgs.m_usedPkgs.containsKey(pkgName))
            {
                continue;
            }
            for (UsedBlames usedBlames : pkgs.m_usedPkgs.get(pkgName).values())
            {
                if (!isCompatible(session, Collections.singletonList(exportBlame), usedBlames.m_cap, resourcePkgMap))
                {
                    for (Blame usedBlame : usedBlames.m_blames)
                    {
                        if (checkMultiple(session, usedBlames, usedBlame, allCandidates))
                        {
                            // Continue to the next blame, if possible we
                            // removed the conflicting candidates.
                            continue;
                        }
                        // Create a candidate permutation that eliminates all candidates
                        // that conflict with existing selected candidates.
                        permutation = (permutation != null)
                            ? permutation
                            : allCandidates.copy();
                        if (error == null)
                        {
                            error = new UseConstraintError(session.getContext(), allCandidates, resource, pkgName, usedBlame);
                        }

                        mutated = (mutated != null)
                            ? mutated
                            : new HashSet<Requirement>();

                        for (int reqIdx = usedBlame.m_reqs.size() - 1; reqIdx >= 0; reqIdx--)
                        {
                            Requirement req = usedBlame.m_reqs.get(reqIdx);
                            // Sanity check for multiple.
                            if (Util.isMultiple(req))
                            {
                                continue;
                            }
                            // If we've already permutated this requirement in another
                            // uses constraint, don't permutate it again just continue
                            // with the next uses constraint.
                            if (mutated.contains(req))
                            {
                                break;
                            }

                            // See if we can permutate the candidates for blamed
                            // requirement; there may be no candidates if the resource
                            // associated with the requirement is already resolved.
                            if (permutation.canRemoveCandidate(req))
                            {
                                permutation.removeFirstCandidate(req);
                                mutated.add(req);
                                break;
                            }
                        }
                    }
                }
            }

            if (error != null)
            {
                if (!mutated.isEmpty())
                {
                    usesPermutations.add(permutation);
                }
                if (m_logger.isDebugEnabled())
                {
                    m_logger.debug("Candidate permutation failed due to a conflict between "
                            + "an export and import; will try another if possible."
                            + " (" + error.getMessage() + ")");
                }
                return error;
            }
        }

        // Check if there are any uses conflicts with imported and required packages.
        // We combine the imported and required packages here into one map.
        // Imported packages are added after required packages because they shadow or override
        // the packages from required bundles.
        LinkedOpenHashMap<String, List<Blame>> allImportRequirePkgs =
            new LinkedOpenHashMap<String, List<Blame>>(pkgs.m_requiredPkgs.size() + pkgs.m_importedPkgs.size());
        allImportRequirePkgs.putAll(pkgs.m_requiredPkgs);
        allImportRequirePkgs.putAll(pkgs.m_importedPkgs);

        for (Entry<String, List<Blame>> entry : allImportRequirePkgs.fast())
        {
            String pkgName = entry.getKey();
            if (!pkgs.m_usedPkgs.containsKey(pkgName))
            {
                continue;
            }
            List<Blame> requirementBlames = entry.getValue();

            for (UsedBlames usedBlames : pkgs.m_usedPkgs.get(pkgName).values())
            {
                if (!isCompatible(session, requirementBlames, usedBlames.m_cap, resourcePkgMap))
                {
                    // Split packages, need to think how to get a good message for split packages (sigh)
                    // For now we just use the first requirement that brings in the package that conflicts
                    Blame requirementBlame = requirementBlames.get(0);
                    for (Blame usedBlame : usedBlames.m_blames)
                    {
                        if (checkMultiple(session, usedBlames, usedBlame, allCandidates))
                        {
                            // Continue to the next blame, if possible we
                            // removed the conflicting candidates.
                            continue;
                        }
                        // Create a candidate permutation that eliminates all candidates
                        // that conflict with existing selected candidates.
                        permutation = (permutation != null)
                            ? permutation
                            : allCandidates.copy();
                        if (error == null)
                        {
                            error = new UseConstraintError(
                                    session.getContext(), allCandidates,
                                    resource, pkgName,
                                    requirementBlame, usedBlame
                            );
                        }

                        mutated = (mutated != null)
                            ? mutated
                            : new HashSet<Requirement>();

                        for (int reqIdx = usedBlame.m_reqs.size() - 1; reqIdx >= 0; reqIdx--)
                        {
                            Requirement req = usedBlame.m_reqs.get(reqIdx);
                            // Sanity check for multiple.
                            if (Util.isMultiple(req))
                            {
                                continue;
                            }
                            // If we've already permutated this requirement in another
                            // uses constraint, don't permutate it again just continue
                            // with the next uses constraint.
                            if (mutated.contains(req))
                            {
                                break;
                            }

                            // See if we can permutate the candidates for blamed
                            // requirement; there may be no candidates if the resource
                            // associated with the requirement is already resolved.
                            if (permutation.canRemoveCandidate(req)) {
                                permutation.removeFirstCandidate(req);
                                mutated.add(req);
                                break;
                            }
                        }
                    }
                }

                // If there was a uses conflict, then we should add a uses
                // permutation if we were able to permutate any candidates.
                // Additionally, we should try to push an import permutation
                // for the original import to force a backtracking on the
                // original candidate decision if no viable candidate is found
                // for the conflicting uses constraint.
                if (error != null)
                {
                    // Add uses permutation if we mutated any candidates.
                    if (!mutated.isEmpty())
                    {
                        usesPermutations.add(permutation);
                    }

                    // Try to permutate the candidate for the original
                    // import requirement; only permutate it if we haven't
                    // done so already.
                    for (Blame requirementBlame : requirementBlames)
                    {
                        Requirement req = requirementBlame.m_reqs.get(0);
                        if (!mutated.contains(req))
                        {
                            // Since there may be lots of uses constraint violations
                            // with existing import decisions, we may end up trying
                            // to permutate the same import a lot of times, so we should
                            // try to check if that the case and only permutate it once.
                            allCandidates.permutateIfNeeded(req, importPermutations);
                        }
                    }

                    if (m_logger.isDebugEnabled())
                    {
                        m_logger.debug("Candidate permutation failed due to a conflict between "
                                + "imports; will try another if possible."
                                + " (" + error.getMessage() + ")"
                        );
                    }
                    return error;
                }
            }
        }

        resultCache.put(resource, Boolean.TRUE);

        // Now check the consistency of all resources on which the
        // current resource depends. Keep track of the current number
        // of permutations so we know if the lower level check was
        // able to create a permutation or not in the case of failure.
        int permCount = usesPermutations.size() + importPermutations.size();
        for (Requirement req : resource.getRequirements(null))
        {
            Capability cap = allCandidates.getFirstCandidate(req);
            if (cap != null)
            {
                if (!resource.equals(cap.getResource()))
                {
                    error = checkPackageSpaceConsistency(
                            session, cap.getResource(),
                            allCandidates, false, resourcePkgMap, resultCache);
                    if (error != null)
                    {
                        // If the lower level check didn't create any permutations,
                        // then we should create an import permutation for the
                        // requirement with the dependency on the failing resource
                        // to backtrack on our current candidate selection.
                        if (permCount == (usesPermutations.size() + importPermutations.size()))
                        {
                            allCandidates.permutate(req, importPermutations);
                        }
                        return error;
                    }
                }
            }
        }
        return null;
    }

    private boolean checkMultiple(
        ResolveSession session,
        UsedBlames usedBlames,
        Blame usedBlame,
        Candidates permutation)
    {
        // Check the root requirement to see if it is a multiple cardinality
        // requirement.
        List<Capability> candidates = null;
        Requirement req = usedBlame.m_reqs.get(0);
        if (Util.isMultiple(req))
        {
            // Create a copy of the current permutation so we can remove the
            // candidates causing the blame.
            if (session.getMultipleCardCandidates() == null)
            {
                session.setMultipleCardCandidates(permutation.copy());
            }
            // Get the current candidate list and remove all the offending root
            // cause candidates from a copy of the current permutation.
            candidates = session.getMultipleCardCandidates().clearCandidates(req, usedBlames.getRootCauses(req));
        }
        // We only are successful if there is at least one candidate left
        // for the requirement
        return (candidates != null) && !candidates.isEmpty();
    }

    private static boolean isCompatible(
        ResolveSession session, List<Blame> currentBlames, Capability candCap,
        Map<Resource, Packages> resourcePkgMap)
    {
        if ((!currentBlames.isEmpty()) && (candCap != null))
        {
            Set<Capability> currentSources;
            // quick check for single source package
            if (currentBlames.size() == 1)
            {
                Capability currentCap = currentBlames.get(0).m_cap;
                if (currentCap.equals(candCap))
                {
                    return true;
                }
                currentSources =
                    getPackageSources(
                        session,
                        currentCap,
                        resourcePkgMap);
            }
            else
            {
                currentSources = new HashSet<Capability>(currentBlames.size());
                for (Blame currentBlame : currentBlames)
                {
                    Set<Capability> blameSources =
                        getPackageSources(
                            session,
                            currentBlame.m_cap,
                            resourcePkgMap);
                    for (Capability blameSource : blameSources)
                    {
                        currentSources.add(blameSource);
                    }
                }
            }

            Set<Capability> candSources =
                getPackageSources(
                    session,
                    candCap,
                    resourcePkgMap);

            return currentSources.containsAll(candSources)
                || candSources.containsAll(currentSources);
        }
        return true;
    }

    private static void computePackageSources(
            ResolveSession session,
            Resource resource,
            Map<Resource, Packages> resourcePkgMap)
    {
        Wiring wiring = session.getContext().getWirings().get(resource);
        List<Capability> caps = (wiring != null)
                ? wiring.getResourceCapabilities(null)
                : resource.getCapabilities(null);
        Map<Capability, Set<Capability>> resCache = session.getPackageSourcesCache();
        LinkedOpenHashMap<String, Set<Capability>> pkgs = new LinkedOpenHashMap<String, Set<Capability>>(caps.size()) {
            public Set<Capability> compute(String pkgName) {
                return new HashSet<Capability>();
            }
        };
        for (Capability sourceCap : caps)
        {
            if (sourceCap.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
            {
                String pkgName = sourceCap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE).toString();
                Set<Capability> pkgCaps = pkgs.getOrCompute(pkgName);
                resCache.put(sourceCap, pkgCaps);
                // Since capabilities may come from fragments, we need to check
                // for that case and wrap them.
                if (!resource.equals(sourceCap.getResource()))
                {
                    sourceCap = new WrappedCapability(resource, sourceCap);
                }
                pkgCaps.add(sourceCap);
            }
            else
            {
                // Otherwise, need to return generic capabilities that have
                // uses constraints so they are included for consistency
                // checking.
                String uses = sourceCap.getDirectives().get(Namespace.CAPABILITY_USES_DIRECTIVE);
                if ((uses != null) && !uses.isEmpty())
                {
                    resCache.put(sourceCap, Collections.singleton(sourceCap));
                }
                else
                {
                    resCache.put(sourceCap, Collections.<Capability>emptySet());
                }
            }
        }
        for (Map.Entry<String, Set<Capability>> pkg : pkgs.fast())
        {
            String pkgName = pkg.getKey();
            List<Blame> required = resourcePkgMap.get(resource).m_requiredPkgs.get(pkgName);
            if (required != null)
            {
                Set<Capability> sources = pkg.getValue();
                for (Blame blame : required)
                {
                    Capability bcap = blame.m_cap;
                    if (sources.add(bcap))
                    {
                        sources.addAll(getPackageSources(session, bcap, resourcePkgMap));
                    }
                }
            }
        }
    }

    private static Set<Capability> getPackageSources(
        ResolveSession session, Capability cap, Map<Resource, Packages> resourcePkgMap)
    {
        Map<Capability, Set<Capability>> packageSourcesCache = session.getPackageSourcesCache();
        Set<Capability> sources = packageSourcesCache.get(cap);
        if (sources == null) {
            computePackageSources(session, cap.getResource(), resourcePkgMap);
            sources = packageSourcesCache.get(cap);
        }
        return sources;
    }

    private static Resource getDeclaredResource(Resource resource)
    {
        if (resource instanceof WrappedResource)
        {
            return ((WrappedResource) resource).getDeclaredResource();
        }
        return resource;
    }

    private static Capability getDeclaredCapability(Capability c)
    {
        if (c instanceof HostedCapability)
        {
            return ((HostedCapability) c).getDeclaredCapability();
        }
        return c;
    }

    private static Requirement getDeclaredRequirement(Requirement r)
    {
        if (r instanceof WrappedRequirement)
        {
            return ((WrappedRequirement) r).getDeclaredRequirement();
        }
        return r;
    }

    private static Map<Resource, List<Wire>> populateWireMap(
        ResolveContext rc, Resource resource,
        Map<Resource, List<Wire>> wireMap, Candidates allCandidates)
    {
        Resource unwrappedResource = getDeclaredResource(resource);
        if (!rc.getWirings().containsKey(unwrappedResource)
            && !wireMap.containsKey(unwrappedResource))
        {
            wireMap.put(unwrappedResource, Collections.<Wire>emptyList());

            List<Wire> packageWires = new ArrayList<Wire>();
            List<Wire> bundleWires = new ArrayList<Wire>();
            List<Wire> capabilityWires = new ArrayList<Wire>();

            for (Requirement req : resource.getRequirements(null))
            {
                List<Capability> cands = allCandidates.getCandidates(req);
                if ((cands != null) && (cands.size() > 0))
                {
                    for (Capability cand : cands)
                    {
                        // Do not create wires for the osgi.wiring.* namespaces
                        // if the provider and requirer are the same resource;
                        // allow such wires for non-OSGi wiring namespaces.
                        if (!cand.getNamespace().startsWith("osgi.wiring.")
                            || !resource.equals(cand.getResource()))
                        {
                            // Populate wires for the candidate
                            populateWireMap(rc, cand.getResource(),
                                    wireMap, allCandidates);

                            Resource provider;
                            if (req.getNamespace().equals(IdentityNamespace.IDENTITY_NAMESPACE)) {
                                provider = getDeclaredCapability(cand).getResource();
                            } else {
                                provider = getDeclaredResource(cand.getResource());
                            }
                            Wire wire = new WireImpl(
                                unwrappedResource,
                                getDeclaredRequirement(req),
                                provider,
                                getDeclaredCapability(cand));
                            if (req.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
                            {
                                packageWires.add(wire);
                            }
                            else if (req.getNamespace().equals(BundleNamespace.BUNDLE_NAMESPACE))
                            {
                                bundleWires.add(wire);
                            }
                            else
                            {
                                capabilityWires.add(wire);
                            }
                        }
                        if (!Util.isMultiple(req))
                        {
                            // If not multiple just create a wire for the first candidate.
                            break;
                        }
                    }
                }
            }

            // Combine package wires with require wires last.
            packageWires.addAll(bundleWires);
            packageWires.addAll(capabilityWires);
            wireMap.put(unwrappedResource, packageWires);

            // Add host wire for any fragments.
            if (resource instanceof WrappedResource)
            {
                List<Resource> fragments = ((WrappedResource) resource).getFragments();
                for (Resource fragment : fragments)
                {
                    // Get wire list for the fragment from the wire map.
                    // If there isn't one, then create one. Note that we won't
                    // add the wire list to the wire map until the end, so
                    // we can determine below if this is the first time we've
                    // seen the fragment while populating wires to avoid
                    // creating duplicate non-payload wires if the fragment
                    // is attached to more than one host.
                    List<Wire> fragmentWires = wireMap.get(fragment);
                    fragmentWires = (fragmentWires == null)
                        ? new ArrayList<Wire>() : fragmentWires;

                    // Loop through all of the fragment's requirements and create
                    // any necessary wires for non-payload requirements.
                    for (Requirement req : fragment.getRequirements(null))
                    {
                        // Only look at non-payload requirements.
                        if (!isPayload(req))
                        {
                            // If this is the host requirement, then always create
                            // a wire for it to the current resource.
                            if (req.getNamespace().equals(HostNamespace.HOST_NAMESPACE))
                            {
                                fragmentWires.add(
                                    new WireImpl(
                                        getDeclaredResource(fragment),
                                        req,
                                        unwrappedResource,
                                        unwrappedResource.getCapabilities(
                                            HostNamespace.HOST_NAMESPACE).get(0)));
                            }
                            // Otherwise, if the fragment isn't already resolved and
                            // this is the first time we are seeing it, then create
                            // a wire for the non-payload requirement.
                            else if (!rc.getWirings().containsKey(fragment)
                                && !wireMap.containsKey(fragment))
                            {
                                Wire wire = createWire(req, allCandidates);
                                if (wire != null)
                                {
                                    fragmentWires.add(wire);
                                }
                            }
                        }
                    }

                    // Finally, add the fragment's wire list to the wire map.
                    wireMap.put(fragment, fragmentWires);
                }
            }
        }

        return wireMap;
    }

    private static Wire createWire(Requirement requirement, Candidates allCandidates)
    {
        Capability cand = allCandidates.getFirstCandidate(requirement);
        if (cand == null) {
            return null;
        }
        return new WireImpl(
            getDeclaredResource(requirement.getResource()),
            getDeclaredRequirement(requirement),
            getDeclaredResource(cand.getResource()),
            getDeclaredCapability(cand));
    }

    private static boolean isPayload(Requirement fragmentReq)
    {
        // this is where we would add other non-payload namespaces
        if (ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE
            .equals(fragmentReq.getNamespace()))
        {
            return false;
        }
        if (HostNamespace.HOST_NAMESPACE.equals(fragmentReq.getNamespace()))
        {
            return false;
        }
        return true;
    }

    private static Map<Resource, List<Wire>> populateDynamicWireMap(
            ResolveContext rc, Resource resource, Requirement dynReq,
            Map<Resource, List<Wire>> wireMap, Candidates allCandidates)
    {
        wireMap.put(resource, Collections.<Wire>emptyList());

        List<Wire> packageWires = new ArrayList<Wire>();

        // Get the candidates for the current dynamic requirement.
        // Record the dynamic candidate.
        Capability dynCand = allCandidates.getFirstCandidate(dynReq);

        if (!rc.getWirings().containsKey(dynCand.getResource()))
        {
            populateWireMap(rc, dynCand.getResource(), wireMap, allCandidates);
        }

        packageWires.add(
            new WireImpl(
                resource,
                dynReq,
                getDeclaredResource(dynCand.getResource()),
                getDeclaredCapability(dynCand)));

        wireMap.put(resource, packageWires);

        return wireMap;
    }

    private static void dumpResourcePkgMap(
        ResolveContext rc, Map<Resource, Packages> resourcePkgMap)
    {
        System.out.println("+++RESOURCE PKG MAP+++");
        for (Entry<Resource, Packages> entry : resourcePkgMap.entrySet())
        {
            dumpResourcePkgs(rc, entry.getKey(), entry.getValue());
        }
    }

    private static void dumpResourcePkgs(
        ResolveContext rc, Resource resource, Packages packages)
    {
        Wiring wiring = rc.getWirings().get(resource);
        System.out.println(resource
            + " (" + ((wiring != null) ? "RESOLVED)" : "UNRESOLVED)"));
        System.out.println("  EXPORTED");
        for (Entry<String, Blame> entry : packages.m_exportedPkgs.entrySet())
        {
            System.out.println("    " + entry.getKey() + " - " + entry.getValue());
        }
        System.out.println("  IMPORTED");
        for (Entry<String, List<Blame>> entry : packages.m_importedPkgs.entrySet())
        {
            System.out.println("    " + entry.getKey() + " - " + entry.getValue());
        }
        System.out.println("  REQUIRED");
        for (Entry<String, List<Blame>> entry : packages.m_requiredPkgs.entrySet())
        {
            System.out.println("    " + entry.getKey() + " - " + entry.getValue());
        }
        System.out.println("  USED");
        for (Entry<String, ArrayMap<Capability, UsedBlames>> entry : packages.m_usedPkgs.entrySet())
        {
            System.out.println("    " + entry.getKey() + " - " + entry.getValue().values());
        }
    }

    private static final class WireCandidate
    {
        public final Requirement requirement;
        public final Capability capability;

        public WireCandidate(Requirement requirement, Capability capability)
        {
            this.requirement = requirement;
            this.capability = capability;
        }
    }

    private static final class Packages
    {
        public final LinkedOpenHashMap<String, Blame> m_exportedPkgs;
        public final LinkedOpenHashMap<String, List<Blame>> m_importedPkgs;
        public final LinkedOpenHashMap<String, List<Blame>> m_requiredPkgs;
        public final LinkedOpenHashMap<String, ArrayMap<Capability, UsedBlames>> m_usedPkgs;

        public Packages(Resource resource)
        {
            int nbCaps = resource.getCapabilities(null).size();
            int nbReqs = resource.getRequirements(null).size();

            m_exportedPkgs = new LinkedOpenHashMap<String, Blame>(nbCaps);
            m_importedPkgs = new LinkedOpenHashMap<String, List<Blame>>(nbReqs) {
                public List<Blame> compute(String s) {
                    return new ArrayList<Blame>();
                }
            };
            m_requiredPkgs = new LinkedOpenHashMap<String, List<Blame>>(nbReqs) {
                public List<Blame> compute(String s) {
                    return new ArrayList<Blame>();
                }
            };
            m_usedPkgs = new LinkedOpenHashMap<String, ArrayMap<Capability, UsedBlames>>(128) {
                @Override
                protected ArrayMap<Capability, UsedBlames> compute(String s) {
                    return new ArrayMap<Capability, UsedBlames>() {
                        @Override
                        protected UsedBlames compute(Capability key) {
                            return new UsedBlames(key);
                        }
                    };
                }
            };
        }
    }

    private static final class Blame
    {
        public final Capability m_cap;
        public final List<Requirement> m_reqs;

        public Blame(Capability cap, List<Requirement> reqs)
        {
            m_cap = cap;
            m_reqs = reqs;
        }

        @Override
        public String toString()
        {
            return m_cap.getResource()
                + "." + m_cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)
                + (((m_reqs == null) || m_reqs.isEmpty())
                ? " NO BLAME"
                : " BLAMED ON " + m_reqs);
        }

        @Override
        public boolean equals(Object o)
        {
            return (o instanceof Blame) && m_reqs.equals(((Blame) o).m_reqs)
                && m_cap.equals(((Blame) o).m_cap);
        }
    }

    /*
     * UsedBlames hold a list of Blame that have a common used capability.
     * The UsedBlames stores sets of capabilities (root causes) that match a
     * root requirement with multiple cardinality.  These causes are the
     * capabilities that pulled in the common used capability.
     * It is assumed that multiple cardinality requirements can only be
     * root requirements of a Blame.
     *
     * This is only true because capabilities can only use a package
     * capability.  They cannot use any other kind of capability so we
     * do not have to worry about transitivity of the uses directive
     * from other capability types.
     */
    private static final class UsedBlames
    {
        public final Capability m_cap;
        public final List<Blame> m_blames = new ArrayList<ResolverImpl.Blame>();
        private Map<Requirement, Set<Capability>> m_rootCauses;

        public UsedBlames(Capability cap)
        {
            m_cap = cap;
        }

        public void addBlame(Blame blame, Capability matchingRootCause)
        {
            if (!m_cap.equals(blame.m_cap))
            {
                throw new IllegalArgumentException(
                    "Attempt to add a blame with a different used capability: "
                    + blame.m_cap);
            }
            m_blames.add(blame);
            if (matchingRootCause != null)
            {
                Requirement req = blame.m_reqs.get(0);
                // Assumption made that the root requirement of the chain is the only
                // possible multiple cardinality requirement and that the matching root cause
                // capability is passed down from the beginning of the chain creation.
                if (Util.isMultiple(req))
                {
                    // The root requirement is multiple. Need to store the root cause
                    // so that we can find it later in case the used capability which the cause
                    // capability pulled in is a conflict.
                    if (m_rootCauses == null)
                    {
                        m_rootCauses = new HashMap<Requirement, Set<Capability>>();
                    }
                    Set<Capability> rootCauses = m_rootCauses.get(req);
                    if (rootCauses == null)
                    {
                        rootCauses = new HashSet<Capability>();
                        m_rootCauses.put(req, rootCauses);
                    }
                    rootCauses.add(matchingRootCause);
                }
            }
        }

        public Set<Capability> getRootCauses(Requirement req)
        {
            if (m_rootCauses == null)
            {
                return Collections.emptySet();
            }
            Set<Capability> result = m_rootCauses.get(req);
            return result == null ? Collections.<Capability>emptySet() : result;
        }

        @Override
        public String toString()
        {
            return m_blames.toString();
        }
    }

    private static final class UseConstraintError extends ResolutionError {

        private final ResolveContext m_context;
        private final Candidates m_allCandidates;
        private final Resource m_resource;
        private final String m_pkgName;
        private final Blame m_blame1;
        private final Blame m_blame2;

        public UseConstraintError(ResolveContext context, Candidates allCandidates, Resource resource, String pkgName, Blame blame) {
            this(context, allCandidates, resource, pkgName, blame, null);
        }

        public UseConstraintError(ResolveContext context, Candidates allCandidates, Resource resource, String pkgName, Blame blame1, Blame blame2) {
            this.m_context = context;
            this.m_allCandidates = allCandidates;
            this.m_resource = resource;
            this.m_pkgName = pkgName;
            this.m_blame1 = blame1;
            this.m_blame2 = blame2;
        }

        public String getMessage() {
            if (m_blame2 == null)
            {
                return "Uses constraint violation. Unable to resolve resource "
                        + Util.getSymbolicName(m_resource)
                        + " [" + m_resource
                        + "] because it exports package '"
                        + m_pkgName
                        + "' and is also exposed to it from resource "
                        + Util.getSymbolicName(m_blame1.m_cap.getResource())
                        + " [" + m_blame1.m_cap.getResource()
                        + "] via the following dependency chain:\n\n"
                        + toStringBlame(m_blame1);
            }
            else
            {
                return  "Uses constraint violation. Unable to resolve resource "
                        + Util.getSymbolicName(m_resource)
                        + " [" + m_resource
                        + "] because it is exposed to package '"
                        + m_pkgName
                        + "' from resources "
                        + Util.getSymbolicName(m_blame1.m_cap.getResource())
                        + " [" + m_blame1.m_cap.getResource()
                        + "] and "
                        + Util.getSymbolicName(m_blame2.m_cap.getResource())
                        + " [" + m_blame2.m_cap.getResource()
                        + "] via two dependency chains.\n\nChain 1:\n"
                        + toStringBlame(m_blame1)
                        + "\n\nChain 2:\n"
                        + toStringBlame(m_blame2);
            }
        }

        public Collection<Requirement> getUnresolvedRequirements() {
            if (m_blame2 == null)
            {
                return Collections.emptyList();
            }
            else
            {
                return Collections.singleton(m_blame2.m_reqs.get(0));
            }
        }

        private String toStringBlame(Blame blame)
        {
            StringBuilder sb = new StringBuilder();
            if ((blame.m_reqs != null) && !blame.m_reqs.isEmpty())
            {
                for (int i = 0; i < blame.m_reqs.size(); i++)
                {
                    Requirement req = blame.m_reqs.get(i);
                    sb.append("  ");
                    sb.append(Util.getSymbolicName(req.getResource()));
                    sb.append(" [");
                    sb.append(req.getResource().toString());
                    sb.append("]\n");
                    if (req.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
                    {
                        sb.append("    import: ");
                    }
                    else
                    {
                        sb.append("    require: ");
                    }
                    sb.append(req.getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE));
                    sb.append("\n     |");
                    if (req.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
                    {
                        sb.append("\n    export: ");
                    }
                    else
                    {
                        sb.append("\n    provide: ");
                    }
                    if ((i + 1) < blame.m_reqs.size())
                    {
                        Capability cap = getSatisfyingCapability(blame.m_reqs.get(i));
                        if (cap.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE))
                        {
                            sb.append(PackageNamespace.PACKAGE_NAMESPACE);
                            sb.append("=");
                            sb.append(cap.getAttributes()
                                    .get(PackageNamespace.PACKAGE_NAMESPACE).toString());
                            Capability usedCap =
                                    getSatisfyingCapability(blame.m_reqs.get(i + 1));
                            sb.append("; uses:=");
                            sb.append(usedCap.getAttributes()
                                    .get(PackageNamespace.PACKAGE_NAMESPACE));
                        }
                        else
                        {
                            sb.append(cap);
                        }
                        sb.append("\n");
                    }
                    else
                    {
                        Capability export = getSatisfyingCapability(blame.m_reqs.get(i));
                        sb.append(export.getNamespace());
                        sb.append(": ");
                        Object namespaceVal = export.getAttributes().get(export.getNamespace());
                        if (namespaceVal != null)
                        {
                            sb.append(namespaceVal.toString());
                        }
                        else
                        {
                            for (Entry<String, Object> attrEntry : export.getAttributes().entrySet())
                            {
                                sb.append(attrEntry.getKey()).append('=')
                                        .append(attrEntry.getValue()).append(';');
                            }
                        }
                        if (export.getNamespace().equals(PackageNamespace.PACKAGE_NAMESPACE)
                                && !export.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)
                                .equals(blame.m_cap.getAttributes().get(
                                        PackageNamespace.PACKAGE_NAMESPACE)))
                        {
                            sb.append("; uses:=");
                            sb.append(blame.m_cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE));
                            sb.append("\n    export: ");
                            sb.append(PackageNamespace.PACKAGE_NAMESPACE);
                            sb.append("=");
                            sb.append(blame.m_cap.getAttributes()
                                    .get(PackageNamespace.PACKAGE_NAMESPACE).toString());
                        }
                        sb.append("\n  ");
                        sb.append(Util.getSymbolicName(blame.m_cap.getResource()));
                        sb.append(" [");
                        sb.append(blame.m_cap.getResource().toString());
                        sb.append("]");
                    }
                }
            }
            else
            {
                sb.append(blame.m_cap.getResource().toString());
            }
            return sb.toString();
        }

        private Capability getSatisfyingCapability(Requirement req)
        {
            // If the requiring revision is not resolved, then check in the
            // candidate map for its matching candidate.
            Capability cap = m_allCandidates.getFirstCandidate(req);
            // Otherwise, if the requiring revision is resolved then check
            // in its wires for the capability satisfying the requirement.
            if (cap == null && m_context.getWirings().containsKey(req.getResource()))
            {
                List<Wire> wires =
                        m_context.getWirings().get(req.getResource()).getRequiredResourceWires(null);
                req = getDeclaredRequirement(req);
                for (Wire w : wires)
                {
                    if (w.getRequirement().equals(req))
                    {
                        // TODO: RESOLVER - This is not 100% correct, since requirements for
                        //       dynamic imports with wildcards will reside on many wires and
                        //       this code only finds the first one, not necessarily the correct
                        //       one. This is only used for the diagnostic message, but it still
                        //       could confuse the user.
                        cap = w.getCapability();
                        break;
                    }
                }
            }

            return cap;
        }

    }

}
