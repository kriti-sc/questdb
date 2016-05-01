/*******************************************************************************
 * ___                  _   ____  ____
 * / _ \ _   _  ___  ___| |_|  _ \| __ )
 * | | | | | | |/ _ \/ __| __| | | |  _ \
 * | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 * \__\_\\__,_|\___||___/\__|____/|____/
 * <p>
 * Copyright (C) 2014-2016 Appsicle
 * <p>
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * <p>
 * As a special exception, the copyright holders give permission to link the
 * code of portions of this program with the OpenSSL library under certain
 * conditions as described in each individual source file and distribute
 * linked combinations including the program with the OpenSSL library. You
 * must comply with the GNU Affero General Public License in all respects for
 * all of the code used other than as permitted herein. If you modify file(s)
 * with this exception, you may extend this exception to your version of the
 * file(s), but you are not obligated to do so. If you do not wish to do so,
 * delete this exception statement from your version. If you delete this
 * exception statement from all source files in the program, then also delete
 * it in the license file.
 ******************************************************************************/

package com.questdb.net.ha;

import com.questdb.JournalWriter;
import com.questdb.ex.JournalNetworkException;
import com.questdb.ex.JournalRuntimeException;
import com.questdb.factory.JournalFactory;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.net.ha.config.ClientConfig;
import com.questdb.net.ha.config.ServerConfig;
import com.questdb.net.ha.config.ServerNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClusterController {

    private final Log LOG = LogFactory.getLog(ClusterController.class);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ClusterStatusListener listener;
    private final JournalFactory factory;
    private final List<JournalWriter> writers;
    private final ServerConfig serverConfig;
    private final ClientConfig clientConfig;
    private final ServerNode thisNode;
    private final ClusterStatusListener statusListener = new StatusListener();
    private JournalClient client;
    private JournalServer server;

    public ClusterController(
            ServerConfig serverConfig
            , ClientConfig clientConfig
            , JournalFactory factory
            , final int instance
            , List<JournalWriter> writers
            , ClusterStatusListener listener
    ) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.factory = factory;
        this.writers = writers;
        this.listener = listener;
        this.thisNode = serverConfig.getNodeByUID(instance);
        if (thisNode == null) {
            throw new JournalRuntimeException("Instance " + instance + " is not found in server config");
        }
    }

    public void halt() {
        if (running.compareAndSet(true, false)) {
            haltClient();
            if (server != null) {
                server.halt();
            }

            if (listener != null) {
                listener.onShutdown();
            }
        }
    }

    public boolean isLeader() {
        return server != null && server.isLeader();
    }

    @SuppressFBWarnings({"LII_LIST_INDEXED_ITERATING"})
    public void start() throws JournalNetworkException {
        if (running.compareAndSet(false, true)) {
            server = new JournalServer(serverConfig, factory, null, thisNode.getId());
            for (int i = 0, k = writers.size(); i < k; i++) {
                server.publish(writers.get(i));
            }
            server.start();
            server.joinCluster(statusListener);
        }
    }

    private void haltClient() {
        if (client != null) {
            client.halt();
            client = null;
        }
    }

    private class StatusListener implements ClusterStatusListener {

        private int lastActive = -1;

        @Override
        public void goActive() {
            if (listener != null) {
                listener.goActive();
            }
        }

        @SuppressFBWarnings({"LII_LIST_INDEXED_ITERATING"})
        @Override
        @SuppressWarnings("unchecked")
        public void goPassive(ServerNode activeNode) {
            if (activeNode.getId() != lastActive) {

                lastActive = activeNode.getId();

                // halt existing client
                haltClient();

                // configure client to connect to new active node
                clientConfig.clearNodes();
                clientConfig.addNode(activeNode);

                client = new JournalClient(clientConfig, factory);
                LOG.info().$(thisNode.toString()).$(" Subscribing journals").$();
                for (int i = 0, sz = writers.size(); i < sz; i++) {
                    JournalWriter w = writers.get(i);
                    client.subscribe(w.getKey(), w, null);
                }

                try {

                    client.start();
                    client.setDisconnectCallback(new JournalClient.DisconnectCallback() {
                        @Override
                        public void onDisconnect(JournalClient.DisconnectReason reason) {
                            switch (reason) {
                                case INCOMPATIBLE_JOURNAL:
                                case CLIENT_HALT:
                                    halt();
                                    break;
                                default:
                                    if (running.get()) {
                                        server.joinCluster(statusListener);
                                    }
                                    break;
                            }
                        }
                    });

                    if (listener != null) {
                        listener.goPassive(activeNode);
                    }

                } catch (JournalNetworkException e) {
                    LOG.error().$("Failed to start client").$(e).$();
                    haltClient();
                    server.joinCluster(statusListener);
                }
            }
        }

        @Override
        public void onShutdown() {

        }

    }
}
