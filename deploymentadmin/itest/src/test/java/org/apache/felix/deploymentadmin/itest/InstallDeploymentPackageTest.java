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
package org.apache.felix.deploymentadmin.itest;

import org.apache.felix.deploymentadmin.itest.util.DeploymentPackageBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.osgi.framework.Bundle;
import org.osgi.service.deploymentadmin.BundleInfo;
import org.osgi.service.deploymentadmin.DeploymentException;
import org.osgi.service.deploymentadmin.DeploymentPackage;

/**
 * Provides test cases regarding the use of "normal" deployment packages in DeploymentAdmin.
 */
@RunWith(PaxExam.class)
public class InstallDeploymentPackageTest extends BaseIntegrationTest
{
    /**
     * FELIX-4409/4410/4463 - test the installation of an invalid deployment package.
     */
    @Test
    public void testInstallInvalidDeploymentPackageFail() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        // incluse two different versions of the same bundle (with the same BSN), this is *not* allowed per the DA spec...
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundleapi1", "bundleapi1", "1.0.0")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundleapi2", "bundleapi2", "2.0.0")));

        try
        {
            installDeploymentPackage(dpBuilder);
            fail("DeploymentException expected!");
        }
        catch (DeploymentException e)
        {
            // Ok; expected...
        }

        // Verify that none of the bundles are installed...
        assertBundleNotExists("testbundles.bundleapi", "1.0.0");
        assertBundleNotExists("testbundles.bundleapi", "2.0.0");
    }

    /**
     * FELIX-1835 - test whether we can install bundles with a non-root path inside the DP.
     */
    @Test
    public void testInstallBundlesWithPathsOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        // incluse two different versions of the same bundle (with the same BSN), this is *not* allowed per the DA spec...
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundleapi1", "bundleapi1", "1.0.0")).setFilename("bundles/bundleapi1.jar"))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundleimpl1", "bundleimpl1", "1.0.0")).setFilename("bundles/bundleimpl1.jar"));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull(dp);

        BundleInfo[] bundleInfos = dp.getBundleInfos();
        assertEquals(2, bundleInfos.length);

        // Verify that none of the bundles are installed...
        assertBundleExists("testbundles.bundleapi", "1.0.0");
        assertBundleExists("testbundles.bundleimpl", "1.0.0");
    }

    /**
     * Tests that adding the dependency for a bundle in an update package causes the depending bundle to be resolved and started.
     */
    @Test
    public void testInstallBundleWithDependencyInPackageUpdateOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        // missing bundle1 as dependency...
        dpBuilder.add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle2")));

        DeploymentPackage dp1 = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp1);

        awaitRefreshPackagesEvent();

        Bundle bundle = dp1.getBundle(getSymbolicName("bundle2"));
        assertNotNull("Failed to obtain bundle from deployment package?!", bundle);

        assertTrue(isBundleInstalled(dp1.getBundle(getSymbolicName("bundle2"))));

        dpBuilder = createDeploymentPackageBuilder(dpBuilder.getSymbolicName(), "1.0.1");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle2")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")));

        DeploymentPackage dp2 = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp2);

        awaitRefreshPackagesEvent();

        assertTrue(isBundleActive(dp2.getBundle(getSymbolicName("bundle1"))));
        assertTrue(isBundleActive(dp2.getBundle(getSymbolicName("bundle2"))));
    }

    /**
     * Tests that installing a bundle with a dependency installed by another deployment package is not started, but is resolved.
     */
    @Test
    public void testInstallBundleWithDependencyInSeparatePackageOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder.add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle2")));

        DeploymentPackage dp1 = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp1);

        awaitRefreshPackagesEvent();

        assertBundleExists(getSymbolicName("bundle2"), "1.0.0");

        // We shouldn't be able to resolve the deps for bundle2...
        assertFalse(resolveBundles(dp1.getBundle(getSymbolicName("bundle2"))));

        assertTrue(isBundleInstalled(dp1.getBundle(getSymbolicName("bundle2"))));

        dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        // as missing bundle1...
        dpBuilder.add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")));

        DeploymentPackage dp2 = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp2);

        awaitRefreshPackagesEvent();

        assertBundleExists(getSymbolicName("bundle1"), "1.0.0");
        assertBundleExists(getSymbolicName("bundle2"), "1.0.0");

        // Now we should be able to resolve the dependencies for bundle2...
        assertTrue(resolveBundles(dp1.getBundle(getSymbolicName("bundle2"))));

        assertTrue(isBundleActive(dp2.getBundle(getSymbolicName("bundle1"))));
        assertTrue(isBundleResolved(dp1.getBundle(getSymbolicName("bundle2"))));
    }

    /**
     * Tests that if an exception is thrown in the start method of a bundle, the installation is not rolled back.
     */
    @Test
    public void testInstallBundleWithExceptionThrownInStartCausesNoRollbackOk() throws Exception
    {
        System.setProperty("bundle3", "start");

        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle3")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        assertBundleExists(getSymbolicName("bundle1"), "1.0.0");
        assertBundleExists(getSymbolicName("bundle3"), "1.0.0");

        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle1"))));
        // the bundle threw an exception during start, so it is not active...
        assertFalse(isBundleActive(dp.getBundle(getSymbolicName("bundle3"))));

        assertEquals("Expected a single deployment package?!", 1, countDeploymentPackages());
    }

    /**
     * Tests that installing a bundle along with a fragment bundle succeeds (DA should not try to start the fragment, see FELIX-4167).
     */
    @Test
    public void testInstallBundleWithFragmentOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("fragment1")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        assertBundleExists(getSymbolicName("bundle1"), "1.0.0");
        assertBundleExists(getSymbolicName("fragment1"), "1.0.0");

        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle1"))));
        assertFalse(isBundleActive(dp.getBundle(getSymbolicName("fragment1"))));

        assertEquals("Expected a single deployment package?!", 1, countDeploymentPackages());
    }

    /**
     * Tests that installing a bundle whose dependencies cannot be met, is installed, but not started.
     */
    @Test
    public void testInstallBundleWithMissingDependencyOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder.add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle2")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        Bundle bundle = dp.getBundle(getSymbolicName("bundle2"));
        assertNotNull("Failed to obtain bundle from deployment package?!", bundle);

        assertBundleExists(getSymbolicName("bundle2"), "1.0.0");

        assertTrue(isBundleInstalled(dp.getBundle(getSymbolicName("bundle2"))));
    }

    /**
     * Tests that installing a bundle along with other (non-bundle) artifacts succeeds.
     */
    @Test
    public void testInstallBundleWithOtherArtifactsOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder
            .add(dpBuilder.createResourceProcessorResource().setUrl(getTestBundle("rp1")))
            .add(dpBuilder.createResource().setResourceProcessorPID(TEST_FAILING_BUNDLE_RP1).setUrl(getTestResource("test-config1.xml")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle3")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        // Though the commit failed; the package should be installed...
        assertBundleExists(getSymbolicName("rp1"), "1.0.0");
        assertBundleExists(getSymbolicName("bundle3"), "1.0.0");

        assertEquals("Expected a single deployment package?!", 1, countDeploymentPackages());
    }

    /**
     * Tests that installing a new bundle works as expected.
     */
    @Test
    public void testInstallSingleValidBundleOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder.add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        assertNotNull("Failed to obtain test service?!", awaitService(TEST_SERVICE_NAME));

        assertBundleExists(getSymbolicName("bundle1"), "1.0.0");
        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle1"))));
    }

    /**
     * Tests that installing two bundles works as expected.
     */
    @Test
    public void testInstallTwoValidBundlesOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle2")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        assertNotNull("Failed to obtain test service?!", awaitService(TEST_SERVICE_NAME));

        assertBundleExists(getSymbolicName("bundle1"), "1.0.0");
        assertBundleExists(getSymbolicName("bundle2"), "1.0.0");

        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle1"))));
        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle2"))));
    }

    /**
     * Tests that if an exception is thrown during the uninstall of a bundle, the installation/update continues and succeeds.
     */
    @Test
    public void testUninstallBundleWithExceptionThrownInStopCauseNoRollbackOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle3")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        assertBundleExists(getSymbolicName("bundle3"), "1.0.0");

        System.setProperty("bundle3", "stop");

        dpBuilder = dpBuilder.create("1.0.1");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle2")));

        dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        assertBundleExists(getSymbolicName("bundle1"), "1.0.0");
        assertBundleExists(getSymbolicName("bundle2"), "1.0.0");
        assertBundleNotExists(getSymbolicName("bundle3"), "1.0.0");

        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle1"))));
        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle2"))));

        assertEquals("Expected a single deployment package?!", 1, countDeploymentPackages());
    }

    /**
     * Tests that if an exception is thrown during the stop of a bundle, the installation/update continues and succeeds.
     */
    @Test
    public void testUpdateBundleWithExceptionThrownInStopCauseNoRollbackOk() throws Exception
    {
        DeploymentPackageBuilder dpBuilder = createNewDeploymentPackageBuilder("1.0.0");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle3")));

        DeploymentPackage dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        awaitRefreshPackagesEvent();

        assertBundleExists(getSymbolicName("bundle3"), "1.0.0");

        System.setProperty("bundle3", "stop");

        dpBuilder = createDeploymentPackageBuilder(dpBuilder.getSymbolicName(), "1.0.1");
        dpBuilder
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle1")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle2")))
            .add(dpBuilder.createBundleResource().setUrl(getTestBundle("bundle3")));

        dp = installDeploymentPackage(dpBuilder);
        assertNotNull("No deployment package returned?!", dp);

        assertBundleExists(getSymbolicName("bundle1"), "1.0.0");
        assertBundleExists(getSymbolicName("bundle2"), "1.0.0");
        assertBundleExists(getSymbolicName("bundle3"), "1.0.0");

        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle1"))));
        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle2"))));
        assertTrue(isBundleActive(dp.getBundle(getSymbolicName("bundle3"))));

        assertEquals("Expected a single deployment package?!", 1, countDeploymentPackages());
    }
}
