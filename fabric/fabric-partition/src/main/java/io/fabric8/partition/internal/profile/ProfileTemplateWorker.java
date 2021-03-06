/*
 * Copyright 2010 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.fabric8.partition.internal.profile;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import io.fabric8.api.Container;
import io.fabric8.api.FabricException;
import io.fabric8.api.FabricService;
import io.fabric8.api.Profile;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.Version;
import io.fabric8.api.jcip.GuardedBy;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.locks.LockService;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.internal.ProfileImpl;
import io.fabric8.partition.TaskContext;
import io.fabric8.partition.WorkItem;
import io.fabric8.partition.Worker;
import io.fabric8.utils.SystemProperties;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.mvel2.ParserContext;
import org.mvel2.templates.CompiledTemplate;
import org.mvel2.templates.TemplateCompiler;
import org.mvel2.templates.TemplateRuntime;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.wireadmin.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ThreadSafe
@Component(name = "io.fabric8.partition.listener.profile", description = "Fabric Profile Partition Listener", immediate = true)
@Service(Worker.class)
public final class ProfileTemplateWorker extends AbstractComponent implements Worker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileTemplateWorker.class);

    private static final String ID = "id";
    private static final String TYPE = "profile-template";
    private static final String NAME_VARIABLE_FORMAT = "__%s__";
    private static final String PROPERTIES_SUFFIX = ".properties";
    private static final String PROFILE_WORKER_LOCK = "/fabric/registry/locks/partionworker";

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = LockService.class)
    private final ValidatingReference<LockService> lockService = new ValidatingReference<LockService>();
    @Reference(referenceInterface = RuntimeProperties.class)
    private final ValidatingReference<RuntimeProperties> runtimeProperties = new ValidatingReference<RuntimeProperties>();

    @GuardedBy("this")
    private final Map<Key, CompiledTemplate> templates = new HashMap<Key, CompiledTemplate>();
    @GuardedBy("this")
    private final SetMultimap<TaskContext, WorkItem> assignedWorkItems = HashMultimap.<TaskContext, WorkItem>create();
    @GuardedBy("this")
    private final ParserContext parserContext = new ParserContext();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private InterProcessLock lock;
    private String name;

    @Activate
    void activate() {
        lock = lockService.get().getLock(PROFILE_WORKER_LOCK);
        RuntimeProperties props = runtimeProperties.get();
        name = props.getProperty(SystemProperties.KARAF_NAME);
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        destroyInternal();
        deactivateComponent();
    }

    private synchronized void destroyInternal() {
        for (TaskContext context : assignedWorkItems.keySet()) {
            release(context, assignedWorkItems.get(context));
        }
        templates.clear();
    }

    @Override
    public String getType() {
        assertValid();
        return TYPE;
    }

    @Override
    public synchronized void assign(TaskContext context, Set<WorkItem> workItems) {
        assertValid();
        executorService.submit(new AssignTask(context, workItems));
    }

    @Override
    public synchronized void release(TaskContext context, Set<WorkItem> workItems) {
        assertValid();
        executorService.submit(new ReleaseTask(context, workItems));
    }

    private void manageProfile(TaskContext context) {
        Container current = fabricService.get().getCurrentContainer();
        ProfileData profileData = createProfileData(context);
        String profileId = context.getDefinition() + "-" + name;
        Version version = current.getVersion();

        try {
            if (lock.acquire(60, TimeUnit.SECONDS)) {
                if (profileData.isEmpty()) {
                    if (version.hasProfile(profileId)) {
                        //Just delete the profile
                        version.getProfile(profileId).delete(true);
                    }
                    return;
                } else if (!version.hasProfile(profileId)) {
                    //Create the profile
                    fabricService.get().getDataStore().createProfile(version.getId(), profileId);
                }

                Profile managedProfile = version.getProfile(profileId);
                //managedProfile.setConfigurations(profileData.getConfigs());
                managedProfile.setFileConfigurations(profileData.getFiles());
                current.addProfiles(managedProfile);
            } else {
                throw new TimeoutException("Timed out waiting for lock");
            }
        } catch (Exception e) {
            LOGGER.error("Error managing work items.", e);
        } finally {
            releaseLock();
        }
    }

    /**
     * Creates a representation of the profile based on the assigned item for the specified {@linkTaskContext}.
     * @param context
     * @return
     */
    private ProfileData createProfileData(TaskContext context) {
        ProfileData profileData = new ProfileData();
        Set<WorkItem> workItems = assignedWorkItems.get(context);
        if (workItems.isEmpty()) {
            return profileData;
        }


        Container current = fabricService.get().getCurrentContainer();
        Version version = current.getVersion();
        Profile templateProfile = version.getProfile(context.getDefinition());
        Iterable<String> fileTemplates = Iterables.filter(templateProfile.getFileConfigurations().keySet(), new MvelPredicate());

        for (String fileTemplate : fileTemplates) {
            Key key = new Key(templateProfile.getId(), fileTemplate);
            synchronized (templates) {
                CompiledTemplate template = templates.get(key);
                if (template == null) {
                    template = TemplateCompiler.compileTemplate(new String(templateProfile.getFileConfigurations().get(fileTemplate)), parserContext);
                    templates.put(key, template);
                }
            }
        }

        for (WorkItem workItem : workItems) {
            Map<String, WorkItem> data = new HashMap<String, WorkItem>();
            data.put(WorkItem.ITEM, workItem);

            for (String fileTemplate : fileTemplates) {
                String file = renderTemplateName(fileTemplate, workItem);
                Key key = new Key(templateProfile.getId(), fileTemplate);
                try {
                    String renderedTemplate = TemplateRuntime.execute(templates.get(key), parserContext, data).toString();
                    updateProfileData(file, renderedTemplate, profileData);
                } catch (Exception ex) {
                    LOGGER.warn("Failed to render {}. Ignoring.", fileTemplate);
                }
            }
        }
        return profileData;
    }

    private static void updateProfileData(String file, String data, ProfileData profileData) {
        if (file.endsWith(PROPERTIES_SUFFIX)) {
            String pid = file.substring(0, file.length() - PROPERTIES_SUFFIX.length());
            Properties old = new Properties();
            if (profileData.getConfigs().containsKey(pid)) {
                old.putAll(profileData.getConfigs().get(pid));
            }
            Properties merged = mergeProperties(data, old);
            profileData.addPid(pid, toMap(merged));
            profileData.addFile(file, toString(merged).getBytes());
        } else {
            profileData.addFile(file, data.getBytes());
        }
    }

    private void releaseLock() {
        try {
            if (lock.isAcquiredInThisProcess()) {
                lock.release();
            }
        } catch (Exception e) {
            throw FabricException.launderThrowable(e);
        }
    }

    private String renderTemplateName(String name, WorkItem workItem) {
        for (Map.Entry<String, String> entry : workItem.getData().entrySet()) {
            name = name.replaceAll(String.format(NAME_VARIABLE_FORMAT, WorkItem.ITEM_DATA_PREFIX + entry.getKey()), entry.getValue());
        }
        return name.substring(0, name.lastIndexOf("."));
    }

    private static Properties mergeProperties(Properties left, Properties right) {
        Properties props = new Properties();
        for (String key : left.stringPropertyNames()) {
            props.put(key, left.getProperty(key));
        }
        for (String key : right.stringPropertyNames()) {
            props.put(key, right.getProperty(key));
        }
        return props;
    }

    private static Properties mergeProperties(String left, Properties right) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(left));
        } catch (IOException e) {
            throw FabricException.launderThrowable(e);
        }
        return mergeProperties(p, right);
    }

    private static Map<String, String> toMap(Properties properties) {
        Map<String, String> map = new HashMap<String, String>();
        for (String key : properties.stringPropertyNames()) {
            map.put(key, properties.getProperty(key));
        }
        return map;
    }

    private static String toString(Properties properties) {
        StringWriter writer = new StringWriter();
        try {
            properties.store(writer, "");
        } catch (IOException e) {
            throw FabricException.launderThrowable(e);
        }
        return writer.toString();
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindLockService(LockService lockService) {
        this.lockService.bind(lockService);
    }

    void unbindLockService(LockService lockService) {
        this.lockService.unbind(lockService);
    }

    void bindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.bind(service);
    }

    void unbindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.unbind(service);
    }

    private static class Key {
        private final String profile;
        private final String configName;

        private Key(String profile, String configName) {
            this.profile = profile;
            this.configName = configName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Key that = (Key) o;

            if (configName != null ? !configName.equals(that.configName) : that.configName != null) return false;
            if (profile != null ? !profile.equals(that.profile) : that.profile != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = profile != null ? profile.hashCode() : 0;
            result = 31 * result + (configName != null ? configName.hashCode() : 0);
            return result;
        }
    }

    private static class ProfileData {
        private final Map<String, byte[]> files = new HashMap<String, byte[]>();
        private final Map<String, Map<String, String>> configs = new HashMap<String, Map<String, String>>();

        public Map<String, byte[]> getFiles() {
            return files;
        }

        public Map<String, Map<String, String>> getConfigs() {
            return configs;
        }

        public void addFile(String file, byte[] content) {
            files.put(file, content);
        }

        public void addPid(String pid, Map<String, String> config) {
            configs.put(pid, config);
        }

        public boolean isEmpty() {
            return files.isEmpty() && configs.isEmpty();
        }
    }

    private class AssignTask implements Runnable {

        private final TaskContext context;
        private final Set<WorkItem> items;

        private AssignTask(TaskContext context, Set<WorkItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public void run() {
            if (items.isEmpty()) {
                return;
            }
            assignedWorkItems.putAll(context, items);
            manageProfile(context);
        }
    }

    private class ReleaseTask implements Runnable {

        private final TaskContext context;
        private final Set<WorkItem> items;

        private ReleaseTask(TaskContext context, Set<WorkItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public void run() {
            if (items.isEmpty()) {
                return;
            }
            for (WorkItem workItem : items) {
                assignedWorkItems.remove(context, workItem);
            }
            manageProfile(context);
        }
    }
}
