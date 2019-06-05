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
 */package org.aguibert.testcontainers.framework.jupiter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.aguibert.testcontainers.framework.MicroProfileApplication;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

public class MicroProfileTestExtension implements BeforeAllCallback, TestInstancePostProcessor {

    static final Logger LOGGER = LoggerFactory.getLogger(MicroProfileTestExtension.class);

    private static final Map<Class<? extends SharedContainerConfiguration>, MicroProfileApplication<?>> sharedContainers = new HashMap<>();
    private static final Namespace NAMESPACE = Namespace.create(MicroProfileTestExtension.class);
    private static final String NAMESPACE_KEY = "mpExtensionKey";

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.put(NAMESPACE_KEY, testInstance);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        processSharedContainerConfig(context);
        injectRestClients(context);
    }

    private static void processSharedContainerConfig(ExtensionContext context) throws IllegalArgumentException, IllegalAccessException {
        Class<?> clazz = context.getRequiredTestClass();
        if (!clazz.isAnnotationPresent(SharedContainerConfig.class))
            return;

        Class<? extends SharedContainerConfiguration> configClass = clazz.getAnnotation(SharedContainerConfig.class).value();

        // First check if the SharedContainerConfig has implemented a manual container start
        try {
            SharedContainerConfiguration configInstance = configClass.newInstance();
            configInstance.startContainers();
            return;
        } catch (UnsupportedOperationException tolerable) {
        } catch (InstantiationException e) {
            throw new ExtensionConfigurationException("Problem creating new instance of class: " + configClass, e);
        }

        // If we get here, the user has not implemented a custom startContainers() method
        Set<GenericContainer<?>> containersToStart = new HashSet<>();
        for (Field containerField : AnnotationSupport.findAnnotatedFields(configClass, Container.class)) {
            if (!Modifier.isStatic(containerField.getModifiers()) || !Modifier.isPublic(containerField.getModifiers()))
                continue;
            boolean isStartable = GenericContainer.class.isAssignableFrom(containerField.getType());
            if (!isStartable)
                throw new ExtensionConfigurationException("Annotation is only supported for " + GenericContainer.class + " types");
            GenericContainer<?> startableContainer = (GenericContainer<?>) containerField.get(null);
            if (!startableContainer.isRunning()) {
                containersToStart.add(startableContainer);
            } else {
                LOGGER.info("Found already running contianer instance: " + startableContainer.getContainerId());
            }
        }
        LOGGER.info("Starting containers in parallel for " + configClass);
        for (GenericContainer<?> c : containersToStart)
            LOGGER.info("  " + c.getImage());
        long start = System.currentTimeMillis();
        containersToStart.parallelStream().forEach(GenericContainer::start);
        LOGGER.info("All containers started in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static void injectRestClients(ExtensionContext context) throws Exception {
        Class<?> clazz = context.getRequiredTestClass();
        List<Field> restClientFields = AnnotationSupport.findAnnotatedFields(clazz, Inject.class);
        if (restClientFields.size() == 0)
            return;

        MicroProfileApplication<?> mpApp = autoDiscoverMPApp(clazz, true);

        // At this point we have found exactly one MicroProfileApplication -- proceed with auto-configure
        if (!mpApp.isCreated() || !mpApp.isRunning())
            throw new ExtensionConfigurationException("Container " + mpApp.getDockerImageName() + " is not running yet. "
                                                      + "It should have been started by the @Testcontainers extension. "
                                                      + "TIP: Make sure that you list @TestContainers before @MicroProfileTest!");

        for (Field restClientField : restClientFields) {
            checkPublicStaticNonFinal(restClientField);
            Object restClient = mpApp.createRestClient(restClientField.getType());
            restClientField.set(null, restClient);
            LOGGER.debug("Injecting rest client for " + restClientField);
        }
    }

    private static MicroProfileApplication<?> autoDiscoverMPApp(Class<?> testClass, boolean errorIfNone) throws IllegalArgumentException, IllegalAccessException {
        // First check for any MicroProfileApplicaiton directly present on the test class
        List<Field> mpApps = AnnotationSupport.findAnnotatedFields(testClass, Container.class,
                                                                   f -> Modifier.isStatic(f.getModifiers()) &&
                                                                        Modifier.isPublic(f.getModifiers()) &&
                                                                        MicroProfileApplication.class.isAssignableFrom(f.getType()),
                                                                   HierarchyTraversalMode.TOP_DOWN);
        if (mpApps.size() == 1)
            return (MicroProfileApplication<?>) mpApps.get(0).get(null);
        if (mpApps.size() > 1)
            throw new ExtensionConfigurationException("Should be no more than 1 public static MicroProfileApplication field on " + testClass);

        // If none found, check any SharedContainerConfig
        String sharedConfigMsg = "";
        if (testClass.isAnnotationPresent(SharedContainerConfig.class)) {
            Class<? extends SharedContainerConfiguration> configClass = testClass.getAnnotation(SharedContainerConfig.class).value();
            MicroProfileApplication<?> mpApp = autoDiscoverMPApp(configClass, false);
            if (mpApp != null)
                return mpApp;
            sharedConfigMsg = " or " + configClass;
        }

        if (errorIfNone)
            throw new ExtensionConfigurationException("No public static MicroProfileApplication fields annotated with @Container were located " +
                                                      "on " + testClass + sharedConfigMsg + " to auto-connect with @RestClient fields.");
        return null;
    }

    private static void checkPublicStaticNonFinal(Field f) {
        if (!Modifier.isPublic(f.getModifiers()) ||
            !Modifier.isStatic(f.getModifiers()) ||
            Modifier.isFinal(f.getModifiers())) {
            throw new ExtensionConfigurationException("@RestClient annotated field must be public, static, and non-final: " + f.getName());
        }
    }

}
