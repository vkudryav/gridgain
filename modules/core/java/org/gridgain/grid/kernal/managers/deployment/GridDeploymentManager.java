/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.managers.deployment;

import org.gridgain.grid.compute.*;
import org.gridgain.grid.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.*;
import org.gridgain.grid.kernal.managers.deployment.protocol.gg.*;
import org.gridgain.grid.kernal.processors.task.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.spi.deployment.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.*;

import static org.gridgain.grid.GridDeploymentMode.*;

/**
 * Deployment manager.
 */
public class GridDeploymentManager extends GridManagerAdapter<GridDeploymentSpi> {
    /** Local deployment storage. */
    private GridDeploymentStore locStore;

    /** Isolated mode storage. */
    private GridDeploymentStore ldrStore;

    /** Shared mode storage. */
    private GridDeploymentStore verStore;

    /** */
    private GridDeploymentCommunication comm;

    /** */
    private final GridDeployment locDep;

    /**
     * @param ctx Grid kernal context.
     */
    public GridDeploymentManager(GridKernalContext ctx) {
        super(ctx, ctx.config().getDeploymentSpi());

        if (!ctx.config().isPeerClassLoadingEnabled()) {
            GridDeploymentSpi spi = ctx.config().getDeploymentSpi();

            GridIgnoreIfPeerClassLoadingDisabled ann = U.getAnnotation(spi.getClass(),
                GridIgnoreIfPeerClassLoadingDisabled.class);

            locDep = ann != null ?
                new LocalDeployment(
                    ctx.config().getDeploymentMode(),
                    U.gridClassLoader(),
                    GridUuid.fromUuid(ctx.localNodeId()),
                    U.getUserVersion(U.gridClassLoader(), log),
                    String.class.getName()) :
                null;
        }
        else
            locDep = null;
    }

    /** {@inheritDoc} */
    @Override public void start() throws GridException {
        GridProtocolHandler.registerDeploymentManager(this);

        assertParameter(ctx.config().getDeploymentMode() != null, "ctx.config().getDeploymentMode() != null");

        if (ctx.config().isPeerClassLoadingEnabled())
            assertParameter(ctx.config().getNetworkTimeout() > 0, "networkTimeout > 0");

        startSpi();

        comm = new GridDeploymentCommunication(ctx, log);

        comm.start();

        locStore = new GridDeploymentLocalStore(getSpi(), ctx, comm);
        ldrStore = new GridDeploymentPerLoaderStore(getSpi(), ctx, comm);
        verStore = new GridDeploymentPerVersionStore(getSpi(), ctx, comm);

        locStore.start();
        ldrStore.start();
        verStore.start();

        if (log.isDebugEnabled()) {
            log.debug("Local deployment: " + locDep);

            log.debug(startInfo());
        }
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) throws GridException {
        GridProtocolHandler.deregisterDeploymentManager();

        if (verStore != null)
            verStore.stop();

        if (ldrStore != null)
            ldrStore.stop();

        if (locStore != null)
            locStore.stop();

        if (comm != null)
            comm.stop();

        getSpi().setListener(null);

        stopSpi();

        if (log.isDebugEnabled())
            log.debug(stopInfo());
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart0() throws GridException {
        locStore.onKernalStart();
        ldrStore.onKernalStart();
        verStore.onKernalStart();
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop0(boolean cancel) {
        if (verStore != null)
            verStore.onKernalStop();

        if (ldrStore != null)
            ldrStore.onKernalStop();

        if (locStore != null)
            locStore.onKernalStop();
    }

    /** {@inheritDoc} */
    @Override public boolean enabled() {
        return super.enabled() && locDep == null;
    }

    /**
     * @param p Filtering predicate.
     * @return All deployed tasks for given predicate.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Class<? extends GridComputeTask<?, ?>>> findAllTasks(
        @Nullable GridPredicate<? super Class<? extends GridComputeTask<?, ?>>>... p) {
        Map<String, Class<? extends GridComputeTask<?, ?>>> map = new HashMap<>();
        if (locDep != null)
            tasks(map, locDep, p);
        else {
            Collection<GridDeployment> deps = locStore.getDeployments();

            for (GridDeployment dep : deps)
                tasks(map, dep, p);
        }

        return map;
    }

    /**
     * @param map Map (out parameter).
     * @param dep Deployment.
     * @param p Predicate.
     */
    private void tasks(Map<String, Class<? extends GridComputeTask<?, ?>>> map, GridDeployment dep,
        GridPredicate<? super Class<? extends GridComputeTask<?, ?>>>[] p) {
        assert map != null;
        assert dep != null;

        for (Map.Entry<String, Class<?>> clsEntry : dep.deployedClassMap().entrySet()) {
            if (GridComputeTask.class.isAssignableFrom(clsEntry.getValue())) {
                Class<? extends GridComputeTask<?, ?>> taskCls = (Class<? extends GridComputeTask<?, ?>>)clsEntry.getValue();

                if (F.isAll(taskCls, p))
                    map.put(clsEntry.getKey(), taskCls);
            }
        }
    }

    /**
     * @param taskName Task name.
     * @param locUndeploy Local undeploy flag.
     * @param rmtNodes Nodes to send request to.
     */
    public void undeployTask(String taskName, boolean locUndeploy, Collection<GridNode> rmtNodes) {
        assert taskName != null;
        assert !rmtNodes.contains(ctx.discovery().localNode());

        if (locDep == null) {
            if (locUndeploy)
                locStore.explicitUndeploy(null, taskName);

            try {
                comm.sendUndeployRequest(taskName, rmtNodes);
            }
            catch (GridException e) {
                U.error(log, "Failed to send undeployment request for task: " + taskName, e);
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @param taskName Task name.
     */
    void undeployTask(UUID nodeId, String taskName) {
        assert taskName != null;

        if (locDep != null) {
            U.warn(log, "Received unexpected undeploy request [nodeId=" + nodeId + ", taskName=" + taskName + ']');

            return;
        }

        locStore.explicitUndeploy(nodeId, taskName);
        ldrStore.explicitUndeploy(nodeId, taskName);
        verStore.explicitUndeploy(nodeId, taskName);
    }

    /**
     * @param cls Class to deploy.
     * @param clsLdr Class loader.
     * @throws GridException If deployment failed.
     * @return Grid deployment.
     */
    @Nullable public GridDeployment deploy(Class<?> cls, ClassLoader clsLdr) throws GridException {
        if (clsLdr == null)
            clsLdr = getClass().getClassLoader();

        if (clsLdr instanceof GridDeploymentClassLoader) {
            GridDeploymentInfo ldr = (GridDeploymentInfo)clsLdr;

            // Expecting that peer-deploy awareness handled on upper level.
            if ((ldr.deployMode() == ISOLATED || ldr.deployMode() == PRIVATE) &&
                (ctx.config().getDeploymentMode() == SHARED || ctx.config().getDeploymentMode() == CONTINUOUS) &&
                !U.hasAnnotation(cls, GridInternal.class))
                throw new GridException("Attempt to deploy class loaded in ISOLATED or PRIVATE mode on node with " +
                    "SHARED or CONTINUOUS deployment mode [cls=" + cls + ", clsDeployMode=" + ldr.deployMode() +
                    ", localDeployMode=" + ctx.config().getDeploymentMode() + ']');

            GridDeploymentMetadata meta = new GridDeploymentMetadata();

            meta.alias(cls.getName());
            meta.classLoader(clsLdr);

            // Check for nested execution. In that case, if task
            // is available locally by name, then we should ignore
            // class loader ID.
            GridDeployment dep = locStore.getDeployment(meta);

            if (dep == null) {
                dep = ldrStore.getDeployment(ldr.classLoaderId());

                if (dep == null)
                    dep = verStore.getDeployment(ldr.classLoaderId());
            }

            return dep;
        }
        else
            return locDep != null ? locDep : locStore.explicitDeploy(cls, clsLdr);
    }

    /**
     * Gets class loader based on given ID.
     *
     * @param ldrId Class loader ID.
     * @return Class loader of {@code null} if not found.
     */
    @Nullable public GridDeployment getLocalDeployment(GridUuid ldrId) {
        if (locDep != null)
            return locDep.classLoaderId().equals(ldrId) ? locDep : null;
        else
            return locStore.getDeployment(ldrId);
    }

    /**
     * Gets any deployment by loader ID.
     *
     * @param ldrId Loader ID.
     * @return Deployment for given ID.
     */
    @Nullable public GridDeployment getDeployment(GridUuid ldrId) {
        if (locDep != null)
           return locDep.classLoaderId().equals(ldrId) ? locDep : null;

        GridDeployment dep = locStore.getDeployment(ldrId);

        if (dep == null) {
            dep = ldrStore.getDeployment(ldrId);

            if (dep == null)
                dep = verStore.getDeployment(ldrId);
        }

        return dep;
    }

    /**
     * @param rsrcName Resource to find deployment for.
     * @return Found deployment or {@code null} if one was not found.
     */
    @Nullable public GridDeployment getDeployment(String rsrcName) {
        if (locDep != null)
            return locDep;

        GridDeployment dep = getLocalDeployment(rsrcName);

        if (dep == null) {
            ClassLoader ldr = Thread.currentThread().getContextClassLoader();

            if (ldr instanceof GridDeploymentClassLoader) {
                GridDeploymentInfo depLdr = (GridDeploymentInfo)ldr;

                dep = ldrStore.getDeployment(depLdr.classLoaderId());

                if (dep == null)
                    dep = verStore.getDeployment(depLdr.classLoaderId());
            }
        }

        return dep;
    }

    /**
     * @param rsrcName Class name.
     * @return Grid cached task.
     */
    @Nullable public GridDeployment getLocalDeployment(String rsrcName) {
        if (locDep != null)
            return locDep;

        GridDeploymentMetadata meta = new GridDeploymentMetadata();

        meta.record(true);
        meta.deploymentMode(ctx.config().getDeploymentMode());
        meta.alias(rsrcName);
        meta.className(rsrcName);
        meta.senderNodeId(ctx.localNodeId());

        return locStore.getDeployment(meta);
    }

    /**
     * @param depMode Deployment mode.
     * @param rsrcName Resource name (could be task name).
     * @param clsName Class name.
     * @param userVer User version.
     * @param sndNodeId Sender node ID.
     * @param clsLdrId Class loader ID.
     * @param participants Node class loader participant map.
     * @param nodeFilter Node filter for class loader.
     * @return Deployment class if found.
     */
    @Nullable public GridDeployment getGlobalDeployment(
        GridDeploymentMode depMode,
        String rsrcName,
        String clsName,
        String userVer,
        UUID sndNodeId,
        GridUuid clsLdrId,
        Map<UUID, GridUuid> participants,
        @Nullable GridPredicate<GridNode> nodeFilter) {
        if (locDep != null)
            return locDep;

        GridDeploymentMetadata meta = new GridDeploymentMetadata();

        meta.deploymentMode(depMode);
        meta.className(clsName);
        meta.alias(rsrcName);
        meta.userVersion(userVer);
        meta.senderNodeId(sndNodeId);
        meta.classLoaderId(clsLdrId);
        meta.participants(participants);
        meta.nodeFilter(nodeFilter);

        if (!ctx.config().isPeerClassLoadingEnabled()) {
            meta.record(true);

            return locStore.getDeployment(meta);
        }

        // In shared mode, if class is locally available, we never load
        // from remote node simply because the class loader needs to be "shared".
        if (isPerVersionMode(meta.deploymentMode())) {
            meta.record(true);

            boolean reuse = true;

            // Check local exclusions.
            if (!sndNodeId.equals(ctx.localNodeId())) {
                String[] p2pExc = ctx.config().getPeerClassLoadingLocalClassPathExclude();

                if (p2pExc != null) {
                    for (String rsrc : p2pExc) {
                        // Remove star (*) at the end.
                        if (rsrc.endsWith("*"))
                            rsrc = rsrc.substring(0, rsrc.length() - 1);

                        if (meta.alias().startsWith(rsrc) || meta.className().startsWith(rsrc)) {
                            if (log.isDebugEnabled())
                                log.debug("Will not reuse local deployment because resource is excluded [meta=" +
                                    meta + ']');

                            reuse = false;

                            break;
                        }
                    }
                }
            }

            if (reuse) {
                GridDeployment locDep = locStore.getDeployment(meta);

                if (locDep == null && participants != null && participants.containsKey(ctx.localNodeId()))
                    locDep = locStore.getDeployment(participants.get(ctx.localNodeId()));

                if (locDep != null) {
                    if (!isPerVersionMode(locDep.deployMode())) {
                        U.warn(log, "Failed to deploy class in SHARED or CONTINUOUS mode (class is locally deployed " +
                            "in some other mode). Either change GridConfiguration.getDeploymentMode() property to " +
                            "SHARED or CONTINUOUS or remove class from local classpath and any of " +
                            "the local GAR deployments that may have it [cls=" + meta.className() + ", depMode=" +
                            locDep.deployMode() + ']', "Failed to deploy class in SHARED or CONTINUOUS mode.");

                        return null;
                    }

                    if (!locDep.userVersion().equals(meta.userVersion())) {
                        U.warn(log, "Failed to deploy class in SHARED or CONTINUOUS mode for given user version " +
                            "(class is locally deployed for a different user version) [cls=" + meta.className() +
                            ", localVer=" + locDep.userVersion() + ", otherVer=" + meta.userVersion() + ']',
                            "Failed to deploy class in SHARED or CONTINUOUS mode.");

                        return null;
                    }

                    if (log.isDebugEnabled())
                        log.debug("Reusing local deployment for SHARED or CONTINUOUS mode: " + locDep);

                    return locDep;
                }
            }

            return verStore.getDeployment(meta);
        }

        // Private or Isolated mode.
        meta.record(false);

        GridDeployment dep = locStore.getDeployment(meta);

        if (sndNodeId.equals(ctx.localNodeId())) {
            if (dep == null)
                U.warn(log, "Task got undeployed while deployment was in progress: " + meta);

            // For local execution, return the same deployment as for the task.
            return dep;
        }

        if (dep != null)
            meta.parentLoader(dep.classLoader());

        meta.record(true);

        return ldrStore.getDeployment(meta);
    }

    /**
     * Adds participants to all SHARED deployments.
     *
     * @param allParticipants All participants.
     * @param addedParticipants Added participants.
     */
    public void addCacheParticipants(Map<UUID, GridUuid> allParticipants, Map<UUID, GridUuid> addedParticipants) {
        verStore.addParticipants(allParticipants, addedParticipants);
    }

    /**
     * @param mode Mode to check.
     * @return {@code True} if shared mode.
     */
    private boolean isPerVersionMode(GridDeploymentMode mode) {
        return mode == GridDeploymentMode.CONTINUOUS || mode == GridDeploymentMode.SHARED;
    }

    /**
     * @param ldr Class loader to get ID for.
     * @return ID for given class loader or {@code null} if given loader is not
     *      grid deployment class loader.
     */
    @Nullable public GridUuid getClassLoaderId(ClassLoader ldr) {
        assert ldr != null;

        return ldr instanceof GridDeploymentClassLoader ? ((GridDeploymentInfo)ldr).classLoaderId() : null;
    }

    /**
     * @param ldr Loader to check.
     * @return {@code True} if P2P class loader.
     */
    public boolean isGlobalLoader(ClassLoader ldr) {
        return ldr instanceof GridDeploymentClassLoader;
    }

    /**
     *
     */
    private static class LocalDeployment extends GridDeployment {
        /**
         * @param depMode Mode.
         * @param clsLdr Loader.
         * @param clsLdrId Loader ID.
         * @param userVer User version.
         * @param sampleClsName Sample class name.
         */
        private LocalDeployment(GridDeploymentMode depMode, ClassLoader clsLdr, GridUuid clsLdrId, String userVer,
            String sampleClsName) {
            super(depMode, clsLdr, clsLdrId, userVer, sampleClsName, /*local*/true);
        }

        /** {@inheritDoc} */
        @Override public boolean undeployed() {
            return false;
        }

        /** {@inheritDoc} */
        @Override public void undeploy() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public boolean pendingUndeploy() {
            return false;
        }

        /** {@inheritDoc} */
        @Override public void onUndeployScheduled() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public boolean acquire() {
            return true;
        }

        /** {@inheritDoc} */
        @Override public void release() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public boolean obsolete() {
            return false;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Map<UUID, GridUuid> participants() {
            return null;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(LocalDeployment.class, this, super.toString());
        }
    }
}
