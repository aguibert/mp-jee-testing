/*
 * Copyright (c) 2019 IBM Corporation and others
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package org.aguibert.testcontainers.framework;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.Future;

import org.aguibert.testcontainers.framework.spi.ServerAdapter;
import org.apache.cxf.jaxrs.client.JAXRSClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.ExposedPort;

public class MicroProfileApplication<SELF extends MicroProfileApplication<SELF>> extends GenericContainer<SELF> {

    static final Logger LOGGER = LoggerFactory.getLogger(MicroProfileApplication.class);

    private String appContextRoot;
    private ServerAdapter serverAdapter;
    private final List<Class<?>> providers = new ArrayList<>();

    public MicroProfileApplication() {
        super(new ImageFromDockerfile()
                        .withDockerfilePath("Dockerfile") // TODO use withDockerfile(File) here because it honors .dockerignore
                        .withFileFromPath(".", Paths.get(".")));
        commonInit();
    }

    public MicroProfileApplication(final String dockerImageName) {
        super(dockerImageName);
        commonInit();
    }

    public MicroProfileApplication(Future<String> dockerImageName) {
        super(dockerImageName);
        commonInit();
    }

    private void commonInit() {
        // Look for a ServerAdapter implementation (optional)
        List<ServerAdapter> adapters = new ArrayList<>(1);
        for (ServerAdapter adapter : ServiceLoader.load(ServerAdapter.class)) {
            adapters.add(adapter); // TODO: temporarily disable ServerAdapter so we can exercise the default path
            LOGGER.info("Found ServerAdapter: " + adapter.getClass());
        }
        if (adapters.size() == 0) {
            LOGGER.info("No ServerAdapter found. Using default settings.");
            serverAdapter = new DefaultServerAdapter();
        } else if (adapters.size() == 1) {
            serverAdapter = adapters.get(0);
            LOGGER.info("Only 1 ServerAdapter found. Will use: " + serverAdapter);
        } else {
            throw new IllegalStateException("Expected 0 or 1 ServerAdapters, but found: " + adapters);
        }
        addExposedPorts(serverAdapter.getDefaultHttpPort());
        withLogConsumer(new Slf4jLogConsumer(LOGGER));
        withAppContextRoot("/");
        providers.add(JsonBProvider.class);
    }

    /**
     * Sets the application context root. The protocol, hostname, and port do not need to be
     * included in the <code>appContextRoot</code> parameter. For example, an application
     * "foo.war" is available at <code>http://localhost:8080/foo/</code> the context root can
     * be set using <code>withAppContextRoot("/foo")</code>.
     */
    public SELF withAppContextRoot(String appContextRoot) {
        Objects.requireNonNull(appContextRoot);
        this.appContextRoot = appContextRoot = JAXRSUtilities.buildPath(appContextRoot);
        waitingFor(Wait.forHttp(this.appContextRoot)
                        .withStartupTimeout(Duration.ofSeconds(serverAdapter.getDefaultAppStartTimeout())));
        return self();
    }

    /**
     * Sets the path to be used to determine container readiness.
     * If the path starts with '/' it is an absolute path (after hostname and port). If it does not
     * start with '/', the path is relative to the current appContextRoot.
     */
    public SELF withReadinessPath(String readinessUrl) {
        withReadinessPath(readinessUrl, serverAdapter.getDefaultAppStartTimeout());
        return self();
    }

    /**
     * @param readinessUrl The container readiness path to be used to determine container readiness.
     *            If the path starts with '/' it is an absolute path (after hostname and port). If it does not
     *            start with '/', the path is relative to the current appContextRoot.
     * @param timeout The amount of time to wait for the container to be ready.
     */
    public SELF withReadinessPath(String readinessUrl, int timeoutSeconds) {
        Objects.requireNonNull(readinessUrl);
        readinessUrl = JAXRSUtilities.buildPath(readinessUrl);
        waitingFor(Wait.forHttp(readinessUrl)
                        .withStartupTimeout(Duration.ofSeconds(timeoutSeconds)));
        return self();
    }

    public SELF withMpRestClient(Class<?> restClient, String hostUrl) {
        String envName = restClient.getCanonicalName()//
                        .replaceAll("\\.", "_")
                        .replaceAll("\\$", "_") +
                         "_mp_rest_url";
        return withEnv(envName, hostUrl);
    }

    public SELF withJaxrsProvider(Class<?> providerClass) {
        providers.add(0, providerClass);
        return self();
    }

    public <T> T createRestClient(Class<T> clazz, String applicationPath) {
        Objects.requireNonNull(applicationPath, "Supplied 'applicationPath' must not be null");
        String urlPath = getBaseURL();
        urlPath += applicationPath;
        LOGGER.info("Building rest client for " + clazz + " with path: " + urlPath + " and providers: " + providers);
        return JAXRSClientFactory.create(urlPath, clazz, providers);
    }

    public <T> T createRestClient(Class<T> clazz) {
        return createRestClient(clazz, JAXRSUtilities.resolveJaxrsAppPath(appContextRoot, clazz));
    }

    public String getBaseURL() throws IllegalStateException {
        if (!this.isRunning())
            throw new IllegalStateException("Container must be running to determine hostname and port");
        return "http://" + this.getContainerIpAddress() + ':' + this.getFirstMappedPort();
    }

    private class DefaultServerAdapter implements ServerAdapter {

        private final InspectImageResponse imageData;
        private final int defaultHttpPort;

        public DefaultServerAdapter() {
            imageData = DockerClientFactory.instance().client().inspectImageCmd(getDockerImageName()).exec();
            LOGGER.info("Found exposed ports: " + Arrays.toString(imageData.getContainerConfig().getExposedPorts()));
            int bestChoice = -1;
            for (ExposedPort exposedPort : imageData.getContainerConfig().getExposedPorts()) {
                int port = exposedPort.getPort();
                // If any ports end with 80, assume they are HTTP ports
                if (Integer.toString(port).endsWith("80")) {
                    bestChoice = port;
                    break;
                } else if (bestChoice == -1) {
                    // if no ports match *80, then pick the first port
                    bestChoice = port;
                }
            }
            LOGGER.info("Automatically selecting default HTTP port: " + getDefaultHttpPort());
            defaultHttpPort = bestChoice;
        }

        @Override
        public int getDefaultHttpPort() {
            return defaultHttpPort;
        }

        @Override
        public int getDefaultHttpsPort() {
            return -1;
        }

        @Override
        public int getDefaultAppStartTimeout() {
            return 30;
        }

        @Override
        public ImageFromDockerfile getDefaultImage(File appFile) {
            throw new UnsupportedOperationException("Dynamically building image is not supported for default ServerAdapter.");
        }
    }

}