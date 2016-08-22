/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.server.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.config.keys.KeyRandomArt;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.server.ServerAuthenticationManager;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.util.test.BaseTestSupport;
import org.apache.sshd.util.test.Utils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WelcomeBannerTest extends BaseTestSupport {
    private static SshServer sshd;
    private static int port;
    private static SshClient client;

    public WelcomeBannerTest() {
        super();
    }

    @BeforeClass
    public static void setupClientAndServer() throws Exception {
        sshd = Utils.setupTestServer(WelcomeBannerTest.class);
        sshd.start();
        port = sshd.getPort();

        client = Utils.setupTestClient(WelcomeBannerTest.class);
        client.start();
    }

    @AfterClass
    public static void tearDownClientAndServer() throws Exception {
        if (sshd != null) {
            try {
                sshd.stop(true);
            } finally {
                sshd = null;
            }
        }

        if (client != null) {
            try {
                client.stop();
            } finally {
                client = null;
            }
        }
    }

    @Test
    public void testSimpleBanner() throws Exception {
        final String expectedWelcome = "Welcome to SSHD WelcomeBannerTest";
        PropertyResolverUtils.updateProperty(sshd, ServerAuthenticationManager.WELCOME_BANNER, expectedWelcome);
        testBanner(expectedWelcome);
    }

    @Test   // see SSHD-686
    public void testAutoGeneratedBanner() throws Exception {
        KeyPairProvider keys = sshd.getKeyPairProvider();
        PropertyResolverUtils.updateProperty(sshd, ServerAuthenticationManager.WELCOME_BANNER, ServerAuthenticationManager.AUTO_WELCOME_BANNER_VALUE);
        testBanner(KeyRandomArt.combine(' ', keys));
    }

    @Test
    public void testPathBanner() throws Exception {
        testFileContentBanner(Function.<Path>identity());
    }

    @Test
    public void testFileBanner() throws Exception {
        testFileContentBanner(path -> path.toFile());
    }

    @Test
    public void testURIBanner() throws Exception {
        testFileContentBanner(path -> path.toUri());
    }

    @Test
    public void testURIStringBanner() throws Exception {
        testFileContentBanner(path -> Objects.toString(path.toUri()));
    }

    @Test
    public void testFileNotExistsBanner() throws Exception {
        Path dir = getTempTargetRelativeFile(getClass().getSimpleName());
        Path file = assertHierarchyTargetFolderExists(dir).resolve(getCurrentTestName() + ".txt");
        Files.deleteIfExists(file);
        assertFalse("Banner file not deleted: " + file, Files.exists(file));
        PropertyResolverUtils.updateProperty(sshd, ServerAuthenticationManager.WELCOME_BANNER, file);
        testBanner(null);
    }

    @Test
    public void testEmptyFileBanner() throws Exception {
        Path dir = getTempTargetRelativeFile(getClass().getSimpleName());
        Path file = assertHierarchyTargetFolderExists(dir).resolve(getCurrentTestName() + ".txt");
        Files.deleteIfExists(file);
        Files.write(file, GenericUtils.EMPTY_BYTE_ARRAY);
        assertTrue("Empty file not created: " + file, Files.exists(file));
        PropertyResolverUtils.updateProperty(sshd, ServerAuthenticationManager.WELCOME_BANNER, file);
        testBanner(null);
    }

    private void testFileContentBanner(Function<? super Path, ?> configValueExtractor) throws Exception {
        Path dir = getTempTargetRelativeFile(getClass().getSimpleName());
        Path file = assertHierarchyTargetFolderExists(dir).resolve(getCurrentTestName() + ".txt");
        String expectedWelcome = getClass().getName() + "#" + getCurrentTestName();
        Files.deleteIfExists(file);
        Files.write(file, expectedWelcome.getBytes(StandardCharsets.UTF_8));
        Object configValue = configValueExtractor.apply(file);
        PropertyResolverUtils.updateProperty(sshd, ServerAuthenticationManager.WELCOME_BANNER, configValue);
        testBanner(expectedWelcome);
    }

    private void testBanner(String expectedWelcome) throws Exception {
        AtomicReference<String> welcomeHolder = new AtomicReference<>(null);
        AtomicReference<ClientSession> sessionHolder = new AtomicReference<>(null);
        client.setUserInteraction(new UserInteraction() {
            @Override
            public boolean isInteractionAllowed(ClientSession session) {
                return true;
            }

            @Override
            public void serverVersionInfo(ClientSession session, List<String> lines) {
                validateSession("serverVersionInfo", session);
            }

            @Override
            public void welcome(ClientSession session, String banner, String lang) {
                validateSession("welcome", session);
                assertNull("Multiple banner invocations", welcomeHolder.getAndSet(banner));
            }

            @Override
            public String[] interactive(ClientSession session, String name, String instruction, String lang, String[] prompt, boolean[] echo) {
                validateSession("interactive", session);
                return null;
            }

            @Override
            public String getUpdatedPassword(ClientSession clientSession, String prompt, String lang) {
                throw new UnsupportedOperationException("Unexpected call");
            }

            private void validateSession(String phase, ClientSession session) {
                ClientSession prev = sessionHolder.getAndSet(session);
                if (prev != null) {
                    assertSame("Mismatched " + phase + " client session", prev, session);
                }
            }
        });

        try (ClientSession session = client.connect(getCurrentTestName(), TEST_LOCALHOST, port).verify(7L, TimeUnit.SECONDS).getSession()) {
            session.addPasswordIdentity(getCurrentTestName());
            session.auth().verify(5L, TimeUnit.SECONDS);
            if (expectedWelcome != null) {
                assertSame("Mismatched sessions", session, sessionHolder.get());
            } else {
                assertNull("Unexpected session", sessionHolder.get());
            }
        }

        assertEquals("Mismatched banner", expectedWelcome, welcomeHolder.get());
    }
}
