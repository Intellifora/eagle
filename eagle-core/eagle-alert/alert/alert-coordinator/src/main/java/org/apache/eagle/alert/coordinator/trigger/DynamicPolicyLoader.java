package org.apache.eagle.alert.coordinator.trigger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.apache.eagle.alert.engine.coordinator.PolicyDefinition;
import org.apache.eagle.alert.service.IMetadataServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Poll policy change and notify listeners
 */
public class DynamicPolicyLoader implements Runnable{
    private static Logger LOG = LoggerFactory.getLogger(DynamicPolicyLoader.class);

    private IMetadataServiceClient client;
    // initial cachedPolicies should be empty
    private Map<String, PolicyDefinition> cachedPolicies = new HashMap<>();
    private List<PolicyChangeListener> listeners = new ArrayList<>();

    public DynamicPolicyLoader(IMetadataServiceClient client){
        this.client = client;
    }

    public synchronized void addPolicyChangeListener(PolicyChangeListener listener){
        listeners.add(listener);
    }

    /**
     * When it is run at the first time, due to cachedPolicies being empty, all existing policies are expected
     * to be addedPolicies
     */
    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        // we should catch every exception to avoid zombile thread
        try {
            List<PolicyDefinition> current = client.listPolicies();
            Map<String, PolicyDefinition> currPolicies = new HashMap<>();
            current.forEach(pe -> currPolicies.put(pe.getName(), pe));

            Collection<String> addedPolicies = CollectionUtils.subtract(currPolicies.keySet(), cachedPolicies.keySet());
            Collection<String> removedPolicies = CollectionUtils.subtract(cachedPolicies.keySet(), currPolicies.keySet());
            Collection<String> potentiallyModifiedPolicies = CollectionUtils.intersection(currPolicies.keySet(), cachedPolicies.keySet());

            List<String> reallyModifiedPolicies = new ArrayList<>();
            for (String updatedPolicy : potentiallyModifiedPolicies) {
                if (!currPolicies.get(updatedPolicy).equals(cachedPolicies.get(updatedPolicy))) {
                    reallyModifiedPolicies.add(updatedPolicy);
                }
            }

            boolean policyChanged = false;
            if (addedPolicies.size() != 0 ||
                    removedPolicies.size() != 0 ||
                    reallyModifiedPolicies.size() != 0) {
                policyChanged = true;
            }

            if (!policyChanged) {
                LOG.info("policy is not changed since last run");
                return;
            }
            synchronized (this) {
                for (PolicyChangeListener listener : listeners) {
                    listener.onPolicyChange(current, addedPolicies, removedPolicies, reallyModifiedPolicies);
                }
            }

            // reset cached policies
            cachedPolicies = currPolicies;
        } catch (Throwable t) {
            LOG.error("error loading policy, but continue to run", t);
        }
    }
}