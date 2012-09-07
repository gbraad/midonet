/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.state.zkManagers;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.google.common.util.concurrent.ValueFuture;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.DirectoryCallbacks;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;

public class PortSetZkManager extends ZkManager {

    private static final Logger log = LoggerFactory
        .getLogger(PortSetZkManager.class);

    public PortSetZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public void getPortSetAsync(UUID portSetId,
                                final DirectoryCallback<Set<UUID>> portSetContentsCallback,
                                Directory.TypedWatcher watcher) {
        String portSetPath = pathManager.getPortSetPath(portSetId);

        zk.asyncGetChildren(
            portSetPath,
            DirectoryCallbacks.transform(
                portSetContentsCallback,
                new Functor<Set<String>, Set<UUID>>() {
                    @Override
                    public Set<UUID> apply(Set<String> arg0) {
                        return CollectionFunctors.map(
                            arg0, strToUUIDMapper, new HashSet<UUID>());
                    }
                }
            ), watcher);
    }

    public void addMemberAsync(UUID portSetId, UUID memberId, DirectoryCallback.Add cb) {

        String portSetPath =
            pathManager.getPortSetEntryPath(portSetId, memberId);

        zk.asyncAdd(portSetPath, null, CreateMode.EPHEMERAL, cb);
    }

    public void delMemberAsync(UUID portSetId, UUID entryId, DirectoryCallback.Void callback) {
        String portSetPath = pathManager.getPortSetEntryPath(portSetId,
                                                             entryId);
        zk.asyncDelete(portSetPath, callback);
    }

    public Set<UUID> getPortSet(UUID portSetId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        try {
            ValueFuture<Set<UUID>> valueFuture = ValueFuture.create();
            getPortSetAsync(portSetId, makeCallback(valueFuture), watcher);
            return valueFuture.get();
        } catch (Exception e) {
            throw new StateAccessException(e);
        }
    }

    private <T> DirectoryCallback<T> makeCallback(final ValueFuture<T> valueFuture) {
        return new DirectoryCallback<T>() {
            @Override
            public void onSuccess(Result<T> data) {
                valueFuture.set(data.getData());
            }

            @Override
            public void onTimeout() {
                valueFuture.cancel(true);
            }

            @Override
            public void onError(KeeperException e) {
                valueFuture.setException(e);
            }
        };
    }
}
