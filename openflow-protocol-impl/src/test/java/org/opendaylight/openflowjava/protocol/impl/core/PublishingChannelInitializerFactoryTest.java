/*
 * Copyright (c) 2014 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.openflowjava.protocol.impl.core;

import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opendaylight.openflowjava.protocol.api.connection.SwitchConnectionHandler;
import org.opendaylight.openflowjava.protocol.api.connection.TlsConfiguration;
import org.opendaylight.openflowjava.protocol.api.connection.TlsConfigurationImpl;
import org.opendaylight.openflowjava.protocol.impl.deserialization.DeserializationFactory;
import org.opendaylight.openflowjava.protocol.impl.serialization.SerializationFactory;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.config.rev140630.KeystoreType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.config.rev140630.PathType;

/**
 *
 * @author jameshall
 */
public class PublishingChannelInitializerFactoryTest {

    TlsConfiguration tlsConfiguration ;
    PublishingChannelInitializerFactory factory;
    private final long switchIdleTimeOut = 60;
    @Mock SwitchConnectionHandler switchConnectionHandler ;
    @Mock SerializationFactory serializationFactory;
    @Mock DeserializationFactory deserializationFactory ;

    /**
     * Sets up test environment
     */
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        factory = new PublishingChannelInitializerFactory();
        tlsConfiguration = new TlsConfigurationImpl(KeystoreType.JKS, "/ctlTrustStore",
                PathType.CLASSPATH, KeystoreType.JKS, "/ctlKeystore", PathType.CLASSPATH);
        factory.setDeserializationFactory(deserializationFactory);
        factory.setSerializationFactory(serializationFactory);
        factory.setSwitchConnectionHandler(switchConnectionHandler);
        factory.setSwitchIdleTimeout(switchIdleTimeOut);
        factory.setTlsConfig(tlsConfiguration);
    }

    /**
     * Test {@link PublishingChannelInitializer} creation
     */
    @Test
    public void testCreatePublishingChannelInitializer() {
        PublishingChannelInitializer initializer = factory.createPublishingChannelInitializer() ;
        assertNotNull( initializer );
    }
}