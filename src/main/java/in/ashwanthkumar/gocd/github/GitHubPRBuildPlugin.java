package in.ashwanthkumar.gocd.github;

import static in.ashwanthkumar.gocd.github.util.JSONUtils.fromJSON;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import com.google.gson.reflect.TypeToken;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import com.tw.go.plugin.GitHelper;
import com.tw.go.plugin.model.GitConfig;
import com.tw.go.plugin.model.ModifiedFile;
import com.tw.go.plugin.model.Revision;
import com.tw.go.plugin.util.ListUtil;
import com.tw.go.plugin.util.StringUtil;
import in.ashwanthkumar.gocd.github.provider.Provider;
import in.ashwanthkumar.gocd.github.settings.scm.PluginConfigurationView;
import in.ashwanthkumar.gocd.github.util.BranchFilter;
import in.ashwanthkumar.gocd.github.util.ExtendedGitCmdHelper;
import in.ashwanthkumar.gocd.github.util.GitFactory;
import in.ashwanthkumar.gocd.github.util.GitFolderFactory;
import in.ashwanthkumar.gocd.github.util.JSONUtils;
import in.ashwanthkumar.utils.collections.Lists;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

@Extension
public class GitHubPRBuildPlugin implements GoPlugin {
    private static final Logger LOGGER = Logger.getLoggerFor(GitHubPRBuildPlugin.class);

    public static final String EXTENSION_NAME = "scm";
    private static final List<String> goSupportedVersions = singletonList("1.0");

    public static final String REQUEST_SCM_CONFIGURATION = "scm-configuration";
    public static final String REQUEST_SCM_VIEW = "scm-view";
    public static final String REQUEST_VALIDATE_SCM_CONFIGURATION = "validate-scm-configuration";
    public static final String REQUEST_CHECK_SCM_CONNECTION = "check-scm-connection";
    public static final String REQUEST_PLUGIN_CONFIGURATION = "go.plugin-settings.get-configuration";
    public static final String REQUEST_PLUGIN_VIEW = "go.plugin-settings.get-view";
    public static final String REQUEST_VALIDATE_PLUGIN_CONFIGURATION = "go.plugin-settings.validate-configuration";

    public static final String GET_PLUGIN_SETTINGS = "go.processor.plugin-settings.get";

    public static final String REQUEST_LATEST_REVISION = "latest-revision";
    public static final String REQUEST_LATEST_REVISIONS_SINCE = "latest-revisions-since";
    public static final String REQUEST_CHECKOUT = "checkout";

    public static final String BRANCH_TO_REVISION_MAP = "BRANCH_TO_REVISION_MAP";
    private static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    public static final int SUCCESS_RESPONSE_CODE = 200;
    public static final int NOT_FOUND_RESPONSE_CODE = 404;
    public static final int INTERNAL_ERROR_RESPONSE_CODE = 500;

    public static final TypeToken<Map<String, Object>> REQUEST_BODY_TYPE = new TypeToken<Map<String, Object>>(){};
    public static final TypeToken<Map<String, String>> REVISION_MAP_TYPE = new TypeToken<Map<String, String>>(){};

    private Provider provider;
    private final GitFactory gitFactory;
    private final GitFolderFactory gitFolderFactory;
    private GoApplicationAccessor goApplicationAccessor;

    public GitHubPRBuildPlugin() {
        try {
            Properties properties = new Properties();
            properties.load(getClass().getResourceAsStream("/defaults.properties"));

            Class<?> providerClass = Class.forName(properties.getProperty("provider"));
            Constructor<?> constructor = providerClass.getConstructor();
            provider = (Provider) constructor.newInstance();
            gitFactory = new GitFactory();
            gitFolderFactory = new GitFolderFactory();
        } catch (Exception e) {
            throw new RuntimeException("could not create provider", e);
        }
    }

    public GitHubPRBuildPlugin(Provider provider, GitFactory gitFactory, GitFolderFactory gitFolderFactory, GoApplicationAccessor goApplicationAccessor) {
        this.provider = provider;
        this.gitFactory = gitFactory;
        this.gitFolderFactory = gitFolderFactory;
        this.goApplicationAccessor = goApplicationAccessor;
    }

    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor goApplicationAccessor) {
        this.goApplicationAccessor = goApplicationAccessor;
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest goPluginApiRequest) {
        LOGGER.info(goPluginApiRequest.requestName());
        LOGGER.info(goPluginApiRequest.requestBody());
        if (goPluginApiRequest.requestName().equals(REQUEST_SCM_CONFIGURATION)) {
            return handleSCMConfiguration();
        } else if (goPluginApiRequest.requestName().equals(REQUEST_SCM_VIEW)) {
            try {
                return handleSCMView();
            } catch (IOException e) {
                String message = "Failed to find template: " + e.getMessage();
                return renderJSON(INTERNAL_ERROR_RESPONSE_CODE, message);
            }
        } else if (goPluginApiRequest.requestName().equals(REQUEST_PLUGIN_CONFIGURATION)) {
            return handlePluginConfiguration();
        } else if (goPluginApiRequest.requestName().equals(REQUEST_PLUGIN_VIEW)) {
            try {
                return handlePluginView();
            } catch (IOException e) {
                String message = "Failed to find template: " + e.getMessage();
                return renderJSON(INTERNAL_ERROR_RESPONSE_CODE, message);
            }
        }  else if (goPluginApiRequest.requestName().equals(REQUEST_VALIDATE_PLUGIN_CONFIGURATION)) {
            return handlePluginValidation(goPluginApiRequest);
        }  else if (goPluginApiRequest.requestName().equals(REQUEST_VALIDATE_SCM_CONFIGURATION)) {
            return handleSCMValidation(goPluginApiRequest);
        } else if (goPluginApiRequest.requestName().equals(REQUEST_CHECK_SCM_CONNECTION)) {
            return handleSCMCheckConnection(goPluginApiRequest);
        } else if (goPluginApiRequest.requestName().equals(REQUEST_LATEST_REVISION)) {
            return handleGetLatestRevision(goPluginApiRequest);
        } else if (goPluginApiRequest.requestName().equals(REQUEST_LATEST_REVISIONS_SINCE)) {
            return handleLatestRevisionSince(goPluginApiRequest);
        } else if (goPluginApiRequest.requestName().equals(REQUEST_CHECKOUT)) {
            return handleCheckout(goPluginApiRequest);
        }
        return renderJSON(NOT_FOUND_RESPONSE_CODE, null);
    }

    private GoPluginApiResponse handlePluginValidation(GoPluginApiRequest goPluginApiRequest) {
        return renderJSON(SUCCESS_RESPONSE_CODE, Collections.emptyList());
    }

    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return new GoPluginIdentifier(EXTENSION_NAME, goSupportedVersions);
    }

    void setProvider(Provider provider) {
        this.provider = provider;
    }

    private GoPluginApiResponse handlePluginView() throws IOException {
        return getPluginView(provider, provider.getGeneralConfigurationView());
    }

    private GoPluginApiResponse handlePluginConfiguration() {
        return getPluginConfiguration(provider.getGeneralConfigurationView());
    }

    private GoPluginApiResponse handleSCMView() throws IOException {
        return getPluginView(provider, provider.getScmConfigurationView());
    }

    private GoPluginApiResponse handleSCMConfiguration() {
        return getPluginConfiguration(provider.getScmConfigurationView());
    }

    private GoPluginApiResponse getPluginView(Provider provider, PluginConfigurationView view) throws IOException {
        if (view.hasConfigurationView()) {
            Map<String, Object> response = new HashMap<>();
            response.put("displayValue", provider.getName());
            response.put("template", getFileContents(view.templateName()));
            return renderJSON(SUCCESS_RESPONSE_CODE, response);
        } else {
            return renderJSON(NOT_FOUND_RESPONSE_CODE, null);
        }
    }

    private GoPluginApiResponse getPluginConfiguration(PluginConfigurationView view) {
        Map<String, Object> response = view.fields();
        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }

    private GoPluginApiResponse handleSCMValidation(GoPluginApiRequest goPluginApiRequest) {
        Map<String, Object> requestBodyMap = fromJSON(goPluginApiRequest.requestBody(), REQUEST_BODY_TYPE);
        final Map<String, String> configuration = keyValuePairs(requestBodyMap, "scm-configuration");
        final GitConfig gitConfig = getGitConfig(configuration);

        List<Map<String, Object>> response = new ArrayList<>();
        validate(response, fieldValidation -> validateUrl(gitConfig, fieldValidation));
        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }

    private GoPluginApiResponse handleSCMCheckConnection(GoPluginApiRequest goPluginApiRequest) {
        Map<String, Object> requestBodyMap = fromJSON(goPluginApiRequest.requestBody(), REQUEST_BODY_TYPE);
        Map<String, String> configuration = keyValuePairs(requestBodyMap, "scm-configuration");
        GitConfig gitConfig = getGitConfig(configuration);

        Map<String, Object> response = new HashMap<>();
        List<String> messages = new ArrayList<>();

        checkConnection(gitConfig, response, messages);

        if (response.get("status") == null) {
            response.put("status", "success");
            messages.add("Could connect to URL successfully");
        }
        response.put("messages", messages);
        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }

    GoPluginApiResponse handleGetLatestRevision(GoPluginApiRequest goPluginApiRequest) {
        Map<String, Object> requestBodyMap = fromJSON(goPluginApiRequest.requestBody(), REQUEST_BODY_TYPE);
        Map<String, String> configuration = keyValuePairs(requestBodyMap, "scm-configuration");
        GitConfig gitConfig = getGitConfig(configuration);
        String revisionMapBehavior = configuration.get("revisionMapBehavior");
        LOGGER.info("revisionMapBehavior: " + revisionMapBehavior); // TODO debug
        String flyweightFolder = (String) requestBodyMap.get("flyweight-folder");
        LOGGER.info(String.format("Flyweight: %s", flyweightFolder));

        try {
            GitHelper git = gitFactory.create(gitConfig, gitFolderFactory.create(flyweightFolder));
            Map<String, String> branchToRevisionMap = buildBranchToRevisionMap(git);

            Pair<String, String> newerRevision = findNewerPrRevision(emptyMap(), branchToRevisionMap, configuration);

            if (newerRevision == null) {
                LOGGER.debug(String.format("No new PRs found for %s. Revisions: %s", gitConfig.getUrl(), branchToRevisionMap));
                return buildLatestRevisionResponse(null, branchToRevisionMap);
            }


            if ("store_all".equals(revisionMapBehavior)) {
                // Remove nothing from the map, meaning store all new PRs. So they're all marked as done.
            }
            else { // also covers "clear_all"
                // Remove all other branches from the response to ensure the next time those will be picked up by GoCD
                branchToRevisionMap.entrySet().removeIf(entry -> !Objects.equals(entry.getKey(), newerRevision.getKey()));
            }

            Revision revision = git.getDetailsForRevision(newerRevision.getValue());
            String branch = newerRevision.getKey();

            Map<String, Object> revisionMap = populateRevisionMap(gitConfig, branch, revision);
            LOGGER.info(String.format("Triggered build for %s with head at %s. Config URL: %s",
                    branch, revision.getRevision(), gitConfig.getUrl()));
            return buildLatestRevisionResponse(revisionMap, branchToRevisionMap);
        } catch (Throwable t) {
            LOGGER.warn("get latest revision: ", t);
            return renderJSON(INTERNAL_ERROR_RESPONSE_CODE, removeUsernameAndPassword(t.getMessage(), gitConfig));
        }
    }

    private String removeUsernameAndPassword(String message, GitConfig gitConfig) {
        String messageForDisplay = message;
        String password = gitConfig.getPassword();
        if (StringUtils.isNotBlank(password)) {
            messageForDisplay = message.replaceAll(password, "****");
        }
        String username = gitConfig.getUsername();
        if (StringUtils.isNotBlank(username)) {
            messageForDisplay = messageForDisplay.replaceAll(username, "****");
        }
        return messageForDisplay;
    }

    GoPluginApiResponse handleLatestRevisionSince(GoPluginApiRequest goPluginApiRequest) {
        Map<String, Object> requestBodyMap = fromJSON(goPluginApiRequest.requestBody(), REQUEST_BODY_TYPE);
        Map<String, String> configuration = keyValuePairs(requestBodyMap, "scm-configuration");
        final GitConfig gitConfig = getGitConfig(configuration);
        String revisionMapBehavior = configuration.get("revisionMapBehavior");
        LOGGER.info("revisionMapBehavior: " + revisionMapBehavior); // TODO debug
        Map<String, String> scmData = (Map<String, String>) requestBodyMap.get("scm-data");
        Map<String, String> oldPrRevisionMap = fromJSON(scmData.get(BRANCH_TO_REVISION_MAP), REVISION_MAP_TYPE);
        String flyweightFolder = (String) requestBodyMap.get("flyweight-folder");
        LOGGER.debug(String.format("Fetching latest for: %s", gitConfig.getUrl()));

        try {
            GitHelper git = gitFactory.create(gitConfig, gitFolderFactory.create(flyweightFolder));
            Map<String, String> newPrToRevisionMap = buildBranchToRevisionMap(git);

            Pair<String, String> newerRevision = findNewerPrRevision(oldPrRevisionMap, newPrToRevisionMap,
                    configuration);

            if (newerRevision == null) {
                LOGGER.debug(String.format("No updated PRs found for %s. Old: %s New: %s", gitConfig.getUrl(), oldPrRevisionMap,
                        newPrToRevisionMap));
                return buildLatestRevisionsResponse(null,
                        "clear_all".equals(revisionMapBehavior) ? emptyMap() : newPrToRevisionMap);
            }

            String pr = newerRevision.getKey();
            String latestSHA = newerRevision.getValue();
            String lastKnownSHA = oldPrRevisionMap.get(pr);
            LOGGER.info(String.format("new commits for %s PR %s, latest commit %s", gitConfig.getUrl(), pr, latestSHA));
            List<Map<String, Object>> revisions = findAllRevisionsSince(git, gitConfig, pr, lastKnownSHA, latestSHA);
            LOGGER.debug(String.format("Commits on %s since previous %s: %s", gitConfig.getUrl(), lastKnownSHA,
                    revisions.stream().map(m -> (String) m.get("revision")).collect(joining(", "))));

            Map<String, String> updatedPrToRevisionMap = new HashMap<>();

            if ("store_all".equals(revisionMapBehavior)) {
                // Store all new PRs. So they're all marked as done.
                updatedPrToRevisionMap.putAll(newPrToRevisionMap);
            }
            else if ("clear_all".equals(revisionMapBehavior)) {
                // Only store the PR we're building, so all other (old) PRs
                // are marked as unbuilt.
                updatedPrToRevisionMap.put(pr, latestSHA);
            }
            else {
                // Default behavior: keep the old PRs, only update the one we're building.
                // So any other new PRs will be built in future executions.
                updatedPrToRevisionMap.putAll(oldPrRevisionMap);
                updatedPrToRevisionMap.put(pr, latestSHA);
            }

            return buildLatestRevisionsResponse(revisions, updatedPrToRevisionMap);
        } catch (Throwable t) {
            LOGGER.warn("Failed to get latest revisions for " + gitConfig.getUrl(), t);
            return renderJSON(INTERNAL_ERROR_RESPONSE_CODE, removeUsernameAndPassword(t.getMessage(), gitConfig));
        }
    }

    private Map<String, String> buildBranchToRevisionMap(GitHelper git) {
        git.cloneOrFetch(provider.getRefSpec());
        Map<String, String> newBranchToRevisionMap = git.getBranchToRevisionMap(provider.getRefPattern());
        git.submoduleUpdate();

        return newBranchToRevisionMap;
    }

    private Pair<String, String> findNewerPrRevision(Map<String, String> oldBranchToRevisionMap,
            Map<String, String> newBranchToRevisionMap, Map<String, String> configuration) {
        BranchFilter branchFilter = provider
                .getScmConfigurationView()
                .getBranchFilter(configuration);

        for (String branch : newBranchToRevisionMap.keySet()) {
            if (branchFilter.isBranchValid(branch)) {
                if (branchHasNewChange(oldBranchToRevisionMap.get(branch), newBranchToRevisionMap.get(branch))) {
                    // If there are any changes we should return the only one of them.
                    // Otherwise, GoCD skips other changes (revisions) in this call.
                    // You can think about it like if we always return a minimum item
                    // of a set with comparable items.
                    String newValue = newBranchToRevisionMap.get(branch);
                    return Pair.of(branch, newValue);
                }
            } else {
                LOGGER.debug(String.format("Branch %s is filtered by branch matcher", branch));
            }
        }

        return null;
    }

    private List<Map<String, Object>> findAllRevisionsSince(GitHelper git, GitConfig gitConfig, String branch, String lastKnownSHA,
            String latestSHA) {
        List<Map<String, Object>> revisions = new ArrayList<>();

        if(StringUtils.isNotEmpty(lastKnownSHA)) {
            git.resetHard(latestSHA);
            List<Revision> allRevisionsSince;
            try {
                allRevisionsSince = git.getRevisionsSince(lastKnownSHA);
            } catch (Exception e) {
                allRevisionsSince = singletonList(git.getLatestRevision());
            }
            List<Map<String, Object>> changesSinceLastCommit = Lists.map(allRevisionsSince,
                    revision -> populateRevisionMap(gitConfig, branch, revision));
            revisions.addAll(changesSinceLastCommit);
        } else {
            Revision revision = git.getDetailsForRevision(latestSHA);
            Map<String, Object> revisionMap = populateRevisionMapForSHA(gitConfig, branch, revision);
            revisions.add(revisionMap);
        }

        return revisions;
    }

    private Map<String, Object> populateRevisionMapForSHA(GitConfig gitConfig, String branch, Revision revision) {
        // patch for building merge commits
        if (revision.isMergeCommit() && ListUtil.isEmpty(revision.getModifiedFiles())) {
            revision.setModifiedFiles(Lists.of(new ModifiedFile("/dev/null", "deleted")));
        }

        return populateRevisionMap(gitConfig, branch, revision);
    }

    private GoPluginApiResponse buildLatestRevisionResponse(Map<String, Object> revision,
            Map<String, String> updatedPrToRevisionMap) {
        Map<String, Object> response = new HashMap<>();
        if (revision != null) {
            response.put("revision", revision);
        }
        return addScmDataAndBuildResponse(updatedPrToRevisionMap, response);
    }

    private GoPluginApiResponse buildLatestRevisionsResponse(List<Map<String, Object>> revisions,
            Map<String, String> updatedPrToRevisionMap) {
        Map<String, Object> response = new HashMap<>();
        if (revisions != null) {
            response.put("revisions", revisions);
        }
        return addScmDataAndBuildResponse(updatedPrToRevisionMap, response);
    }

    private GoPluginApiResponse addScmDataAndBuildResponse(Map<String, String> updatedPrToRevisionMap, Map<String, Object> response) {
        Map<String, String> scmDataMap = new HashMap<>();
        scmDataMap.put(BRANCH_TO_REVISION_MAP, JSONUtils.toJSON(updatedPrToRevisionMap));
        response.put("scm-data", scmDataMap);

        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }

    private boolean branchHasNewChange(String previousSHA, String latestSHA) {
        return previousSHA == null || !previousSHA.equals(latestSHA);
    }

    private GoPluginApiResponse handleCheckout(GoPluginApiRequest goPluginApiRequest) {
        Map<String, Object> requestBodyMap = fromJSON(goPluginApiRequest.requestBody(), REQUEST_BODY_TYPE);
        Map<String, String> configuration = keyValuePairs(requestBodyMap, "scm-configuration");
        GitConfig gitConfig = getGitConfig(configuration);
        String destinationFolder = (String) requestBodyMap.get("destination-folder");
        Map<String, Object> revisionMap = (Map<String, Object>) requestBodyMap.get("revision");
        Map<String, String> customDataBag = (Map<String, String>) revisionMap.getOrDefault("data", emptyMap());
        String revision = (String) revisionMap.get("revision");
        LOGGER.info(String.format("destination: %s. commit: %s", destinationFolder, revision));

        try {
            ExtendedGitCmdHelper git = gitFactory.create(gitConfig, gitFolderFactory.create(destinationFolder));
            git.cloneOrFetch(provider.getRefSpec());

            String branch = customDataBag.getOrDefault("PR_CHECKOUT_BRANCH", "gocd-pr");
            git.checkoutNewBranch(branch);

            git.resetHard(revision);
            git.submoduleUpdate();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("messages", singletonList(String.format("Checked out to revision %s", revision)));

            return renderJSON(SUCCESS_RESPONSE_CODE, response);
        } catch (Throwable t) {
            LOGGER.warn("checkout: ", t);
            return renderJSON(INTERNAL_ERROR_RESPONSE_CODE, t.getMessage());
        }
    }

    GitConfig getGitConfig(Map<String, String> configuration) {
        GitConfig gitConfig = new GitConfig(
                configuration.get("url"),
                configuration.get("username"),
                configuration.get("password"),
                StringUtils.trimToNull(configuration.get("defaultBranch")),
                true,
                Boolean.parseBoolean(configuration.get("shallowClone")));
        provider.addConfigData(gitConfig);
        return gitConfig;
    }

    private void validate(List<Map<String, Object>> response, FieldValidator fieldValidator) {
        Map<String, Object> fieldValidation = new HashMap<>();
        fieldValidator.validate(fieldValidation);
        if (!fieldValidation.isEmpty()) {
            response.add(fieldValidation);
        }
    }

    Map<String, Object> populateRevisionMap(GitConfig gitConfig, String branch, Revision revision) {
        Map<String, Object> response = new HashMap<>();
        response.put("revision", revision.getRevision());
        response.put("user", revision.getUser());
        response.put("timestamp", new SimpleDateFormat(DATE_PATTERN).format(revision.getTimestamp()));
        response.put("revisionComment", revision.getComment());
        List<Map<String, String>> modifiedFilesMapList = new ArrayList<>();
        if (!ListUtil.isEmpty(revision.getModifiedFiles())) {
            for (ModifiedFile modifiedFile : revision.getModifiedFiles()) {
                Map<String, String> modifiedFileMap = new HashMap<>();
                modifiedFileMap.put("fileName", modifiedFile.getFileName());
                modifiedFileMap.put("action", modifiedFile.getAction());
                modifiedFilesMapList.add(modifiedFileMap);
            }
        }
        response.put("modifiedFiles", modifiedFilesMapList);
        Map<String, String> customDataBag = new HashMap<>();
        provider.populateRevisionData(gitConfig, branch, revision.getRevision(), customDataBag);

        customDataBag.put("PR_CHECKOUT_BRANCH", determineCheckoutBranch(customDataBag));

        response.put("data", customDataBag);
        return response;
    }

    private String determineCheckoutBranch(Map<String, String> customDataBag) {
        // Use the source branch suggested by the provider, if available:
        String checkoutBranch = customDataBag.getOrDefault("PR_BRANCH", customDataBag.get("CURRENT_BRANCH"));
        if (checkoutBranch == null) {
            // If not, use a generic name but include the PR identifier for reference, if available:
            // Don't use "pr" as the name because at least for Bitbucket there are already pr/ git refs.
            checkoutBranch = "gocd-pr";
            if (customDataBag.containsKey("PR_ID")) {
                checkoutBranch += "/" + customDataBag.get("PR_ID");
            }
        }

        // git doesn't like colons in e.g. "owner:branch" returned by GitHub
        return checkoutBranch.replace(':', '/');
    }

    static Map<String, String> keyValuePairs(Map<String, Object> requestBodyMap, String mainKey) {
        Map<String, String> keyValuePairs = new HashMap<>();
        Map<String, Object> fieldsMap = (Map<String, Object>) requestBodyMap.get(mainKey);
        for (String key : fieldsMap.keySet()) {
            Map<String, Object> fieldProperties = (Map<String, Object>) fieldsMap.get(key);
            String value = (String) fieldProperties.get("value");
            keyValuePairs.put(key, value);
        }
        return keyValuePairs;
    }

    public void validateUrl(GitConfig gitConfig, Map<String, Object> fieldMap) {
        if (StringUtil.isEmpty(gitConfig.getUrl())) {
            fieldMap.put("key", "url");
            fieldMap.put("message", "URL is a required field");
        } else if (!provider.isValidURL(gitConfig.getUrl())) {
            fieldMap.put("key", "url");
            fieldMap.put("message", "Invalid URL");
        }
    }

    public void checkConnection(GitConfig gitConfig, Map<String, Object> response, List<String> messages) {
        if (StringUtil.isEmpty(gitConfig.getUrl())) {
            response.put("status", "failure");
            messages.add("URL is empty");
        } else if (!provider.isValidURL(gitConfig.getUrl())) {
            response.put("status", "failure");
            messages.add("Invalid URL");
        } else {
            try {
                provider.checkConnection(gitConfig);
            } catch (Exception e) {
                response.put("status", "failure");
                messages.add(e.getMessage());
            }
        }
    }

    private String getFileContents(String filePath) throws IOException {
        return IOUtils.toString(getClass().getResourceAsStream(filePath), StandardCharsets.UTF_8);
    }

    GoPluginApiResponse renderJSON(final int responseCode, Object response) {
        final String json = response == null ? null : JSONUtils.toJSON(response);
        return new GoPluginApiResponse() {
            @Override
            public int responseCode() {
                return responseCode;
            }

            @Override
            public Map<String, String> responseHeaders() {
                return null;
            }

            @Override
            public String responseBody() {
                return json;
            }
        };
    }

}
