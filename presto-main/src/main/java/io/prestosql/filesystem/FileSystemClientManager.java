/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.filesystem;

import io.airlift.log.Logger;
import io.prestosql.spi.classloader.ThreadContextClassLoader;
import io.prestosql.spi.filesystem.HetuFileSystemClient;
import io.prestosql.spi.filesystem.HetuFileSystemClientFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class FileSystemClientManager
{
    private static final Logger LOG = Logger.get(FileSystemClientManager.class);
    private static final String FS_CLIENT_TYPE = "fs.client.type";
    private static final String FS_CONFIG_DIR = "etc/filesystem/";
    private static final String DEFAULT_CONFIG_NAME = "default";

    private static final Map<String, HetuFileSystemClientFactory> fileSystemFactories = new ConcurrentHashMap<>();
    private static final Map<String, Properties> availableFileSystemConfigs = new ConcurrentHashMap<>();

    private Properties defaultProfile;

    public FileSystemClientManager()
    {
        // Default filesystem to be a local filesystem client
        defaultProfile = new Properties();
        defaultProfile.setProperty(FS_CLIENT_TYPE, "local");
    }

    public void addFileSystemClientFactories(HetuFileSystemClientFactory factory)
    {
        if (fileSystemFactories.putIfAbsent(factory.getName(), factory) != null) {
            throw new IllegalArgumentException(format("Factory for %s filesystem is already registered", factory.getName()));
        }
    }

    /**
     * Loads pre-defined file system profiles in the etc folder.
     * These configs are loaded for clients' usage in presto-main package
     * through {@link FileSystemClientManager#getFileSystemClient(String, Path)}.
     *
     * @throws IOException when exceptions occur during reading profiles
     */
    public void loadFactoryConfigs()
            throws IOException
    {
        LOG.info(String.format("-- Available file system client factories: %s --", fileSystemFactories.keySet().toString()));
        LOG.info("-- Loading file system configs --");

        File configDir = new File(FS_CONFIG_DIR);
        if (!configDir.exists() || !configDir.isDirectory()) {
            LOG.info("-- File system configs not found. Skipped loading --");
            return;
        }

        String[] filesystems = requireNonNull(configDir.list(),
                "Error reading file system config directory: " + FS_CONFIG_DIR);

        for (String fileName : filesystems) {
            if (!fileName.endsWith(".properties")) {
                continue;
            }
            String configName = fileName.replaceAll("\\.properties", "");
            File configFile = new File(FS_CONFIG_DIR + fileName);
            Properties properties = loadProperties(configFile);

            // validate config file: type is specified and corresponding factory is available
            String configType = properties.getProperty(FS_CLIENT_TYPE);
            checkState(configType != null, "%s must be specified in %s",
                    FS_CLIENT_TYPE, configFile.getCanonicalPath());
            checkState(fileSystemFactories.containsKey(configType),
                    "Factory for file system type %s not found", configType);

            // If a file defines default properties, overwrite default then continue to next file
            if (DEFAULT_CONFIG_NAME.equals(configName)) {
                defaultProfile = properties;
                LOG.info("default profile has been overridden by default.properties");
            }
            // otherwise register config file into the map
            else {
                availableFileSystemConfigs.put(configName, properties);
                LOG.info(String.format("Loaded '%s' file system config '%s'", configType, configName));
            }
        }

        LOG.info(String.format("-- Loaded file system profiles: %s --",
                availableFileSystemConfigs.keySet().toString()));
    }

    /**
     * Get the default file system client
     *
     * @return a file system client constructed by default configurations
     * @throws IOException
     */
    public HetuFileSystemClient getFileSystemClient(Path root)
            throws IOException
    {
        return getFileSystemClient(DEFAULT_CONFIG_NAME, root);
    }

    /**
     * Get a file system client with pre-defined profile in filesystem config folder
     *
     * @param name name of the filesystem profile defined in the filesystem config folder,
     * or the default profile name (same as {@link FileSystemClientManager#getFileSystemClient(Path)} if provide default name)
     * @param root Workspace root of the filesystem client. It will only be allowed to access filesystem within this directory.
     * @return a {@link HetuFileSystemClient}
     * @throws IOException exception thrown during constructing the client
     */
    public HetuFileSystemClient getFileSystemClient(String name, Path root)
            throws IOException
    {
        if (!DEFAULT_CONFIG_NAME.equals(name) && !availableFileSystemConfigs.containsKey(name)) {
            throw new IllegalArgumentException(String.format("Profile %s is not available. Please check the name provided.", name));
        }
        Properties fsConfig = DEFAULT_CONFIG_NAME.equals(name) ? defaultProfile : availableFileSystemConfigs.get(name);
        String type = fsConfig.getProperty(FS_CLIENT_TYPE);
        HetuFileSystemClientFactory factory = fileSystemFactories.get(type);
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(factory.getClass().getClassLoader())) {
            return factory.getFileSystemClient(fsConfig, root);
        }
    }

    /**
     * Get a file system client with a user-defined properties object
     *
     * @param properties properties used to construct the file system client
     * @return a {@link HetuFileSystemClient}
     * @throws IOException exception thrown during constructing the client
     */
    public HetuFileSystemClient getFileSystemClient(Properties properties)
            throws IOException
    {
        String type = checkProperty(properties, FS_CLIENT_TYPE);
        checkState(fileSystemFactories.containsKey(type),
                "Factory for file system type %s not found", type);
        HetuFileSystemClientFactory factory = fileSystemFactories.get(type);
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(factory.getClass().getClassLoader())) {
            return factory.getFileSystemClient(properties);
        }
    }

    private Properties loadProperties(File configFile)
            throws IOException
    {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(configFile)) {
            properties.load(in);
        }
        return properties;
    }

    private String checkProperty(Properties properties, String key)
    {
        String val = properties.getProperty(key);
        if (val == null) {
            throw new IllegalArgumentException(String.format("Configuration entry '%s' must be specified", key));
        }
        return val;
    }
}
