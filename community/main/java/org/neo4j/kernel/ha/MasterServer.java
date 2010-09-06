package org.neo4j.kernel.ha;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.neo4j.kernel.impl.util.StringLogger;

/**
 * Sits on the master side, receiving serialized requests from slaves (via
 * {@link MasterClient}). Delegates actual work to {@link MasterImpl}.
 */
public class MasterServer extends CommunicationProtocol implements ChannelPipelineFactory
{
    private final static int DEAD_CONNECTIONS_CHECK_INTERVAL = 10;
    private final static int MAX_NUMBER_OF_CONCURRENT_TRANSACTIONS = 200;

    private final ChannelFactory channelFactory;
    private final ServerBootstrap bootstrap;
    private final Master realMaster;
    private final ChannelGroup channelGroup;
    private final ScheduledExecutorService deadConnectionsPoller;
    private final Map<Channel, SlaveContext> connectedSlaveChannels =
            new HashMap<Channel, SlaveContext>();

    private final StringLogger msgLog;
    
    public MasterServer( Master realMaster, final int port, String storeDir )
    {
        this.realMaster = realMaster;
        this.msgLog = StringLogger.getLogger( storeDir + "/messages.log" );
        ExecutorService executor = Executors.newCachedThreadPool();
        channelFactory = new NioServerSocketChannelFactory(
                executor, executor, MAX_NUMBER_OF_CONCURRENT_TRANSACTIONS );
        bootstrap = new ServerBootstrap( channelFactory );
        bootstrap.setPipelineFactory( this );
        channelGroup = new DefaultChannelGroup();
        executor.execute( new Runnable()
        {
            public void run()
            {
                Channel channel = bootstrap.bind( new InetSocketAddress( port ) );
                // Add the "server" channel
                channelGroup.add( channel );
                msgLog.logMessage( "Master server bound to " + port );
            }
        } );
        deadConnectionsPoller = new ScheduledThreadPoolExecutor( 1 );
        deadConnectionsPoller.scheduleWithFixedDelay( new Runnable()
        {
            public void run()
            {
//                checkForDeadChannels();
            }
        }, DEAD_CONNECTIONS_CHECK_INTERVAL, DEAD_CONNECTIONS_CHECK_INTERVAL, TimeUnit.SECONDS );
    }

    public ChannelPipeline getPipeline() throws Exception
    {
        ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast( "chunkedWriter", new ChunkedWriteHandler() );
        pipeline.addLast( "frameDecoder", new LengthFieldBasedFrameDecoder( MAX_FRAME_LENGTH,
                0, 4, 0, 4 ) );
        // pipeline.addLast( "frameEncoder", new LengthFieldPrepender( 4 ) );
        pipeline.addLast( "serverHandler", new ServerHandler() );
        return pipeline;
    }

    private class ServerHandler extends SimpleChannelHandler
    {
        @Override
        public void messageReceived( ChannelHandlerContext ctx, MessageEvent event )
                throws Exception
        {
            try
            {
                ChannelBuffer message = (ChannelBuffer) event.getMessage();
                RequestType type = RequestType.values()[message.readByte()];
                Channel channel = event.getChannel();
                SlaveContext context = null;
                if ( type.includesSlaveContext() )
                {
                    context = CommunicationProtocol.readSlaveContext( message );
                    mapSlave( channel, context );
                }
                channel.write( new ChunkedResponse( type.caller.callMaster( realMaster, context,
                        message ), type.serializer, type.includesSlaveContext() ) );
            }
            catch ( Exception e )
            {
                e.printStackTrace();
                throw e;
            }
        }

        @Override
        public void exceptionCaught( ChannelHandlerContext ctx, ExceptionEvent e ) throws Exception
        {
            e.getCause().printStackTrace();
        }
    }

    protected void mapSlave( Channel channel, SlaveContext slave )
    {
        channelGroup.add( channel );
        synchronized ( connectedSlaveChannels )
        {
            connectedSlaveChannels.put( channel, slave );
        }
    }
    
    protected void unmapSlave( Channel channel, SlaveContext slave )
    {
        synchronized ( connectedSlaveChannels )
        {
            connectedSlaveChannels.remove( channel );
        }
    }

    public void shutdown()
    {
        // Close all open connections
        deadConnectionsPoller.shutdown();
        msgLog.logMessage( "Master server shutdown, closing all channels" );
        channelGroup.close().awaitUninterruptibly();
        
        // TODO This should work, but blocks with busy wait sometimes
//        channelFactory.releaseExternalResources();
    }

//    private void checkForDeadChannels()
//    {
//        synchronized ( connectedSlaveChannels )
//        {
//            Collection<Channel> channelsToRemove = new ArrayList<Channel>();
//            for ( Map.Entry<Channel, SlaveContext> entry : connectedSlaveChannels.entrySet() )
//            {
//                if ( channelIsClosed( entry.getKey() ) )
//                {
//                    realMaster.finishTransaction( entry.getValue() );
//                }
//                channelsToRemove.add( entry.getKey() );
//            }
//            for ( Channel channel : channelsToRemove )
//            {
//                connectedSlaveChannels.remove( channel );
//            }
//        }
//    }
    
    private boolean channelIsClosed( Channel channel )
    {
        return channel.isConnected() && channel.isOpen();
    }

    // =====================================================================
    // Just some methods which aren't really used when running a HA cluster,
    // but exposed so that other tools can reach that information.
    // =====================================================================

    public Map<Integer, Collection<SlaveContext>> getSlaveInformation()
    {
        // Which slaves are connected a.t.m?
        Set<Integer> machineIds = new HashSet<Integer>();
        synchronized ( connectedSlaveChannels )
        {
            for ( SlaveContext context : this.connectedSlaveChannels.values() )
            {
                machineIds.add( context.machineId() );
            }
        }
        
        // Insert missing slaves into the map so that all connected slave
        // are in the returned map
        Map<Integer, Collection<SlaveContext>> ongoingTransactions =
                ((MasterImpl) realMaster).getOngoingTransactions();
        for ( Integer machineId : machineIds )
        {
            if ( !ongoingTransactions.containsKey( machineId ) )
            {
                ongoingTransactions.put( machineId, Collections.<SlaveContext>emptyList() );
            }
        }
        return new TreeMap<Integer, Collection<SlaveContext>>( ongoingTransactions );
    }
}
