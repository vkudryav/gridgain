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

package org.gridgain.examples.misc.client.api;

import org.gridgain.client.*;
import org.gridgain.client.balancer.*;
import org.gridgain.grid.*;

import java.net.*;
import java.util.*;

/**
 * This example demonstrates use of Java remote client API. To execute
 * this example you should start an instance of {@link ClientExampleNodeStartup}
 * class which will start up a GridGain node with proper configuration.
 * <p>
 * After node has been started this example creates a client
 * and performs several executions of the a test task using different API methods.
 * <p>
 * Note that different nodes cannot share the same port for rest services. If you want
 * to start more than one node on the same physical machine you must provide different
 * configurations for each node. Otherwise, this example would not work.
 * <p>
 * Before running this example you must start at least one remote node using
 * {@link ClientExampleNodeStartup}.
 */
public class ClientApiExample {
    /** Grid node address to connect to. */
    private static final String SERVER_ADDRESS = "127.0.0.1";

    /**
     * Executes example.
     *
     * @param args Command line arguments, none required.
     * @throws GridClientException If example execution failed.
     */
    public static void main(String[] args) throws GridClientException {
        System.out.println();
        System.out.println(">>> Client API example started.");

        try (GridClient client = createClient()) {
            // Show grid topology.
            System.out.println(">>> Client created, current grid topology: " + client.compute().nodes());

            // Random node ID.
            final UUID randNodeId = client.compute().nodes().iterator().next().nodeId();

            // Note that in this example we get a fixed projection for task call because we cannot guarantee that
            // other nodes contain ClientExampleTask in classpath.
            GridClientCompute prj = client.compute().projection(new GridClientPredicate<GridClientNode>() {
                @Override public boolean apply(GridClientNode node) {
                    return node.nodeId().equals(randNodeId);
                }
            });

            // Execute test task that count number of nodes it is running on.
            Integer entryCnt = prj.execute(ClientExampleTask.class.getName(), "Client example.");

            System.out.println(">>> Predicate projection : " + entryCnt + " nodes participated in task.");

            // Same as above, using different projection API.
            GridClientNode clntNode = prj.node(randNodeId);

            prj = prj.projection(clntNode);

            entryCnt = prj.execute(ClientExampleTask.class.getName(), "Client example - single node.");

            System.out.println(">>> GridClientNode projection : " + entryCnt + " nodes participated in task.");

            // Use of collections is also possible.
            prj = prj.projection(Collections.singleton(clntNode));

            entryCnt = prj.execute(ClientExampleTask.class.getName(), "Client example - collection of nodes.");

            System.out.println(">>> Collection projection : " + entryCnt + " nodes participated in task.");

            // Balancing - may be random or round-robin. Users can create
            // custom load balancers as well.
            GridClientLoadBalancer balancer = new GridClientRandomBalancer();

            // Balancer may be added to predicate or collection examples.
            prj = client.compute().projection(new GridClientPredicate<GridClientNode>() {
                @Override public boolean apply(GridClientNode node) {
                    return node.nodeId().equals(randNodeId);
                }
            }, balancer);

            entryCnt = prj.execute(ClientExampleTask.class.getName(), "Client example - explicit random balancer.");

            System.out.println(">>> Predicate projection with balancer : " + entryCnt + " nodes participated in task.");

            // Now let's try round-robin load balancer.
            balancer = new GridClientRoundRobinBalancer();

            prj = prj.projection(Collections.singleton(clntNode), balancer);

            entryCnt = prj.execute(ClientExampleTask.class.getName(),
                "Client example - explicit roundrobin balancer.");

            System.out.println(">>> GridClientNode projection : " + entryCnt + " nodes participated in task.");

            // Execution may be asynchronous.
            GridClientFuture<Integer> fut = prj.executeAsync(ClientExampleTask.class.getName(),
                "Client example - asynchronous execution.");

            System.out.println(">>> Execute async : " + fut.get() + " nodes participated in task.");

            // GridClientCompute can be queried for nodes participating in it.
            Collection c = prj.nodes(Collections.singleton(randNodeId));

            System.out.println(">>> Nodes with UUID " + randNodeId + " : " + c);

            // Nodes may also be filtered with predicate. Here
            // we create projection which only contains local node.
            c = prj.nodes(new GridClientPredicate<GridClientNode>() {
                @Override public boolean apply(GridClientNode node) {
                    return node.nodeId().equals(randNodeId);
                }
            });

            System.out.println(">>> Nodes filtered with predicate : " + c);

            // Information about nodes may be refreshed explicitly.
            clntNode = prj.refreshNode(randNodeId, true, true);

            System.out.println(">>> Refreshed node : " + clntNode);

            // As usual, there's also an asynchronous version.
            GridClientFuture<GridClientNode> futClntNode = prj.refreshNodeAsync(randNodeId, false, false);

            System.out.println(">>> Refreshed node asynchronously : " + futClntNode.get());

            // Nodes may also be refreshed by IP address.
            String clntAddr = "127.0.0.1";

            for (InetSocketAddress addr : clntNode.availableAddresses(GridClientProtocol.TCP))
                if (addr != null)
                    clntAddr = addr.getAddress().getHostAddress();

            // Force node metrics refresh (by default it happens periodically in the background).
            clntNode = prj.refreshNode(clntAddr, true, true);

            if (clntNode != null)
                System.out.println(">>> Refreshed node by IP : " + clntNode.toString());
            else
                System.err.println(">>> Failed to refresh node metrics! Please check the node is reachable by " +
                    "making sure node has the right restTcpHost address set.");

            // Asynchronous version.
            futClntNode = prj.refreshNodeAsync(clntAddr, false, false);

            System.out.println(">>> Refreshed node by IP asynchronously : " + futClntNode.get());

            // Topology as a whole may be refreshed, too.
            Collection<GridClientNode> top = prj.refreshTopology(true, true);

            System.out.println(">>> Refreshed topology : " + top);

            // Asynchronous version.
            GridClientFuture<List<GridClientNode>> topFut = prj.refreshTopologyAsync(false, false);

            System.out.println(">>> Refreshed topology asynchronously : " + topFut.get());
        }
    }

    /**
     * This method will create a client with default configuration. Note that this method expects that
     * first node will bind rest binary protocol on default port. It also expects that partitioned cache is
     * configured in grid.
     *
     * @return Client instance.
     * @throws GridClientException If client could not be created.
     */
    private static GridClient createClient() throws GridClientException {
        GridClientConfiguration cfg = new GridClientConfiguration();

        // Point client to a local node. Note that this server is only used
        // for initial connection. After having established initial connection
        // client will make decisions which grid node to use based on collocation
        // with key affinity or load balancing.
        cfg.setServers(Collections.singletonList(SERVER_ADDRESS + ':' + GridConfiguration.DFLT_TCP_PORT));

        return GridClientFactory.start(cfg);
    }
}
