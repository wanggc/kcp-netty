package io.jpower.kcp.example.rtt;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class TcpRttClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TcpRttClientHandler.class);

    private final ByteBuf data;

    private int[] rtts;

    private volatile int count;

    private ScheduledExecutorService scheduleSrv;

    private ScheduledFuture<?> future = null;

    private final long startTime;

    /**
     * Creates a client-side handler.
     */
    public TcpRttClientHandler(int count) {
        data = Unpooled.buffer(TcpRttClient.SIZE);
        for (int i = 0; i < data.capacity(); i++) {
            data.writeByte((byte) i);
        }

        rtts = new int[count];
        for (int i = 0; i < rtts.length; i++) {
            rtts[i] = -1;
        }
        startTime = System.currentTimeMillis();
        scheduleSrv = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {

        future = scheduleSrv.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                ctx.write(rttMsg(++count));
                if (count >= rtts.length) {
                    // finish
                    future.cancel(true);
                    ctx.write(rttMsg(-1));

                }
                ctx.flush();
            }
        }, TcpRttClient.RTT_INTERVAL, TcpRttClient.RTT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * count+timestamp+dataLen+data
     *
     * @param count
     * @return
     */
    public ByteBuf rttMsg(int count) {
        ByteBuf buf = Unpooled.buffer(10);
        buf.writeShort(count);
        buf.writeInt((int) (System.currentTimeMillis() - startTime));
        int dataLen = data.readableBytes();
        buf.writeShort(dataLen);
        buf.writeBytes(data, data.readerIndex(), dataLen);

        return buf;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        scheduleSrv.shutdown();
        scheduleSrv.awaitTermination(3, TimeUnit.SECONDS);

        int sum = 0;
        for (int rtt : rtts) {
            sum += rtt;
        }
        log.info("average: {}", (sum / rtts.length));
        Arrays.sort(rtts);
        log.info(".99: {}", rtts[(int) (rtts.length * 0.99)]);
        log.info(".95: {}", rtts[(int) (rtts.length * 0.95)]);
        log.info(".75: {}", rtts[(int) (rtts.length * 0.75)]);
        log.info(".50: {}", rtts[(int) (rtts.length * 0.50)]);
        double stddev = new StandardDeviation().evaluate(Arrays.stream(rtts).mapToDouble(i -> i).toArray());
        log.info("stddev: {}", stddev);    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        int curCount = buf.readShort();

        if (curCount == -1) {
            scheduleSrv.schedule(new Runnable() {
                @Override
                public void run() {
                    ctx.close();
                }
            }, 3, TimeUnit.SECONDS);
        } else {
            int idx = curCount - 1;
            long time = buf.readInt();
            if (rtts[idx] != -1) {
                log.error("???");
            }
            //log.info("rcv count {} {}", curCount, System.currentTimeMillis());
            rtts[idx] = (int) (System.currentTimeMillis() - startTime - time);

            log.info("rtt {}: {}", curCount, rtts[idx]);
        }

        buf.release();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        log.error("exceptionCaught", cause);
        ctx.close();
    }

}
