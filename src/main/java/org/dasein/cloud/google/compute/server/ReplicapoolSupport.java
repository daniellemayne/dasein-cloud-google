/**
 * Copyright (C) 2012-2014 Dell, Inc
 * See annotations for authorship information
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.google.compute.server;

import java.io.IOException;

import javax.annotation.Nonnull;

import com.google.api.services.replicapool.Replicapool;
import com.google.api.services.replicapool.model.InstanceGroupManager;
import com.google.api.services.replicapool.model.InstanceGroupManagerList;
import com.google.api.services.replicapool.model.Operation;

import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.ci.AbstractConvergedInfrastructureSupport;
import org.dasein.cloud.ci.CIFilterOptions;
import org.dasein.cloud.ci.CIProvisionOptions;
import org.dasein.cloud.ci.ConvergedInfrastructure;
import org.dasein.cloud.google.Google;
import org.dasein.cloud.google.GoogleMethod;
import org.dasein.cloud.google.GoogleOperationType;
import org.dasein.cloud.google.capabilities.GCEReplicapoolCapabilities;
import org.dasein.cloud.util.APITrace;
import org.apache.log4j.Logger;

/**
 * Implements the replicapool services supported in the Google API.
 * @author Roger Unwin
 * @version 2015.03 initial version
 * @since 2015.03
 */
public class ReplicapoolSupport extends AbstractConvergedInfrastructureSupport <Google> {
    static private final Logger logger = Google.getLogger(ReplicapoolSupport.class);
    private Google provider = null;

    public ReplicapoolSupport(Google provider) {
        super(provider);
        this.provider = provider;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "GoogleConvergedInfrastructure.isSubscribed");
        try {
            return true;  // cop-out for now.
        } finally{
            APITrace.end();
        }
    }

    // template CRUD in here or in Template class? 

    /*
    public CIProvisionOptions createCITemplate(@Nonnull String topologyId) {
        TopologyProvisionOptions withTopologyOptions = TopologyProvisionOptions.getInstance(productName, productDescription, machineType, canIpForward);
        GoogleTopologySupport topology = GoogleTopologySupport.createTopology(withTopologyOptions);
        ReplicapoolTemplate template = new ReplicapoolTemplate(topologyId, null, false, false, null, null, null, false, false);
        boolean success = template.create(provider);
        int size;
        String description;
        CIProvisionOptions foo = CIProvisionOptions.getInstance(topologyId, name, description, zone, size, template.getSelfLink());
        return foo;
    }
    */

    public boolean deleteCITemplate(@Nonnull String topologyId) {
        return false;
        //ReplicapoolTemplate
    }

    private transient volatile GCEReplicapoolCapabilities capabilities;

    public @Nonnull GCEReplicapoolCapabilities getCapabilities() {
        if( capabilities == null ) {
            capabilities = new GCEReplicapoolCapabilities();
        }
        return capabilities;
    }

    @Override
    public Iterable<ConvergedInfrastructure> listConvergedInfrastructures(CIFilterOptions options) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "GoogleConvergedInfrastructure.listConvergedInfrastructures");
        try {
             Replicapool rp = provider.getGoogleReplicapool();
             InstanceGroupManagerList result = null;
             try {
                 // WAIT FOR IT TO APPEAR IN CONSOLE
                 result = rp.instanceGroupManagers().list(provider.getContext().getAccountNumber(), "us-central1-f").execute(); //provider.getContext().getRegionId()
             } catch ( IOException e ) {
                 e.printStackTrace();
             }
             System.out.println(result.isEmpty());
            return null;
        } finally{
            APITrace.end();
        }
    }

    @Override
    public Iterable<String> listVirtualMachines(String inCIId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "GoogleConvergedInfrastructure.listVirtualMachines");
        try {
            // TODO Auto-generated method stub
            return null;
        } finally{
            APITrace.end();
        }
    }

    @Override
    public Iterable<String> listVLANs(String inCIId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "GoogleConvergedInfrastructure.listVLANs");
        try {
            // TODO Auto-generated method stub
            return null;
        } finally{
            APITrace.end();
        }
    }

    /*
     * Create a replicaPool based on options in CIProvisionOptions options
     * @see org.dasein.cloud.ci.ConvergedInfrastructureSupport#provision(org.dasein.cloud.ci.CIProvisionOptions)
     */
    @Override
    public ConvergedInfrastructure provision(CIProvisionOptions options) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "GoogleConvergedInfrastructure.provision");
        Replicapool rp = provider.getGoogleReplicapool();
        try {
            ProviderContext ctx = provider.getContext();
            InstanceGroupManager content = new InstanceGroupManager();
            content.setBaseInstanceName(options.getBaseInstanceName());
            content.setDescription(options.getDescription());
            content.setInstanceTemplate(options.getInstanceTemplate());
            content.setName(options.getName());
            //content.setTargetPools(targetPools);
            Operation job = rp.instanceGroupManagers().insert(ctx.getAccountNumber(), options.getZone(), options.getSize(), content).execute();
            System.out.println("inspect result");
            GoogleMethod method = new GoogleMethod(provider);
            method.getCIOperationComplete(ctx, job, GoogleOperationType.ZONE_OPERATION, "us-central1", options.getZone());
            // TODO Auto-generated method stub
            //return ConvergedInfrastructure.getInstance(ownerId, regionId, ciId, state, name, description);
            listConvergedInfrastructures(null);
            return null;  // clean up later
        } catch ( IOException e ) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally{
            APITrace.end();
        }
        return null;
    }

    @Override
    public void terminate(String ciId, String explanation) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "GoogleConvergedInfrastructure.provision");
        try {
            // TODO Auto-generated method stub
        } finally{
            APITrace.end();
        }
    }


}
