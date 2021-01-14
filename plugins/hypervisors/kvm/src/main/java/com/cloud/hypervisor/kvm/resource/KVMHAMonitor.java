// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource;

import com.cloud.agent.properties.AgentProperty;
import com.cloud.agent.properties.AgentPropertyFile;
import com.cloud.utils.script.Script;
import org.apache.log4j.Logger;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StoragePoolInfo.StoragePoolState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class KVMHAMonitor extends KVMHABase implements Runnable {

    private static final Logger s_logger = Logger.getLogger(KVMHAMonitor.class);
    private final Map<String, NfsStoragePool> _storagePool = new ConcurrentHashMap<>();
    private final boolean rebootHostAndAlertManagementOnHearbeatTimeout;

    /* private ip address */
    private final String _hostIP;

    public KVMHAMonitor(NfsStoragePool pool, String host, String scriptPath) {
        if (pool != null) {
            _storagePool.put(pool._poolUUID, pool);
        }
        _hostIP = host;
        configureHeartBeatPath(scriptPath);

        _heartBeatUpdateTimeout = AgentPropertyFile.getProperty(AgentProperty.HEARTBEAT_UPDATE_TIMEOUT);
        rebootHostAndAlertManagementOnHearbeatTimeout = AgentPropertyFile.getProperty(AgentProperty.REBOOT_HOST_AND_ALERT_MANAGEMENT_ON_HEARBEAT_TIMEOUT);
    }

    private static synchronized void configureHeartBeatPath(String scriptPath) {
        KVMHABase.s_heartBeatPath = scriptPath;
    }

    public void addStoragePool(NfsStoragePool pool) {
        synchronized (_storagePool) {
            _storagePool.put(pool._poolUUID, pool);
        }
    }

    public void removeStoragePool(String uuid) {
        synchronized (_storagePool) {
            NfsStoragePool pool = _storagePool.get(uuid);
            if (pool != null) {
                Script.runSimpleBashScript("umount " + pool._mountDestPath);
                _storagePool.remove(uuid);
            }
        }
    }

    public List<NfsStoragePool> getStoragePools() {
        synchronized (_storagePool) {
            return new ArrayList<>(_storagePool.values());
        }
    }

    public NfsStoragePool getStoragePool(String uuid) {
        synchronized (_storagePool) {
            return _storagePool.get(uuid);
        }
    }

    protected void runHearbeat() {
        synchronized (_storagePool) {
            Set<String> removedPools = new HashSet<>();
            for (String uuid : _storagePool.keySet()) {
                NfsStoragePool primaryStoragePool = _storagePool.get(uuid);
                StoragePool storage;
                try {
                    Connect conn = LibvirtConnection.getConnection();
                    storage = conn.storagePoolLookupByUUIDString(uuid);
                    if (storage == null || storage.getInfo().state != StoragePoolState.VIR_STORAGE_POOL_RUNNING) {
                        if (storage == null) {
                            s_logger.debug(String.format("Libvirt storage pool [%s] not found, removing from HA list.", uuid));
                        } else {
                            s_logger.debug(String.format("Libvirt storage pool [%s] found, but not running, removing from HA list.", uuid));
                        }

                        removedPools.add(uuid);
                        continue;
                    }

                    s_logger.debug(String.format("Found NFS storage pool [%s] in libvirt, continuing.", uuid));

                } catch (LibvirtException e) {
                    s_logger.debug(String.format("Failed to lookup libvirt storage pool [%s].", uuid), e);

                    if (e.toString().contains("pool not found")) {
                        s_logger.debug(String.format("Removing pool [%s] from HA monitor since it was deleted.", uuid));
                        removedPools.add(uuid);
                        continue;
                    }

                }

                String result = null;
                for (int i = 1; i <= _heartBeatUpdateMaxTries; i++) {
                    Script cmd = new Script(s_heartBeatPath, _heartBeatUpdateTimeout, s_logger);
                    cmd.add("-i", primaryStoragePool._poolIp);
                    cmd.add("-p", primaryStoragePool._poolMountSourcePath);
                    cmd.add("-m", primaryStoragePool._mountDestPath);
                    cmd.add("-h", _hostIP);
                    result = cmd.execute();

                    s_logger.debug(String.format("The command (%s), to the pool [%s], has the result [%s].", cmd.toString(), uuid, result));

                    if (result != null) {
                        s_logger.warn(String.format("Write heartbeat for pool [%s] failed: %s; try: %s of %s.", uuid, result, i, _heartBeatUpdateMaxTries));
                        try {
                            Thread.sleep(_heartBeatUpdateRetrySleep);
                        } catch (InterruptedException e) {
                            s_logger.debug("[IGNORED] Interrupted between heartbeat retries.", e);
                        }
                    } else {
                        break;
                    }

                }

                if (result != null && rebootHostAndAlertManagementOnHearbeatTimeout) {
                    s_logger.warn(String.format("Write heartbeat for pool [%s] failed: %s; stopping cloudstack-agent.", uuid, result));
                    Script cmd = new Script(s_heartBeatPath, _heartBeatUpdateTimeout, s_logger);
                    cmd.add("-i", primaryStoragePool._poolIp);
                    cmd.add("-p", primaryStoragePool._poolMountSourcePath);
                    cmd.add("-m", primaryStoragePool._mountDestPath);
                    cmd.add("-c");
                    result = cmd.execute();
                }
            }

            if (!removedPools.isEmpty()) {
                for (String uuid : removedPools) {
                    removeStoragePool(uuid);
                }
            }
        }

    }

    @Override
    public void run() {
        while (true) {

            runHearbeat();

            try {
                Thread.sleep(_heartBeatUpdateFreq);
            } catch (InterruptedException e) {
                s_logger.debug("[IGNORED] Interrupted between heartbeats.", e);
            }
        }
    }

}
