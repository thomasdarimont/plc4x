/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */
package org.apache.plc4x.java.ads.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.plc4x.java.ads.api.generic.types.AmsNetId;
import org.apache.plc4x.java.ads.api.generic.types.AmsPort;
import org.apache.plc4x.java.ads.api.serial.AmsSerialAcknowledgeFrame;
import org.apache.plc4x.java.ads.api.serial.types.*;
import org.apache.plc4x.java.ads.model.AdsAddress;
import org.apache.plc4x.java.ads.model.SymbolicAdsAddress;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.base.connection.AbstractPlcConnection;
import org.apache.plc4x.java.base.connection.SerialChannelFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class AdsSerialPlcConnectionTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdsSerialPlcConnectionTest.class);

    private AdsSerialPlcConnection SUT;

    @Before
    public void setUp() {
        SUT = AdsSerialPlcConnection.of("/dev/tty0", AmsNetId.of("0.0.0.0.0.0"), AmsPort.of(13));
    }

    @After
    public void tearDown() {
        SUT = null;
    }

    @Test
    public void initialState() {
        assertEquals(SUT.getTargetAmsNetId().toString(), "0.0.0.0.0.0");
        assertEquals(SUT.getTargetAmsPort().toString(), "13");
    }

    @Test
    public void emptyParseAddress() {
        try {
            SUT.parseAddress("");
        } catch (IllegalArgumentException exception) {
            assertTrue("Unexpected exception", exception.getMessage().startsWith("address  doesn't match "));
        }
    }

    @Test
    public void parseAddress() {
        try {
            AdsAddress address = (AdsAddress) SUT.parseAddress("0/1");
            assertEquals(address.getIndexGroup(), 0);
            assertEquals(address.getIndexOffset(), 1);
        } catch (IllegalArgumentException exception) {
            fail("valid data block address");
        }
    }

    @Test
    public void parseSymbolicAddress() {
        try {
            SymbolicAdsAddress address = (SymbolicAdsAddress) SUT.parseAddress("Main.variable");
            assertEquals(address.getSymbolicAddress(), "Main.variable");
        } catch (IllegalArgumentException exception) {
            fail("valid data block address");
        }
    }

    @Test
    public void testRead() throws Exception {
        prepareSerialSimulator();
        CompletableFuture<PlcReadResponse> read = SUT.read(new PlcReadRequest(String.class, SUT.parseAddress("0/0")));
        PlcReadResponse plcReadResponse = read.get(30, TimeUnit.SECONDS);
        assertNotNull(plcReadResponse);
    }

    private void prepareSerialSimulator() throws Exception {
        Field channelFactoryField = FieldUtils.getField(AbstractPlcConnection.class, "channelFactory", true);
        SerialChannelFactory serialChannelFactory = (SerialChannelFactory) channelFactoryField.get(SUT);
        SerialChannelFactory serialChannelFactorySpied = spy(serialChannelFactory);
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(SUT.getChannelHandler(null));
        doReturn(embeddedChannel).when(serialChannelFactorySpied).createChannel(any());
        channelFactoryField.set(SUT, serialChannelFactorySpied);
        SUT.connect();
        new SerialSimulator(embeddedChannel).start();
    }

    private class SerialSimulator extends Thread {

        private EmbeddedChannel embeddedChannel;

        private SimulatorState state = SimulatorState.RECEIVE_REQUEST;

        public SerialSimulator(EmbeddedChannel embeddedChannel) {
            super("Serial Simulator");
            this.embeddedChannel = embeddedChannel;
        }

        @Override
        public void run() {
            while (true) {
                switch (state) {
                    // Receiving state
                    case RECEIVE_REQUEST: {
                        LOGGER.info("Waiting for normal message");
                        ByteBuf outputBuffer;
                        while ((outputBuffer = embeddedChannel.readOutbound()) == null) {
                            if (!trySleep()) {
                                return;
                            }
                        }
                        int headerBytes = MagicCookie.NUM_BYTES + TransmitterAddress.NUM_BYTES + ReceiverAddress.NUM_BYTES + FragmentNumber.NUM_BYTES;
                        LOGGER.info("Skipping " + headerBytes + " bytes");
                        outputBuffer.skipBytes(headerBytes);
                        short dataLength = outputBuffer.readUnsignedByte();
                        LOGGER.info("Expect at least " + dataLength + "bytes");
                        while (outputBuffer.readableBytes() < dataLength) {
                            if (!trySleep()) {
                                return;
                            }
                        }
                        byte[] bytes = new byte[dataLength];
                        LOGGER.info("Read " + dataLength + "bytes. Having " + outputBuffer.readableBytes() + "bytes");
                        outputBuffer.readBytes(bytes);
                        outputBuffer.skipBytes(CRC.NUM_BYTES);
                        LOGGER.info("Wrote Inbound");
                        state = SimulatorState.ACK_MESSAGE;
                        if (!trySleep()) {
                            return;
                        }
                    }
                    break;
                    case ACK_MESSAGE: {
                        ByteBuf byteBuf = AmsSerialAcknowledgeFrame.of(
                            TransmitterAddress.of((byte) 0x0),
                            ReceiverAddress.of((byte) 0x0),
                            FragmentNumber.of((byte) 0)
                        ).getByteBuf();
                        embeddedChannel.writeOneInbound(byteBuf);
                        LOGGER.info("Acked Message");
                        state = SimulatorState.SEND_RESPONSE;
                    }
                    case SEND_RESPONSE: {
                        LOGGER.info("Sending data message");
                        embeddedChannel.writeOneInbound(Unpooled.wrappedBuffer(new byte[]{
                            /*Magic Cookie     */    0x01, (byte) 0xA5,
                            /*Sender           */    0x00,
                            /*Empfaenger       */    0x00,
                            /*Fragmentnummer   */    (byte) 0x00,
                            /*Anzahl Daten     */    0x2A,
                            /*NetID Empfaenger */    (byte) 0xC0, (byte) 0xA8, 0x64, (byte) 0x9C, 0x01, 0x01,
                            /*Portnummer       */    0x01, (byte) 0x80,
                            /*NetID Sender     */    (byte) 0xC0, (byte) 0xA8, 0x64, (byte) 0xAE, 0x01, 0x01,
                            /*Portnummer       */    0x21, 0x03,
                            /*Response Lesen   */    0x02, 0x00,
                            /*Status           */    0x05, 0x00,
                            /*Anzahl Daten     */    0x0A, 0x00, 0x00, 0x00,
                            /*Fehlercode       */    0x00, 0x00, 0x00, 0x00,
                            /*InvokeID         */    0x02, 0x00, 0x00, 0x00,
                            /*Ergebnis         */    0x00, 0x00, 0x00, 0x00,
                            /*Anzahl Daten     */    0x02, 0x00, 0x00, 0x00,
                            /*Daten            */    (byte) 0xAF, 0x27,
                            /*Checksumme       */    0x60, (byte) 0x0c,
                        }));
                        LOGGER.info("Wrote Inbound");
                        state = SimulatorState.WAIT_FOR_ACK;
                        if (!trySleep()) {
                            return;
                        }
                    }
                    break;
                    case WAIT_FOR_ACK: {
                        LOGGER.info("Waiting for ack message");
                        ByteBuf outputBuffer;
                        while ((outputBuffer = embeddedChannel.readOutbound()) == null) {
                            if (!trySleep()) {
                                return;
                            }
                        }
                        int headerBytes = MagicCookie.NUM_BYTES + TransmitterAddress.NUM_BYTES + ReceiverAddress.NUM_BYTES + FragmentNumber.NUM_BYTES;
                        LOGGER.info("Skipping " + headerBytes + " bytes");
                        outputBuffer.skipBytes(headerBytes);
                        short dataLength = outputBuffer.readUnsignedByte();
                        LOGGER.info("Expect " + dataLength + "bytes");
                        state = SimulatorState.DONE;
                        if (!trySleep()) {
                            return;
                        }
                    }
                    case DONE: {
                        LOGGER.info("Plc is Done. Goodbye");
                        return;
                    }
                    default:
                        throw new IllegalStateException("Illegal state number" + state);
                }
            }

        }

        private boolean trySleep() {
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }
    }

    private enum SimulatorState {
        RECEIVE_REQUEST,
        ACK_MESSAGE,
        SEND_RESPONSE,
        WAIT_FOR_ACK,
        DONE
    }
}