package org.dasein.cloud.google.network;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.Backend;
import com.google.api.services.compute.model.BackendService;
import com.google.api.services.compute.model.ForwardingRule;
import com.google.api.services.compute.model.ForwardingRuleList;
import com.google.api.services.compute.model.HostRule;
import com.google.api.services.compute.model.HttpHealthCheck;
import com.google.api.services.compute.model.Operation;
import com.google.api.services.compute.model.PathMatcher;
import com.google.api.services.compute.model.PathRule;
import com.google.api.services.compute.model.TargetHttpProxy;
import com.google.api.services.compute.model.TargetHttpProxyList;
import com.google.api.services.compute.model.UrlMap;
import com.google.api.services.compute.model.UrlMapList;
import org.dasein.cloud.CloudErrorType;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.GeneralCloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.Tag;
import org.dasein.cloud.ci.AbstractConvergedHttpLoadBalancer;
import org.dasein.cloud.ci.ConvergedHttpLoadBalancer;
import org.dasein.cloud.ci.HttpLoadBalancerCapabilities;
import org.dasein.cloud.google.Google;
import org.dasein.cloud.google.GoogleException;
import org.dasein.cloud.google.GoogleMethod;
import org.dasein.cloud.google.GoogleOperationType;
import org.dasein.cloud.google.capabilities.GCEHttpLoadBalancerCapabilities;
import org.dasein.cloud.identity.ServiceAction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
public class CIHttpLoadBalancerSupport extends AbstractConvergedHttpLoadBalancer<Google> {

    private Google provider;
    private ProviderContext ctx;

    public CIHttpLoadBalancerSupport(Google provider) {
        super(provider);
        this.provider = provider;

        ctx = provider.getContext(); 
    }

    @Override
    public HttpLoadBalancerCapabilities getCapabilities() {
        return new GCEHttpLoadBalancerCapabilities(provider);
    }

    @Override
    public String getProviderTermForConvergedHttpLoadBalancer(Locale locale) {
        return "HTTP load balancer";
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true; // TODO punt!
    }

    @Override
    public Iterable<String> listConvergedHttpLoadBalancers() throws CloudException, InternalException {
        List<String> httpLoadBalancers = new ArrayList<String>();

        Compute gce = provider.getGoogleCompute();

        try {
            UrlMapList urlMaps = gce.urlMaps().list(ctx.getAccountNumber()).execute();
            if ((null != urlMaps) && (null != urlMaps.getItems())) {
                for (UrlMap urlMap: urlMaps.getItems()) {
                    httpLoadBalancers.add(urlMap.getName());
                }
            }
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred listing convergedHttpLoadBalancers " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        }

        return httpLoadBalancers;
    }

    @Override
    public @Nullable ConvergedHttpLoadBalancer getConvergedHttpLoadBalancer(@Nonnull String convergedHttpLoadBalancerName) throws CloudException, InternalException {
        return toConvergedHttpLoadBalancer(convergedHttpLoadBalancerName);
    }

    private String flatten(List<String> items) {
        String flattened = "";
        for (String item : items) {
            flattened += item + ", ";
        }
        return flattened.replaceFirst(", $", "");
    }

    public ConvergedHttpLoadBalancer toConvergedHttpLoadBalancer(@Nonnull String urlMap) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();
        ConvergedHttpLoadBalancer convergedHttpLoadBalancer;
        urlMap = urlMap.replaceAll(".*/", "");
        try {
            UrlMap um = gce.urlMaps().get(ctx.getAccountNumber(), urlMap).execute(); 
            convergedHttpLoadBalancer = ConvergedHttpLoadBalancer.getInstance(um.getName(), um.getDescription(), um.getSelfLink(), um.getCreationTimestamp(), um.getDefaultService().replaceAll(".*/", ""));

            List<HostRule> hostRules = um.getHostRules();
            Map<String, String> descriptionMap = new HashMap<String, String>();
            Map<String, String> hostMatchPatternMap = new HashMap<String, String>();
            for (HostRule hostRule: hostRules) {
                descriptionMap.put(hostRule.getPathMatcher(), hostRule.getDescription());
                hostMatchPatternMap.put(hostRule.getPathMatcher(), flatten(hostRule.getHosts()));
            }

            List<PathMatcher> pathMatchers = um.getPathMatchers();
            for (PathMatcher pathMatcher: pathMatchers) {
                Map<String, String> pathMap = new HashMap<String, String>();
                String defaultService = pathMatcher.getDefaultService().replaceAll(".*/", "");
                pathMap.put("/*", defaultService);
                if (null != pathMatcher.getPathRules()) {
                    for (PathRule pathRule: pathMatcher.getPathRules()) { 
                        pathMap.put(flatten(pathRule.getPaths()), pathRule.getService().replaceAll(".*/", ""));
                    }
                }
                convergedHttpLoadBalancer = convergedHttpLoadBalancer.withUrlSet(pathMatcher.getName(), descriptionMap.get(pathMatcher.getName()), hostMatchPatternMap.get(pathMatcher.getName()), pathMap);
            }

            // um.getTests() List object unknown

            TargetHttpProxyList targetHttpProxyList = gce.targetHttpProxies().list(ctx.getAccountNumber()).execute();
            for (TargetHttpProxy targetProxy: targetHttpProxyList.getItems()) {
                if (targetProxy.getUrlMap().endsWith(urlMap)) {
                    convergedHttpLoadBalancer = convergedHttpLoadBalancer.withTargetHttpProxy(targetProxy.getName(), targetProxy.getDescription(), targetProxy.getCreationTimestamp(), targetProxy.getSelfLink());
                    ForwardingRuleList forwardingRuleList = gce.globalForwardingRules().list(ctx.getAccountNumber()).execute();
                    for (ForwardingRule forwardingRule: forwardingRuleList.getItems()) {
                        if (forwardingRule.getTarget().endsWith(targetProxy.getName())) {
                            convergedHttpLoadBalancer = convergedHttpLoadBalancer.withForwardingRule(forwardingRule.getName(), forwardingRule.getDescription(), forwardingRule.getCreationTimestamp(), forwardingRule.getIPAddress(), forwardingRule.getIPProtocol(), forwardingRule.getPortRange(), forwardingRule.getSelfLink(), forwardingRule.getTarget().replaceAll(".*/", ""));
                        }
                    }
                }
            }

            List<String> backendServices = new ArrayList<String>();
            backendServices.add(um.getDefaultService().replaceAll(".*/", ""));

            List<String> allHealthChecks = new ArrayList<String>();
            for (String backendService : new HashSet<String>(backendServices)) { // use HashSet to make it unique list
                BackendService bes = gce.backendServices().get(ctx.getAccountNumber(), backendService).execute();

                List<String> healthChecks = bes.getHealthChecks();
                List<String> instanceGroups = new ArrayList<String>();
                for (Backend backend : bes.getBackends()) {
                    instanceGroups.add(backend.getGroup().replaceAll(".*/", ""));
                    convergedHttpLoadBalancer = convergedHttpLoadBalancer.withBackendServiceBackend(bes.getName(), backend.getDescription(), backend.getBalancingMode(), backend.getCapacityScaler(), backend.getGroup(), backend.getMaxRate(), backend.getMaxRatePerInstance(), backend.getMaxUtilization());
                }
                convergedHttpLoadBalancer = convergedHttpLoadBalancer.withBackendService(bes.getName(), bes.getDescription(), bes.getCreationTimestamp(), bes.getPort(), bes.getPortName(), bes.getProtocol(), healthChecks.toArray(new String[healthChecks.size()]), instanceGroups.toArray(new String[instanceGroups.size()]), bes.getSelfLink(), bes.getTimeoutSec());

                for (String healthCheck : bes.getHealthChecks()) {
                    allHealthChecks.add(healthCheck.replaceAll(".*/", ""));
                }
            }

            for (String healthCheck : new HashSet<String>(allHealthChecks)) { // use HashSet to make it unique list
                HttpHealthCheck hc = gce.httpHealthChecks().get(ctx.getAccountNumber(), healthCheck).execute();
                convergedHttpLoadBalancer = convergedHttpLoadBalancer.withHealthCheck(hc.getName(), hc.getDescription(), hc.getCreationTimestamp(), hc.getHost(), hc.getPort(), hc.getRequestPath(), hc.getCheckIntervalSec(), hc.getTimeoutSec(), hc.getHealthyThreshold(), hc.getUnhealthyThreshold(), hc.getSelfLink());
            }
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else
                throw new GeneralCloudException("An error occurred listing convergedHttpLoadBalancers " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        } catch (Exception ex) {
            throw new GeneralCloudException("Error removing Converged Http Load Balancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
        return convergedHttpLoadBalancer;
    }

    /*
     * takes either a globalForwardingRule name or url
     */
    public void removeGlobalForwardingRule(@Nonnull String globalForwardingRule) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();

        GoogleMethod method = new GoogleMethod(provider);
        try {
            Operation job = gce.globalForwardingRules().delete(ctx.getAccountNumber(), globalForwardingRule.replaceAll(".*/", "")).execute();
            method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred removing global forwarding rule " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error removing global forwarding rule " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
    }

    /*
     * takes either a targetHttpProxy name or url
     */
    public void removeTargetHttpProxy(@Nonnull String targetHttpProxy) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();

        GoogleMethod method = new GoogleMethod(provider);
        try {
            Operation job = gce.targetHttpProxies().delete(ctx.getAccountNumber(), targetHttpProxy.replaceAll(".*/", "")).execute();
            method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred removing target http proxy " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error removing target http proxy " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
    }

    /*
     * takes either a urlMap name or url
     */
    public void removeUrlMap(@Nonnull String urlMap) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();

        GoogleMethod method = new GoogleMethod(provider);
        try {
            Operation job = gce.urlMaps().delete(ctx.getAccountNumber(), urlMap.replaceAll(".*/", "")).execute();
            method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred removing url map " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error removing url map " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
    }

    /*
     * takes either a backendService name or url
     */
    public void removeBackendService(@Nonnull String backendService) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();

        GoogleMethod method = new GoogleMethod(provider);
        try {
            Operation job = gce.backendServices().delete(ctx.getAccountNumber(), backendService.replaceAll(".*/", "")).execute();
            method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred removing backend service " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            if (!ex.getMessage().contains("is already being used by")) {
                throw new GeneralCloudException("Error removing backend service " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            } // its ok not to remove resources in use elsewhere.
        }
    }

    /*
     * takes either a httpHealthCheck name or url
     */
    public void removeHttpHealthCheck(@Nonnull String httpHealthCheck) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();

        GoogleMethod method = new GoogleMethod(provider);
        try {
            Operation job = gce.httpHealthChecks().delete(ctx.getAccountNumber(), httpHealthCheck.replaceAll(".*/", "")).execute();
            method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred removing http health check " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            if (!ex.getMessage().contains("is already being used by")) {
                throw new GeneralCloudException("Error removing http health check " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            } // its ok not to remove resources in use elsewhere.
        }
    }

    @Override
    public void removeConvergedHttpLoadBalancers(@Nonnull String urlMap) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();

        urlMap = urlMap.replaceAll(".*/", "");
        try {
            ForwardingRuleList forwardingRuleList = gce.globalForwardingRules().list(ctx.getAccountNumber()).execute();
            TargetHttpProxyList targetHttpProxyList = gce.targetHttpProxies().list(ctx.getAccountNumber()).execute();

            for (TargetHttpProxy targetProxy: targetHttpProxyList.getItems()) {
                if (targetProxy.getUrlMap().endsWith(urlMap)) {
                    for (ForwardingRule forwardingRule: forwardingRuleList.getItems()) {
                        if (forwardingRule.getTarget().endsWith(targetProxy.getName())) {
                            removeGlobalForwardingRule(forwardingRule.getName());
                        }
                    }
                    removeTargetHttpProxy(targetProxy.getName());
                }
            }

            UrlMap um = gce.urlMaps().get(ctx.getAccountNumber(), urlMap).execute(); 

            List<String> backendServices = new ArrayList<String>();
            backendServices.add(um.getDefaultService().replaceAll(".*/", ""));
            List<PathMatcher> pathMatchers = um.getPathMatchers();
            for (PathMatcher pathMatcher: pathMatchers) {
                backendServices.add(pathMatcher.getDefaultService().replaceAll(".*/", ""));
                if (null != pathMatcher.getPathRules()) {
                    for (PathRule pathRule: pathMatcher.getPathRules()) { 
                        backendServices.add(pathRule.getService().replaceAll(".*/", ""));
                    }
                }
            }

            removeUrlMap(um.getName());

            List<String> healthChecks = new ArrayList<String>();
            for (String backendService : new HashSet<String>(backendServices)) { // use HashSet to make it unique list
                BackendService bes = gce.backendServices().get(ctx.getAccountNumber(), backendService).execute();
                for (String healthCheck : bes.getHealthChecks()) {
                    healthChecks.add(healthCheck);
                }
                removeBackendService(backendService);
            }

            for (String healthCheck : new HashSet<String>(healthChecks)) { // use HashSet to make it unique list
                removeHttpHealthCheck(healthCheck);
            }
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred removing convergedHttpLoadBalancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error removing Converged Http Load Balancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
    }

    public void createBackendService(ConvergedHttpLoadBalancer withConvergedHttpLoadBalancerOptions) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();
        GoogleMethod method = new GoogleMethod(provider);

        List<ConvergedHttpLoadBalancer.BackendService> backendServices = withConvergedHttpLoadBalancerOptions.getBackendServices();
        for (ConvergedHttpLoadBalancer.BackendService backendService : backendServices) {
            if (null == backendService.getSelfLink()) {
                BackendService beContent = new BackendService();
                beContent.setName(getCapabilities().getConvergedHttpLoadBalancerNamingConstraints().convertToValidName(backendService.getName(), Locale.US));
                beContent.setDescription(backendService.getDescription());
                beContent.setPort(backendService.getPort());
                beContent.setPortName(backendService.getPortName());
                beContent.setTimeoutSec(backendService.getTimeoutSec());

                List<String> healthCheckSelfUrls = new ArrayList<String>();
                for (String healthCheckName : backendService.getHealthChecks()) {
                    healthCheckSelfUrls.add(withConvergedHttpLoadBalancerOptions.getHealthCheckSelfUrl(healthCheckName));
                }
                beContent.setHealthChecks(healthCheckSelfUrls);

                List<Backend> backends = new ArrayList<Backend>();

                for (String backendServiceInstranceGroupSelfUrl : backendService.getBackendServiceBackends()) {
                    Backend backend = new Backend();
                    backend.setGroup(backendServiceInstranceGroupSelfUrl);
                    backends.add(backend);
                }

                beContent.setBackends(backends);

                try {
                    Operation foo = gce.backendServices().insert(ctx.getAccountNumber(), beContent ).execute();
                    method.getOperationComplete(provider.getContext(), foo, GoogleOperationType.GLOBAL_OPERATION, null, null);
                } catch ( IOException ex ) {
                    if (ex.getClass() == GoogleJsonResponseException.class) {
                        GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                        throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
                    } else {
                        throw new GeneralCloudException("An error occurred creating backend service " + ex.getMessage(), ex, CloudErrorType.GENERAL);
                    }
                } catch ( Exception ex ) {
                    throw new GeneralCloudException("Error creating backend service " + ex.getMessage(), ex, CloudErrorType.GENERAL);
                }
                backendService.setServiceUrl(gce.getBaseUrl() + ctx.getAccountNumber() + "/global/backendServices/" + backendService.getName());
            }
        }
    }

    public void createURLMap(ConvergedHttpLoadBalancer withConvergedHttpLoadBalancerOptions) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();
        GoogleMethod method = new GoogleMethod(provider);

        try {
            List<ConvergedHttpLoadBalancer.UrlSet> urlSets = withConvergedHttpLoadBalancerOptions.getUrlSets();

            UrlMap urlMap = new UrlMap();

            List<PathMatcher> pathMatchers = new ArrayList<PathMatcher>();
            List<HostRule> hostRules = new ArrayList<HostRule>();

            for (ConvergedHttpLoadBalancer.UrlSet urlSet : urlSets) {
                HostRule hostRule = new HostRule();
                List<String> hosts = new ArrayList<String>();
                String hostMatchPatterns = urlSet.getHostMatchPatterns();
                if (hostMatchPatterns.contains(",")) {
                    for (String hostMatchPattern : hostMatchPatterns.split(", ?")) {
                        hosts.add(hostMatchPattern);
                    }
                } else {
                    hosts.add(hostMatchPatterns);
                }
                hostRule.setHosts(hosts);
                hostRule.setPathMatcher(urlSet.getName());
                hostRules.add(hostRule);

                PathMatcher pathMatcher = new PathMatcher();
                pathMatcher.setName(urlSet.getName());
                //pathMatcher.setDescription(urlSet.getDescription()); // GCE does not support

                List<PathRule> pathRules = new ArrayList<PathRule>();

                Map<String, String> pathMap = urlSet.getPathMap();
                for (String key : pathMap.keySet()) {
                    PathRule pathRule = new PathRule();
                    List<String> paths = new ArrayList<String>();
                    if (key.equals("/*")) {
                        pathMatcher.setDefaultService(withConvergedHttpLoadBalancerOptions.getBackendServiceSelfUrl(pathMap.get(key)));
                    } else {
                        if (key.contains(",")) {
                            for (String path : key.split(", *")) {
                                paths.add(path);
                            }
                        } else {
                            paths.add(key);
                        }
                        pathRule.setPaths(paths);
                        pathRule.setService(withConvergedHttpLoadBalancerOptions.getBackendServiceSelfUrl(pathMap.get(key)));
                        pathRules.add(pathRule);
                    }
                }
                pathMatcher.setPathRules(pathRules);
                pathMatchers.add(pathMatcher);
            }

            urlMap.setHostRules(hostRules);
            urlMap.setName(getCapabilities().getConvergedHttpLoadBalancerNamingConstraints().convertToValidName(withConvergedHttpLoadBalancerOptions.getName(), Locale.US));
            urlMap.setPathMatchers(pathMatchers);
            urlMap.setDescription(withConvergedHttpLoadBalancerOptions.getDescription());  // GCE does not support
            urlMap.setDefaultService(withConvergedHttpLoadBalancerOptions.getBackendServiceSelfUrl(withConvergedHttpLoadBalancerOptions.getDefaultBackendService()));
            Operation job = gce.urlMaps().insert(ctx.getAccountNumber(), urlMap ).execute();
            method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);

        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred creating url map " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating url map " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }

        withConvergedHttpLoadBalancerOptions.setUrlMapSelfUrl(gce.getBaseUrl() + ctx.getAccountNumber() + "/global/urlMaps/" + withConvergedHttpLoadBalancerOptions.getName());
    }

    public void createTargetProxy(ConvergedHttpLoadBalancer withConvergedHttpLoadBalancerOptions) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();
        GoogleMethod method = new GoogleMethod(provider);
        TargetHttpProxy content = new TargetHttpProxy();

        List<ConvergedHttpLoadBalancer.TargetHttpProxy> targetHttpProxies = withConvergedHttpLoadBalancerOptions.getTargetHttpProxies();
        try {
            for (ConvergedHttpLoadBalancer.TargetHttpProxy targetHttpProxy : targetHttpProxies) {
                content.setName(getCapabilities().getConvergedHttpLoadBalancerNamingConstraints().convertToValidName(targetHttpProxy.getName(), Locale.US));
                content.setDescription(targetHttpProxy.getDescription());
                content.setUrlMap(withConvergedHttpLoadBalancerOptions.getSelfLink());
                Operation job = gce.targetHttpProxies().insert(ctx.getAccountNumber(), content ).execute();
                method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
                targetHttpProxy.setTargetProxySelfUrl(gce.getBaseUrl() + ctx.getAccountNumber() + "/global/targetHttpProxies/" + targetHttpProxy.getName());
            }
        } catch (IOException ex) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred creating target proxy " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating target proxy " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
    }

    public void createGlobalForwardingRule(ConvergedHttpLoadBalancer withConvergedHttpLoadBalancerOptions) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();
        GoogleMethod method = new GoogleMethod(provider);
        ForwardingRule gfwContent = new ForwardingRule();

        List<ConvergedHttpLoadBalancer.ForwardingRule> forwardingRules = withConvergedHttpLoadBalancerOptions.getForwardingRules();
        try {
            for (ConvergedHttpLoadBalancer.ForwardingRule forwardingRule : forwardingRules) {
                gfwContent.setName(getCapabilities().getConvergedHttpLoadBalancerNamingConstraints().convertToValidName(forwardingRule.getName(), Locale.US));
                gfwContent.setDescription(forwardingRule.getDescription());
                if (null != forwardingRule.getIpAddress()) {
                    gfwContent.setIPAddress(forwardingRule.getIpAddress());
                }
                gfwContent.setIPProtocol(forwardingRule.getIpProtocol());
                gfwContent.setPortRange(forwardingRule.getPortRange());
                gfwContent.setTarget(withConvergedHttpLoadBalancerOptions.getTargetProxySelfUrl(forwardingRule.getTarget()));
                Operation job = gce.globalForwardingRules().insert(ctx.getAccountNumber(), gfwContent ).execute();
                method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
                forwardingRule.setGlobalForwardingRuleSelfUrl(gce.getBaseUrl() + ctx.getAccountNumber() + "/global/httpHealthChecks/" + forwardingRule.getName());
            }
        } catch (IOException ex) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred creating GlobalForwardingRule: " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error occurred creating GlobalForwardingRule: " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
    }

    @Override
    public String createConvergedHttpLoadBalancer(@Nonnull ConvergedHttpLoadBalancer withConvergedHttpLoadBalancerOptions) throws CloudException, InternalException {
        try {
            createHttpHealthChecks(withConvergedHttpLoadBalancerOptions);
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating Converged Http Load Balancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
        try {
            createBackendService(withConvergedHttpLoadBalancerOptions);
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating Converged Http Load Balancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
        try {
            createURLMap(withConvergedHttpLoadBalancerOptions);
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating Converged Http Load Balancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
        try {
            createTargetProxy(withConvergedHttpLoadBalancerOptions);
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating Converged Http Load Balancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
        try {
            createGlobalForwardingRule(withConvergedHttpLoadBalancerOptions);
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating Converged Http Load Balancer " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }

        return withConvergedHttpLoadBalancerOptions.getSelfLink();
    }

    private void createHttpHealthChecks(ConvergedHttpLoadBalancer withConvergedHttpLoadBalancerOptions) throws CloudException, InternalException {
        Compute gce = provider.getGoogleCompute();

        GoogleMethod method = new GoogleMethod(provider);
        try {
            List<ConvergedHttpLoadBalancer.HealthCheck> healthChecks = withConvergedHttpLoadBalancerOptions.getHealthChecks();
            for  (ConvergedHttpLoadBalancer.HealthCheck healthCheck : healthChecks) {
                if (null == healthCheck.getSelfLink()) {
                    HttpHealthCheck httpHealthCheck = new HttpHealthCheck();
                    httpHealthCheck.setName(getCapabilities().getConvergedHttpLoadBalancerNamingConstraints().convertToValidName(healthCheck.getName(), Locale.US));
                    httpHealthCheck.setDescription(healthCheck.getDescription());
                    httpHealthCheck.setCheckIntervalSec(healthCheck.getCheckIntervalSec());
                    httpHealthCheck.setHealthyThreshold(healthCheck.getHealthyThreshold());
                    httpHealthCheck.setUnhealthyThreshold(healthCheck.getUnHealthyThreshold());
                    httpHealthCheck.setTimeoutSec(healthCheck.getTimeoutSec());
                    httpHealthCheck.setHost(healthCheck.getHost()); // optional i think
                    httpHealthCheck.setPort(healthCheck.getPort());
                    httpHealthCheck.setRequestPath(healthCheck.getRequestPath());
                    Operation job = gce.httpHealthChecks().insert(ctx.getAccountNumber(), httpHealthCheck).execute();
                    method.getOperationComplete(provider.getContext(), job, GoogleOperationType.GLOBAL_OPERATION, null, null);
                    healthCheck.setSelfLink(gce.getBaseUrl() + ctx.getAccountNumber() + "/global/httpHealthChecks/" + healthCheck.getName());
                }
            }
        } catch ( IOException ex ) {
            if (ex.getClass() == GoogleJsonResponseException.class) {
                GoogleJsonResponseException gjre = (GoogleJsonResponseException)ex;
                throw new GoogleException(CloudErrorType.GENERAL, gjre.getStatusCode(), gjre.getContent(), gjre.getDetails().getMessage());
            } else {
                throw new GeneralCloudException("An error occurred creating http health checks " + ex.getMessage(), ex, CloudErrorType.GENERAL);
            }
        } catch (Exception ex) {
            throw new GeneralCloudException("Error creating http health checks " + ex.getMessage(), ex, CloudErrorType.GENERAL);
        }
    }

    @Override
    public void updateTags(String convergedHttpLoadbalancerId, Tag... tags) throws CloudException, InternalException {
        // TODO Auto-generated method stub
    }

    @Override
    public void updateTags(String[] convergedHttpLoadbalancerIds, Tag... tags) throws CloudException, InternalException {
        // TODO Auto-generated method stub
    }

    @Override
    public void removeTags(String convergedHttpLoadbalancerId, Tag... tags) throws CloudException, InternalException {
        // TODO Auto-generated method stub
    }

    @Override
    public void removeTags(String[] convergedHttpLoadbalancerIds, Tag... tags) throws CloudException, InternalException {
        // TODO Auto-generated method stub
    }

    @Override
    public String[] mapServiceAction(ServiceAction action) {
        // TODO Auto-generated method stub
        return null;
    }
}
