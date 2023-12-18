package rocks.inspectit.ocelot.agentconfiguration;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import rocks.inspectit.ocelot.file.FileInfo;
import rocks.inspectit.ocelot.file.FileManager;
import rocks.inspectit.ocelot.file.accessor.AbstractFileAccessor;
import rocks.inspectit.ocelot.file.accessor.git.RevisionAccess;
import rocks.inspectit.ocelot.mappings.AgentMappingSerializer;
import rocks.inspectit.ocelot.mappings.model.AgentMapping;
import rocks.inspectit.ocelot.utils.CancellableTask;
import rocks.inspectit.ocelot.utils.DocsObjectsLoader;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A task for asynchronously loading the configurations based on a given list of mappings.
 */
@Slf4j
class AgentConfigurationReloadTask extends CancellableTask<List<AgentConfiguration>> {

    /**
     * Predicate to check if a given file path ends with .yml or .yaml
     */
    private static final Predicate<String> HAS_YAML_ENDING = filePath -> filePath.toLowerCase()
            .endsWith(".yml") || filePath.toLowerCase().endsWith(".yaml");

    private FileManager fileManager;

    private AgentMappingSerializer mappingsSerializer;

    /**
     * Creates a new reload task, but does NOT start it.
     * The loading process is done in {@link #run()}.
     *
     * @param mappingsSerializer the serializer responsible for extracting the mappings from the current revision
     * @param fileManager        the FileManager used to read the configuration files
     * @param onLoadCallback     invoked when the loading has finished successfully. Will not be invoked if the loading failed or was canceled.
     */
    public AgentConfigurationReloadTask(AgentMappingSerializer mappingsSerializer, FileManager fileManager, Consumer<List<AgentConfiguration>> onLoadCallback) {
        super(onLoadCallback);
        this.mappingsSerializer = mappingsSerializer;
        this.fileManager = fileManager;
    }

    /**
     * Performs the actual loading, should only be invoked once.
     */
    @Override
    public void run() {
        log.info("Starting configuration reloading...");
        RevisionAccess fileAccess = mappingsSerializer.getRevisionAccess();

        if (!fileAccess.agentMappingsExist()) {
            log.error("No agent mappings file was found on the current branch! Please add '{}' to the current branch.",
                    AbstractFileAccessor.AGENT_MAPPINGS_FILE_NAME);
            onTaskSuccess(Collections.emptyList());
            return;
        }
        List<AgentMapping> mappingsToLoad = mappingsSerializer.readAgentMappings(fileAccess);
        List<AgentConfiguration> newConfigurations = new ArrayList<>();
        for (AgentMapping mapping : mappingsToLoad) {
            try {
                AgentConfiguration agentConfiguration = createAgentConfiguration(mapping);
                if (isCanceled()) {
                    log.debug("Configuration reloading canceled");
                    return;
                }
                newConfigurations.add(agentConfiguration);
            } catch (Exception e) {
                log.error("Could not load agent configuration for agent mapping '{}'.", mapping.name(), e);
            }
        }
        onTaskSuccess(newConfigurations);
    }

    /**
     * Creates the whole configuration for one agent with the provided agent mapping
     * @param mapping the mapping to load
     * @return Configuration for the agent mapping
     */
    @VisibleForTesting
    AgentConfiguration createAgentConfiguration(AgentMapping mapping) {
        AbstractFileAccessor fileAccessor = getFileAccessorForMapping(mapping);

        LinkedHashSet<String> allYamlFiles = new LinkedHashSet<>();
        for (String path : mapping.sources()) {
            if (isCanceled()) {
                return null;
            }
            allYamlFiles.addAll(getAllYamlFiles(fileAccessor, path));
        }

        String configYaml = loadConfigYaml(fileAccessor, allYamlFiles);
        Map<String, Set<String>> docsObjectsByFile = loadDocsObjectsByFile(fileAccessor, allYamlFiles);

        AgentConfiguration agentConfiguration = AgentConfiguration.builder()
                .mapping(mapping)
                .docsObjectsByFile(docsObjectsByFile)
                .configYaml(configYaml)
                .build();

        return agentConfiguration;
    }

    /**
     * Loads the given mapping as yaml string.
     *
     * @param fileAccessor the accessor to use for reading the files
     * @param allYamlFiles the list of yaml files, which should be merged
     * @return the merged yaml for the given mapping or an empty string if the mapping does not contain any existing files
     * If this task has been canceled, null is returned.
     */
    private String loadConfigYaml(AbstractFileAccessor fileAccessor, LinkedHashSet<String> allYamlFiles) {
        Object result = null;
        for (String path : allYamlFiles) {
            if (isCanceled()) {
                return null;
            }
            result = loadAndMergeYaml(fileAccessor, result, path);
        }
        return result == null ? "" : new Yaml().dump(result);
    }

    /**
     * Loads all documentable objects of each file for the current agent
     *
     * @param fileAccessor the accessor to use for reading the files
     * @param allYamlFiles the list of yaml files, which should be merged
     * @return A set of defined objects for each file
     */
    private Map<String, Set<String>> loadDocsObjectsByFile(AbstractFileAccessor fileAccessor, LinkedHashSet<String> allYamlFiles) {
        Map<String, Set<String>> docsObjectsByFile = new HashMap<>();
        for (String path : allYamlFiles) {
            String src = fileAccessor.readConfigurationFile(path).orElse("");
            Set<String> objects = Collections.emptySet();

            try {
                objects = DocsObjectsLoader.loadObjects(src);
            } catch (Exception e) {
                log.warn("Could not parse configuration: {}", path, e);
            }

            docsObjectsByFile.put(path, objects);
        }

        return docsObjectsByFile;
    }

    private AbstractFileAccessor getFileAccessorForMapping(AgentMapping mapping) {
        return switch (mapping.sourceBranch()) {
            case LIVE -> fileManager.getLiveRevision();
            case WORKSPACE -> fileManager.getWorkspaceRevision();
            default -> throw new UnsupportedOperationException("Unhandled branch: " + mapping.sourceBranch());
        };
    }

    /**
     * If the given path is a yaml file, a list containing only it is returned.
     * If the path is a directory, the absolute path of all contained yaml files is returned in alphabetical order.
     * If it is neither, an empty list is returned.
     *
     * @param path the path to check for yaml files, can start with a slash which will be ignored
     *
     * @return a list of absolute paths of contained YAML files
     */
    private List<String> getAllYamlFiles(AbstractFileAccessor fileAccessor, String path) {
        String cleanedPath;
        if (path.startsWith("/")) {
            cleanedPath = path.substring(1);
        } else {
            cleanedPath = path;
        }

        if (fileAccessor.configurationFileExists(cleanedPath)) {
            if (fileAccessor.configurationFileIsDirectory(cleanedPath)) {
                List<FileInfo> fileInfos = fileAccessor.listConfigurationFiles(cleanedPath);

                return fileInfos.stream()
                        .flatMap(file -> file.getAbsoluteFilePaths(cleanedPath))
                        .filter(HAS_YAML_ENDING)
                        .sorted()
                        .collect(Collectors.toList());
            } else if (HAS_YAML_ENDING.test(cleanedPath)) {
                return Collections.singletonList(cleanedPath);
            }
        }
        return Collections.emptyList();
    }

    /**
     * Loads a yaml file as a Map/List structure and merges it with an existing map/list structure
     *
     * @param toMerge the existing structure of nested maps / lists with which the loaded yaml will be merged.
     * @param path    the path of the yaml file to load
     *
     * @return the merged structure
     */
    private Object loadAndMergeYaml(AbstractFileAccessor fileAccessor, Object toMerge, String path) {
        Yaml yaml = new Yaml();
        String src = fileAccessor.readConfigurationFile(path).orElse("");

        try {
            Map<String, Object> loadedYaml = yaml.load(src);
            if (toMerge == null) {
                return loadedYaml;
            } else {
                return ObjectStructureMerger.merge(toMerge, loadedYaml);
            }
        } catch (Exception e) {
            throw new InvalidConfigurationFileException(path, e);
        }
    }

    /**
     * This exception will be thrown if a configuration file cannot be parsed, e.g. it contains invalid characters.
     */
    static class InvalidConfigurationFileException extends RuntimeException {

        public InvalidConfigurationFileException(String path, Exception e) {
            super(String.format("The configuration file '%s' is invalid and cannot be parsed.", path), e);
        }
    }
}
