/* Copyright (C)2013 Pantheon Technologies, s.r.o. All rights reserved. */
package org.openflow.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openflow.core.TCPHandler.COMPONENT_NAMES;

/**
 * Class for decoding incoming messages into message frames.
 *
 * @author michal.polkorab
 */
public class OFFrameDecoder extends ByteToMessageDecoder {

    public static final byte LENGTH_OF_HEADER = 8;
    private static final byte LENGTH_INDEX_IN_HEADER = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(OFFrameDecoder.class);

    /**
     * Constructor of class.
     */
    public OFFrameDecoder() {
        LOGGER.info("Creating OFFrameDecoder");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.warn("Unexpected exception from downstream.", cause);
        ctx.close();
    }

    @Override
    protected void decode(ChannelHandlerContext chc, ByteBuf bb, List<Object> list) throws Exception {
        if (bb.readableBytes() < LENGTH_OF_HEADER) {
            return;
        }

        int length = bb.getUnsignedShort(LENGTH_INDEX_IN_HEADER);
        if (bb.readableBytes() < length) {
            return;
        }

        LOGGER.info("OF Protocol message received");

        enableOFVersionDetector(chc);

        List<String> componentList = chc.pipeline().names();
        LOGGER.debug(componentList.toString());

        ByteBuf messageBuffer = bb.slice(bb.readerIndex(), length);
        list.add(messageBuffer);
        messageBuffer.retain();
        bb.skipBytes(length);
    }

    private void enableOFVersionDetector(ChannelHandlerContext ctx) {
        if (ctx.pipeline().get(COMPONENT_NAMES.OF_VERSION_DETECTOR.name()) == null) {
            LOGGER.info("Adding OFVD");
            ctx.pipeline().addLast(COMPONENT_NAMES.OF_VERSION_DETECTOR.name(), new OFVersionDetector());
        } else {
            LOGGER.debug("OFVD already in pipeline");
        }
    }
}