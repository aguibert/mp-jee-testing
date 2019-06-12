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

import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * @author aguibert
 */
public class AppContainerConfig implements SharedContainerConfiguration {

    @Container
    public static MicroProfileApplication<?> app = new MicroProfileApplication<>()
					.withNetwork(Network.SHARED)
					.withEnv("POSTGRES_HOSTNAME", "testpostgres")
                    .withEnv("POSTGRES_PORT", "5432")
                    .withAppContextRoot("/myservice");
					
	@Container
	public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>()
					.withNetwork(Network.SHARED)
					.withNetworkAliases("testpostgres")
					.withDatabaseName("testdb");	

    @Override
    public void startContainers() {
		//TODO: Reset to default if we move away from the Parallel streams API for loading
		postgres.start();
		app.start();
    }

}
