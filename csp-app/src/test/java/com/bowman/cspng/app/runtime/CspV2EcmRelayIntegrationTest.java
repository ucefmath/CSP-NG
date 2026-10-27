package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CcCamConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration bout-en-bout du relais ECM via CSPv2 vers connecteur amont (OSCam/CCcam).
 * Valide l'absence de double en-tête DVB à travers toute la chaîne de bout en bout
 * et la bonne sérialisation en DcwStatus.OK via CcCamConnector et CwsConnectorManager.
 */
@ExtendWith(VertxExtension.class)
class CspV2EcmRelayIntegrationTest {

    private CspV2DomainMapper mapper;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        mapper = new CspV2DomainMapper(() -> 1000L);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (vertx != null) vertx.close().onComplete(ctx.succeedingThenComplete());
        else ctx.completeNow();
    }

    private static byte[] createValidCw() {
        byte[] cw = new byte[16];
        for (int i = 0; i < 16; i++) cw[i] = (byte) (i + 1);
        cw[3] = (byte) ((cw[0] + cw[1] + cw[2]) & 0xFF);
        cw[7] = (byte) ((cw[4] + cw[5] + cw[6]) & 0xFF);
        cw[11] = (byte) ((cw[8] + cw[9] + cw[10]) & 0xFF);
        cw[15] = (byte) ((cw[12] + cw[13] + cw[14]) & 0xFF);
        assertTrue(DcwValidator.checksumDcw(cw));
        return cw;
    }

    @Test
    @DisplayName("Relais bout-en-bout : Client CCcam -> CSPv2 Wire -> Central Handler -> CcCamConnector upstream (aucun double en-tête)")
    void endToEndEcmRelayNoDoubleHeader(VertxTestContext ctx) {
        // 1. Client DVB / CCcam envoie une section DVB complète standard (longueur payload = 19 octets -> en-tête 80 70 13)
        byte[] clientOriginalSection = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x13,
            (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
            (byte) 0x80, (byte) 0x08, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        };
        int commandTag = clientOriginalSection[0] & 0xFF;
        int srvId = 0x2000;
        int caId = 0x0500;

        // 2. Traitement session cliente (ex: CcCamSession.java:653) : amputation des 3 octets DVB
        byte[] clientAmputatedBody = Arrays.copyOfRange(clientOriginalSection, 3, clientOriginalSection.length);
        int clientDataHash = CspV2DomainMapper.ecmHash(clientAmputatedBody, 0, clientAmputatedBody.length);
        EcmKey clientKey = new EcmKey(commandTag, srvId, 0, caId, clientDataHash);
        EcmRequest clientDomainReq = new EcmRequest(clientKey, clientAmputatedBody, "default",
            "192.168.1.100", "user1", 1000L, null, 5000L);

        // 3. Émission sortante CSPv2 vers la centrale (toWire)
        CspV2Payload.EcmRequest wireReq = mapper.toWire(clientDomainReq);

        // Assertions wire : la section complète est portée sur le wire avec son dataHash
        assertArrayEquals(clientOriginalSection, wireReq.ecm(),
            "Le wire CSPv2 doit transporter la section complète reconstruite");
        assertEquals(clientDataHash, wireReq.dataHash(),
            "Le wire dataHash doit être calculé sur le corps amputé");

        // 4. Réception sur la centrale (toDomain via CspV2EcmHandler)
        AtomicReference<byte[]> capturedConnectorUpstreamSection = new AtomicReference<>();
        AtomicReference<byte[]> capturedConnectorFrame = new AtomicReference<>();
        byte[] cw = createValidCw();

        CspV2EcmHandler centralHandler = new CspV2EcmHandler(
            mapper,
            true,
            key -> null, // cache miss
            (domainReq, provider) -> {
                // Connecteur amont réel (CcCamConnector.java:801-806)
                // Reconstruit la section DVB avant buildEcmRequest
                byte[] upstreamSection = CcCardCodec.reconstructSection(
                    domainReq.key().commandTag(), domainReq.body());
                capturedConnectorUpstreamSection.set(upstreamSection);

                byte[] frame = CcCardCodec.buildEcmRequest(
                    domainReq.key().caId(), provider, 0x12345678,
                    domainReq.key().serviceId(), upstreamSection);
                capturedConnectorFrame.set(frame);

                // Simulation réponse OSCam amont
                return Future.succeededFuture(new CwReply(
                    domainReq.key(), domainReq.key().commandTag(), cw,
                    "upstream-oscam", "10.0.0.1", 1000L));
            }
        );

        // 5. Exécution de la requête par le handler central
        centralHandler.onEcmRequest("worker01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                CspV2Payload.DcwReply wireReply = ar.result();
                assertEquals(CspV2Payload.DcwStatus.OK, wireReply.status());
                assertArrayEquals(cw, wireReply.cw());
                assertEquals("upstream-oscam", wireReply.connectorName());

                // 6. Assertion critique : les octets envoyés au connecteur amont (OSCam)
                // sont STRICTEMENT IDENTIQUES à la section originale du client
                byte[] upstreamSection = capturedConnectorUpstreamSection.get();
                assertNotNull(upstreamSection, "La section amont ne doit pas être nulle");
                assertArrayEquals(clientOriginalSection, upstreamSection,
                    "La section envoyée à OSCam doit être IDENTIQUE à la section originale (AUCUN double en-tête)");

                // Vérifier également les octets dans la trame MSG_CW_ECM (offset 13..)
                CcCardCodec.EcmRequestHeader parsedHeader = CcCardCodec.parseEcmRequest(capturedConnectorFrame.get());
                assertArrayEquals(clientOriginalSection, parsedHeader.ecm(),
                    "Les octets ECM dans la trame CCcam finale doivent être la section d'origine");

                // 7. Retour côté worker/client (toDomain de la réponse)
                CwReply finalDomainReply = mapper.toDomain(wireReply, wireReq, "central");
                assertFalse(finalDomainReply.isEmpty());
                assertArrayEquals(cw, finalDomainReply.cw());
                assertEquals("upstream-oscam", finalDomainReply.connectorName());
                assertEquals("cspv2:central", finalDomainReply.originAddress());
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Relais CSPv2 avec CcCamConnector réel : la réponse porte la clé ECM réelle et toWire sérialise en DcwStatus.OK")
    void realCcCamConnectorRelaySerializesToDcwStatusOk(Vertx vertx, VertxTestContext ctx) throws Exception {
        this.vertx = vertx;
        int port = 18000 + new SecureRandom().nextInt(3000);
        String user = "testuser";
        String pass = "testpass";
        byte[] cw = createValidCw();

        CountDownLatch serverReady = new CountDownLatch(1);
        NetServer server = vertx.createNetServer();
        server.connectHandler(sock -> {
            SimpleCcCamServerMock ccServer = new SimpleCcCamServerMock(sock, user, pass);
            ccServer.onHandshakeComplete(() -> {
                byte[] srvData = SimpleCcCamServerMock.buildSrvData(ccServer.getNodeId());
                ccServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);
                byte[] newCard = SimpleCcCamServerMock.buildTestCard(0x12345678, 0x0500, 1, 2);
                ccServer.sendFrame(CcConstants.MSG_NEW_CARD, newCard);
            });
            ccServer.onEcmRequest((cardId, ecm) -> {
                return cw;
            });
            ccServer.startHandshake();
        });

        server.listen(port, "127.0.0.1", ar -> {
            if (ar.succeeded()) serverReady.countDown();
        });
        assertTrue(serverReady.await(5, TimeUnit.SECONDS));

        CacheVerticle cacheVerticle = new CacheVerticle();
        CwsConnectorManager connectorManager = new CwsConnectorManager(vertx, cacheVerticle);
        CcCamConnector connector = connectorManager.addCcCamConnector(
            "cccam-upstream", "127.0.0.1", port, user, pass, "default", "2.3.2", "4000");

        connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Attendre que la carte soit découverte
        for (int i = 0; i < 40; i++) {
            if (connector.isConnected() && connector.hasCards()) break;
            Thread.sleep(200);
        }
        assertTrue(connector.isConnected());
        assertTrue(connector.hasCards());

        // Wire ECM request
        byte[] clientSection = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x13,
            (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
            (byte) 0x80, (byte) 0x08, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        };
        byte[] body = Arrays.copyOfRange(clientSection, 3, clientSection.length);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(
            0x80, 0x2000, 0x0001, 0x0500, hash, "default", clientSection, 5000);

        CspV2EcmHandler handler = new CspV2EcmHandler(cacheVerticle, connectorManager, mapper, true);

        handler.onEcmRequest("worker01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                CspV2Payload.DcwReply wireReply = ar.result();
                // CRUCIAL : Le statut doit être OK (non plus NOT_FOUND causé par une clé factice)
                assertEquals(CspV2Payload.DcwStatus.OK, wireReply.status(),
                    "La réponse issue de CcCamConnector doit être sérialisée en DcwStatus.OK");
                assertEquals(0x80, wireReply.commandTag());
                assertEquals(0x2000, wireReply.serviceId());
                assertEquals(0x0001, wireReply.networkId());
                assertEquals(0x0500, wireReply.caId());
                assertEquals(hash, wireReply.dataHash());
                assertArrayEquals(cw, wireReply.cw());
                assertEquals("cccam-upstream", wireReply.connectorName());
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Défense en profondeur CwsConnectorManager : un connecteur renvoyant une clé factice est corrigé et toWire produit DcwStatus.OK")
    void defenseInDepthReassignsDummyKeyAndSerializesToDcwStatusOk(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        CacheVerticle cacheVerticle = new CacheVerticle();
        CwsConnectorManager connectorManager = new CwsConnectorManager(vertx, cacheVerticle);
        byte[] cw = createValidCw();

        // Connecteur de test retournant délibérément une clé factice (0x80, 0, 0, 0, 0)
        AbstractConnector dummyKeyConnector = new AbstractConnector(vertx, cacheVerticle,
            "dummy-key-conn", "127.0.0.1", 9999, "default") {
            @Override public boolean isConnected() { return true; }
            @Override public Future<CwReply> sendEcm(EcmRequest request) {
                // Clé factice historique
                CwReply replyWithDummyKey = new CwReply(
                    new EcmKey(0x80, 0, 0, 0, 0),
                    0x80, cw, "dummy-key-conn", "127.0.0.1", System.currentTimeMillis());
                return Future.succeededFuture(replyWithDummyKey);
            }
            @Override public Future<Void> start() { return Future.succeededFuture(); }
            @Override public void stop() {}
            @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
        };
        connectorManager.addTestConnector(dummyKeyConnector);

        byte[] clientSection = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x05,
            (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05
        };
        byte[] body = Arrays.copyOfRange(clientSection, 3, clientSection.length);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(
            0x80, 0x1234, 0x0002, 0x0100, hash, "default", clientSection, 5000);

        CspV2EcmHandler handler = new CspV2EcmHandler(cacheVerticle, connectorManager, mapper, true);

        handler.onEcmRequest("worker01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                CspV2Payload.DcwReply wireReply = ar.result();
                assertEquals(CspV2Payload.DcwStatus.OK, wireReply.status(),
                    "Grâce à la défense en profondeur de CwsConnectorManager, le statut doit être OK");
                assertEquals(0x80, wireReply.commandTag());
                assertEquals(0x1234, wireReply.serviceId());
                assertEquals(0x0002, wireReply.networkId());
                assertEquals(0x0100, wireReply.caId());
                assertEquals(hash, wireReply.dataHash());
                assertArrayEquals(cw, wireReply.cw());
            });
            ctx.completeNow();
        });
    }

    // --- Mock CCcam minimal pour le test de relais ---
    static class SimpleCcCamServerMock {
        private final NetSocket socket;
        private final String expectedUser;
        private final String expectedPass;
        private final byte[] ourNodeId = new byte[8];
        private final byte[] clientNodeId = new byte[8];
        private final CcCrypt encryptBlock = new CcCrypt();
        private final CcCrypt decryptBlock = new CcCrypt();
        private Buffer accumulator = Buffer.buffer();
        private boolean headerPhase = true;
        private int payloadLen;
        private int currentCmd;
        private Runnable onHandshakeComplete;
        private java.util.function.BiFunction<Integer, byte[], byte[]> ecmHandler;

        SimpleCcCamServerMock(NetSocket socket, String user, String pass) {
            this.socket = socket;
            this.expectedUser = user;
            this.expectedPass = pass;
            new SecureRandom().nextBytes(ourNodeId);
        }

        byte[] getNodeId() { return ourNodeId; }
        byte[] getClientNodeId() { return clientNodeId; }
        void onHandshakeComplete(Runnable r) { this.onHandshakeComplete = r; }
        void onEcmRequest(java.util.function.BiFunction<Integer, byte[], byte[]> h) { this.ecmHandler = h; }

        void startHandshake() {
            byte[] seed = new byte[16];
            new SecureRandom().nextBytes(seed);
            socket.write(Buffer.buffer(seed));

            byte[] seedXor = seed.clone();
            CcCrypt.xor(seedXor);
            byte[] hash = sha1(seedXor);
            byte[] seedForEncrypt = seedXor.clone();

            encryptBlock.initCrypt(hash, 20);
            encryptBlock.crypt(seedXor, 16, CcConstants.DECRYPT);
            decryptBlock.initCrypt(seedXor, 16);
            decryptBlock.crypt(hash, 20, CcConstants.DECRYPT);

            socket.handler(new HandshakeHandler(seedForEncrypt));
        }

        void sendFrame(int cmd, byte[] payload) {
            byte[] frame = CcFrameCodec.encode(cmd, payload);
            encryptBlock.crypt(frame, frame.length, CcConstants.ENCRYPT);
            socket.write(Buffer.buffer(frame));
        }

        private class HandshakeHandler implements Handler<Buffer> {
            private Buffer buf = Buffer.buffer();
            private int step = 0;
            HandshakeHandler(byte[] seedXor) {}
            @Override public void handle(Buffer data) {
                buf.appendBuffer(data);
                while (processStep()) {}
            }
            private boolean processStep() {
                switch (step) {
                    case 0:
                        if (buf.length() < 20) return false;
                        byte[] hash = buf.getBytes(0, 20);
                        decryptBlock.crypt(hash, 20, CcConstants.DECRYPT);
                        buf = Buffer.buffer(buf.getBytes(20, buf.length()));
                        step = 1;
                        return true;
                    case 1:
                        if (buf.length() < 20) return false;
                        byte[] user = buf.getBytes(0, 20);
                        decryptBlock.crypt(user, 20, CcConstants.DECRYPT);
                        buf = Buffer.buffer(buf.getBytes(20, buf.length()));
                        byte[] pwdBytes = expectedPass.getBytes();
                        decryptBlock.crypt(pwdBytes, pwdBytes.length, CcConstants.ENCRYPT);
                        step = 2;
                        return true;
                    case 2:
                        if (buf.length() < 6) return false;
                        byte[] magic = buf.getBytes(0, 6);
                        decryptBlock.crypt(magic, 6, CcConstants.DECRYPT);
                        boolean ok = magic[0] == 'C' && magic[1] == 'C' && magic[2] == 'c'
                            && magic[3] == 'a' && magic[4] == 'm';
                        if (!ok) { socket.close(); return false; }
                        byte[] resp = new byte[20];
                        resp[0] = 'C'; resp[1] = 'C'; resp[2] = 'c'; resp[3] = 'a'; resp[4] = 'm';
                        encryptBlock.crypt(resp, 20, CcConstants.ENCRYPT);
                        socket.write(Buffer.buffer(resp));
                        socket.handler(new NormalHandler());
                        if (onHandshakeComplete != null) onHandshakeComplete.run();
                        step = 99;
                        return false;
                    default: return false;
                }
            }
        }

        private class NormalHandler implements Handler<Buffer> {
            @Override public void handle(Buffer data) {
                accumulator.appendBuffer(data);
                while (true) {
                    if (headerPhase) {
                        if (accumulator.length() < 4) return;
                        byte[] header = accumulator.getBytes(0, 4);
                        decryptBlock.crypt(header, 4, CcConstants.DECRYPT);
                        int[] decoded = CcFrameCodec.decodeHeader(header);
                        currentCmd = decoded[1];
                        payloadLen = decoded[2];
                        if (payloadLen > 1024 || payloadLen < 0) {
                            accumulator = Buffer.buffer(accumulator.getBytes(4, accumulator.length()));
                            continue;
                        }
                        if (payloadLen == 0) {
                            handleMsg(currentCmd, new byte[0]);
                            accumulator = Buffer.buffer(accumulator.getBytes(4, accumulator.length()));
                        } else {
                            headerPhase = false;
                        }
                    } else {
                        if (accumulator.length() < 4 + payloadLen) return;
                        byte[] payload = accumulator.getBytes(4, 4 + payloadLen);
                        decryptBlock.crypt(payload, payloadLen, CcConstants.DECRYPT);
                        handleMsg(currentCmd, payload);
                        accumulator = Buffer.buffer(accumulator.getBytes(4 + payloadLen, accumulator.length()));
                        headerPhase = true;
                    }
                }
            }

            private void handleMsg(int cmd, byte[] payload) {
                if (cmd == CcConstants.MSG_CLI_DATA && payload.length >= 28) {
                    System.arraycopy(payload, 20, clientNodeId, 0, 8);
                } else if (cmd == CcConstants.MSG_CW_ECM && ecmHandler != null && payload.length >= 13) {
                    try {
                        CcCardCodec.EcmRequestHeader hdr = CcCardCodec.parseEcmRequest(payload);
                        byte[] cw = ecmHandler.apply(hdr.cardId(), hdr.ecm());
                        if (cw != null && cw.length == 16) {
                            byte[] scrambled = cw.clone();
                            CcCwCrypt.cwCrypt(scrambled, clientNodeId, hdr.cardId());
                            sendFrame(CcConstants.MSG_CW_ECM, scrambled);
                            byte[] sync = Arrays.copyOf(scrambled, 16);
                            encryptBlock.crypt(sync, 16, CcConstants.ENCRYPT);
                        } else {
                            sendFrame(CcConstants.MSG_CW_NOK2, new byte[0]);
                        }
                    } catch (Exception e) {
                        sendFrame(CcConstants.MSG_CW_NOK2, new byte[0]);
                    }
                }
            }
        }

        static byte[] buildSrvData(byte[] nodeId) {
            byte[] data = new byte[72];
            System.arraycopy(nodeId, 0, data, 0, 8);
            byte[] ver = "2.3.2".getBytes();
            System.arraycopy(ver, 0, data, 8, Math.min(ver.length, 6));
            byte[] bld = "4000".getBytes();
            System.arraycopy(bld, 0, data, 40, Math.min(bld.length, 4));
            return data;
        }

        static byte[] buildTestCard(int cardId, int caid, int hop, int reshare) {
            byte[] buf = new byte[21];
            buf[0] = (byte) ((cardId >> 24) & 0xFF);
            buf[1] = (byte) ((cardId >> 16) & 0xFF);
            buf[2] = (byte) ((cardId >> 8) & 0xFF);
            buf[3] = (byte) (cardId & 0xFF);
            buf[8] = (byte) ((caid >> 8) & 0xFF);
            buf[9] = (byte) (caid & 0xFF);
            buf[10] = (byte) hop;
            buf[11] = (byte) reshare;
            buf[20] = 0;
            return buf;
        }

        static byte[] sha1(byte[] data) {
            try {
                return MessageDigest.getInstance("SHA-1").digest(data);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
