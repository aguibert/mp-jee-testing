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
package org.aguibert.liberty;

import org.aguibert.testcontainers.framework.MicroProfileApplication;
import org.aguibert.testcontainers.framework.jupiter.SharedContainerConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;

public class AppContainerConfig implements SharedContainerConfiguration {

    @Container
    public static MicroProfileApplication<?> app = new MicroProfileApplication<>()
                    .withNetwork(Network.SHARED)
                    .withAppContextRoot("/myservice")
                    .withEnv("MONGO_HOSTNAME", "testmongo")
                    .withEnv("MONGO_PORT", "27017")
                    .withMpRestClient(ExternalRestServiceClient.class, "http://mockserver:" + MockServerContainer.PORT);

    @Container
    public static MockServerContainer mockServer = new MockServerContainer()
                    .withNetwork(Network.SHARED)
                    .withNetworkAliases("mockserver");

    @Container
    public static GenericContainer<?> mongo = new GenericContainer<>("mongo:3.4")
                    .withNetwork(Network.SHARED)
                    .withNetworkAliases("testmongo");

    @Override
    public void startContainers() {
        // OPTIONAL: this method may be implemented to do custom instantiation/ordering of containers
        SharedContainerConfiguration.super.startContainers();
    }

}
