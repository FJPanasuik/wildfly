/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.server;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.jboss.as.model.AbstractServerModelUpdate;
import org.jboss.logging.Logger;
import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceActivator;
import org.jboss.msc.service.ServiceActivatorContext;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.staxmapper.XMLMapper;

/**
 * The standalone server implementation.
 *
 * @author Emanuel Muckenhuber
 * @author Thomas.Diesler@jboss.com
 */
public class StandaloneServerImpl implements StandaloneServer {

    private static final String STANDALONE_XML = "standalone.xml";
    private final StandardElementReaderRegistrar extensionRegistrar;

    static final Logger log = Logger.getLogger("org.jboss.as.server");

    private final ServerEnvironment environment;
    private volatile ServiceContainer serviceContainer;

    StandaloneServerImpl(ServerEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("bootstrapConfig is null");
        }
        this.environment = environment;
        extensionRegistrar = StandardElementReaderRegistrar.Factory.getRegistrar();
    }

    @Override
    public void start() throws ServerStartException {
        start(Collections.<ServiceActivator> emptyList());
    }

    @Override
    public void start(List<ServiceActivator> serviceActivators) throws ServerStartException {
        final File standalone = new File(environment.getServerConfigurationDir(), STANDALONE_XML);
        if (!standalone.isFile()) {
            throw new ServerStartException("File " + standalone.getAbsolutePath() + " does not exist.");
        }
        if (!standalone.canWrite()) {
            throw new ServerStartException("File " + standalone.getAbsolutePath() + " is not writable.");
        }
        final List<AbstractServerModelUpdate<?>> updates = new ArrayList<AbstractServerModelUpdate<?>>();
        try {
            final XMLMapper mapper = XMLMapper.Factory.create();
            extensionRegistrar.registerStandardStandaloneReaders(mapper);
            BufferedInputStream input = new BufferedInputStream(new FileInputStream(standalone));
            XMLStreamReader streamReader = XMLInputFactory.newInstance().createXMLStreamReader(input);
            mapper.parseDocument(updates, streamReader);
        } catch (Exception e) {
            throw new ServerStartException("Caught exception during processing of standalone.xml", e);
        }

        final CountDownLatch startStopLatch = new CountDownLatch(1);
        final ServerStartTask startTask = new ServerStartTask(0, serviceActivators, updates, environment);
        startTask.run(Collections.<ServiceActivator> singletonList(new ServerStartupService(startStopLatch)));

        try {
            startStopLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            //
        }

        if (serviceContainer == null) {
            throw new ServerStartException("ServiceContainer not available");
        }
    }

    @Override
    public void stop() {
        if (serviceContainer == null) {
            throw new IllegalStateException("Server not started");
        }
        final CountDownLatch startStopLatch = new CountDownLatch(1);
        ServiceController<?> controller = serviceContainer.getService(ServerStartTask.AS_SERVER_SERVICE_NAME);
        controller.addListener(new AbstractServiceListener<Object>() {

            @Override
            public void listenerAdded(ServiceController<? extends Object> controller) {
                controller.setMode(Mode.REMOVE);
            }

            @Override
            public void serviceStopped(ServiceController<? extends Object> controller) {
                startStopLatch.countDown();
                serviceContainer = null;
            }

            @Override
            public void serviceFailed(ServiceController<? extends Object> controller, StartException reason) {
                startStopLatch.countDown();
                serviceContainer = null;
            }
        });

        try {
            startStopLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            //
        }
    }

    class ServerStartupService implements Service<Void>, ServiceActivator {

        private final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("server", "startup");
        private final CountDownLatch startStopLatch;

        ServerStartupService(final CountDownLatch startStopLatch) {
            this.startStopLatch = startStopLatch;
        }

        @Override
        public void activate(ServiceActivatorContext context) {
            final ServiceTarget serviceTarget = context.getServiceTarget();
            serviceTarget.addService(SERVICE_NAME, this)
                    .addDependency(ServerStartTask.AS_SERVER_SERVICE_NAME)
                    //FIXME workaround to make this wait for more things that must be up before we can deploy things
                    //while waiting for the new bootstrap API
                    .addDependency(ServiceName.JBOSS.append("arquillian", "testrunner"))
                    .addDependency(ServerController.SERVICE_NAME.append("operation", "handler"))
                    .addDependency(ServiceName.JBOSS.append("naming", "context", "java", "app"))
                    .addDependency(ServiceName.JBOSS.append("naming", "context", "java", "global"))
                    .addDependency(ServiceName.JBOSS.append("naming", "context", "java", "comp"))
                    .addDependency(ServiceName.JBOSS.append("naming", "context", "java", "module"))
                    .addDependency(ServiceName.JBOSS.append("deployment-manager", "server", "local"))
                    .addDependency(ServiceName.JBOSS.append("web", "connector", "http"))
                    .addDependency(ServiceName.JBOSS.append("connector", "config"))
                    .addDependency(ServiceName.JBOSS.append("messaging", "jms", "connection-factory", "InVmConnectionFactory"))
                    .addDependency(ServiceName.JBOSS.append("messaging", "jms", "connection-factory", "RemoteConnectionFactory"))
                    .addDependency(ServiceName.JBOSS.append("messaging", "jms" , "queue", "testQueue"))
                    .addDependency(ServiceName.JBOSS.append("messaging", "jms" , "topic", "testTopic"))
                    .addDependency(ServiceName.JBOSS.append("mbean", "server"))
                    .install();
        }

        @Override
        public void start(StartContext context) throws StartException {
            serviceContainer = context.getController().getServiceContainer();
            startStopLatch.countDown();
        }

        @Override
        public void stop(StopContext context) {
        }

        @Override
        public Void getValue() throws IllegalStateException {
            return null;
        }
    }
}
