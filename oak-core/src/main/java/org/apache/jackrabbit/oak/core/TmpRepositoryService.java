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
package org.apache.jackrabbit.oak.core;

import org.apache.jackrabbit.mk.MicroKernelFactory;
import org.apache.jackrabbit.mk.api.MicroKernel;
import org.apache.jackrabbit.oak.api.Connection;
import org.apache.jackrabbit.oak.kernel.NodeState;
import org.apache.jackrabbit.oak.api.NodeStateEditor;
import org.apache.jackrabbit.oak.api.QueryEngine;
import org.apache.jackrabbit.oak.api.RepositoryService;
import org.apache.jackrabbit.oak.kernel.KernelNodeStore;
import org.apache.jackrabbit.oak.query.QueryEngineImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.GuestCredentials;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.SimpleCredentials;
import javax.security.auth.login.LoginException;

/**
 * TmpRepositoryService...
 */
public class TmpRepositoryService implements RepositoryService {

    /**
     * logger instance
     */
    private static final Logger log = LoggerFactory.getLogger(TmpRepositoryService.class);

    // TODO: retrieve default wsp-name from configuration
    private static final String DEFAULT_WORKSPACE_NAME = "default";

    private final MicroKernel microKernel;
    private final KernelNodeStore nodeStore;

    public TmpRepositoryService(String microKernelUrl) {
        microKernel = MicroKernelFactory.getInstance(microKernelUrl);
        nodeStore = new KernelNodeStore(microKernel);
    }

    @Override
    public Connection login(Object credentials, String workspaceName)
            throws LoginException, NoSuchWorkspaceException {

        // TODO: add proper implementation
        // TODO  - authentication against configurable spi-authentication
        // TODO  - validation of workspace name (including access rights for the given 'user')

        final SimpleCredentials sc;
        if (credentials == null || credentials instanceof GuestCredentials) {
            sc = new SimpleCredentials("anonymous", new char[0]);
        } else if (credentials instanceof SimpleCredentials) {
            sc = (SimpleCredentials) credentials;
        } else {
            sc = null;
        }
        
        if (sc == null) {
            throw new LoginException("login failed");
        }

        final String wspName = (workspaceName == null) ? DEFAULT_WORKSPACE_NAME : workspaceName;
        final String revision = getRevision(credentials);

        // FIXME: workspace setup must be done elsewhere...
        if (wspName.equals(DEFAULT_WORKSPACE_NAME)) {
            NodeState root = nodeStore.getRoot();
            NodeState wspNode = root.getChildNode(wspName);
            if (wspNode == null) {
                NodeStateEditor editor = nodeStore.branch(root);
                editor.addNode(wspName);
                nodeStore.merge(editor);
            }
        }

        QueryEngine queryEngine = new QueryEngineImpl(microKernel);
        return ConnectionImpl.createWorkspaceConnection(sc, wspName, nodeStore, revision,
                queryEngine);
    }

    /**
     * @param credentials The credentials object used for authentication.
     * @return The microkernel revision or {@code null} if the give credentials doesn't
     * specify a specific revision number.
     */
    private String getRevision(Object credentials) {
        // TODO: define if/how desired revision can be passed in by the credentials object.
        return null;
    }
}