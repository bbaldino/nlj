/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package org.jitsi.nlj

import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.nlj.rtcp.NackHandler
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpRrGenerator
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.*
import org.jitsi.nlj.transform.node.incoming.*
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtcpPacket
import org.jitsi.rtp.SrtpPacket
import org.jitsi.rtp.SrtpProtocolPacket
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpIterator
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.RtpProtocol
import org.jitsi.util.Logger
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RtpReceiverImpl @JvmOverloads constructor(
    val id: String,
    /**
     * A function to be used when these receiver wants to send RTCP packets to the
     * participant it's receiving data from (NACK packets, for example)
     */
    private val rtcpSender: (RtcpPacket) -> Unit = {},
    transportCcEngine: TransportCCEngine? = null,
    private val rtcpEventNotifier: RtcpEventNotifier,
    /**
     * The executor this class will use for its primary work (i.e. critical path
     * packet processing).  This [RtpReceiver] will execute a blocking queue read
     * on this executor.
     */
    private val executor: ExecutorService,
    /**
     * A [ScheduledExecutorService] which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    private val backgroundExecutor: ScheduledExecutorService
) : RtpReceiver() {
    private var running: Boolean = true
    private val inputTreeRoot: Node
    private val incomingPacketQueue = LinkedBlockingQueue<PacketInfo>()
    private val srtpDecryptWrapper = SrtpTransformerDecryptNode()
    private val srtcpDecryptWrapper = SrtcpTransformerDecryptNode()
    private val tccGenerator = TccGeneratorNode(rtcpSender)
    private val payloadTypeFilter = PayloadTypeFilterNode()
    private val audioLevelReader = AudioLevelReader()
    private val statTracker = IncomingStatisticsTracker()
    private val rtcpRrGenerator = RtcpRrGenerator(backgroundExecutor, rtcpSender, statTracker)
    private val rtcpTermination = RtcpTermination(rtcpEventNotifier, transportCcEngine)

    companion object {
        private val logger: Logger = Logger.getLogger(this::class.java)
        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP receiver incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP receiver incoming queue"
    }

    /**
     * [rtpPacketHandler] will be invoked with RTP packets that have made
     * it through the entire receive pipeline.  Some external entity should
     * assign it to a [PacketHandler] with appropriate logic.
     */
    override var rtpPacketHandler: PacketHandler? = null
    /**
     * [rtcpPacketHandler] will be invoked with RTCP packets that were not
     * terminated and should be further routed (e.g. RTCPFB packets).
     * Some external entity should assign it to a [PacketHandler] with appropriate logic.
     */
    override var rtcpPacketHandler: PacketHandler? = null

    /**
     * The [rtpPacketHandler] can be re-assigned at any time, but it should maintain
     * its place in the receive pipeline.  To support both keeping it in the same
     * place and allowing it to be re-assigned, we wrap it with this.
     */
    private val rtpPacketHandlerWrapper = object : Node("RTP packet handler wrapper") {
        override fun doProcessPackets(p: List<PacketInfo>) {
            rtpPacketHandler?.processPackets(p)
        }
    }

    /**
     * The [rtcpPacketHandler] can be re-assigned at any time, but it should maintain
     * its place in the receive pipeline.  To support both keeping it in the same
     * place and allowing it to be re-assigned, we wrap it with this.
     */
    private val rtcpPacketHandlerWrapper = object : Node("RTCP packet handler wrapper") {
        override fun doProcessPackets(p: List<PacketInfo>) {
            rtcpPacketHandler?.processPackets(p)
        }
    }

    // Stat tracking values
    var firstPacketWrittenTime: Long = 0
    var lastPacketWrittenTime: Long = 0
    var bytesReceived: Long = 0
    var packetsReceived: Long = 0

    var bytesProcessed: Long = 0
    var packetsProcessed: Long = 0
    var firstPacketProcessedTime: Long = 0
    var lastPacketProcessedTime: Long = 0

    private var firstQueueReadTime: Long = -1
    private var lastQueueReadTime: Long = -1
    private var numQueueReads: Long = 0
    private var numTimesQueueEmpty: Long = 0

    init {
        logger.cinfo { "Receiver ${this.hashCode()} using executor ${executor.hashCode()}" }
        rtcpEventNotifier.addRtcpEventListener(rtcpRrGenerator)

        inputTreeRoot = pipeline {
            node(PacketParser("SRTP protocol parser") { SrtpProtocolPacket(it.getBuffer()) })
            demux("SRTP/SRTCP") {
                packetPath {
                    name = "SRTP path"
                    predicate = { pkt -> RtpProtocol.isRtp(pkt.getBuffer()) }
                    path = pipeline {
                        node(PacketParser("SRTP Parser") { SrtpPacket(it.getBuffer()) })
                        node(payloadTypeFilter)
                        node(tccGenerator)
                        node(srtpDecryptWrapper)
                        node(MediaTypeParser())
                        node(statTracker)
                        demux("Media type") {
                            packetPath {
                                name = "Audio path"
                                predicate = { pkt -> pkt is AudioRtpPacket }
                                path = pipeline {
                                    node(audioLevelReader)
                                    node(rtpPacketHandlerWrapper)
                                }
                            }
                            packetPath {
                                name = "Video path"
                                predicate = { pkt -> pkt is VideoRtpPacket }
                                path = pipeline {
                                    node(RtxHandler())
                                    node(PaddingTermination())
                                    node(VideoParser())
                                    node(RetransmissionRequester(rtcpSender))
                                    node(rtpPacketHandlerWrapper)
                                }
                            }
                        }
                    }
                }
                packetPath {
                    name = "SRTCP path"
                    predicate = { pkt -> RtpProtocol.isRtcp(pkt.getBuffer()) }
                    path = pipeline {
                        val prevRtcpPackets = mutableListOf<Packet>()
                        node(PacketParser("SRTCP parser") { SrtcpPacket(it.getBuffer())} )
                        node(srtcpDecryptWrapper)
                        simpleNode("RTCP pre-parse cache ${hashCode()}") { pkts ->
                            prevRtcpPackets.clear()
                            pkts.forEach {
                                prevRtcpPackets.add(it.packet.clone())
                            }
                            pkts
                        }
                        node(PacketParser("RTCP parser") { RtcpPacket.fromBuffer(it.getBuffer()) })
                        //TODO: probably just make a class for this, but for now we're using the cache above to debug
                        simpleNode("Compound RTCP splitter") { pktInfos ->
                            try {
                                val outPackets = mutableListOf<PacketInfo>()
                                pktInfos.forEach { pktInfo ->
                                    val compoundRtcpPackets = RtcpIterator(pktInfo.packet.getBuffer()).getAll()
                                    compoundRtcpPackets.forEach {
                                        // For each compound RTCP packet, create a new PacketInfo
                                        val splitPacket = PacketInfo(it, timeline = pktInfo.timeline.clone())
                                        splitPacket.receivedTime = pktInfo.receivedTime
                                        outPackets.add(splitPacket)
                                    }
                                }
                                outPackets
                            } catch (e: Exception) {
                                logger.cerror {
                                    with (StringBuffer()) {
                                        appendln("Exception extracting RTCP.  The original, decrypted packet buffer is " +
                                                "one of these:")
                                        prevRtcpPackets.forEach {
                                            appendln(it.getBuffer().toHex())
                                        }

                                        toString()
                                    }

                                }
                                emptyList()
                            }
                        }
                        node(rtcpTermination)
                        node(rtcpPacketHandlerWrapper)
                    }
                }
            }
        }
        executor.execute(this::doWork)
    }

    private fun doWork() {
        while (running) {
            val now = System.currentTimeMillis()
            if (firstQueueReadTime == -1L) {
                firstQueueReadTime = now
            }
            numQueueReads++
            lastQueueReadTime = now
            incomingPacketQueue.poll(100, TimeUnit.MILLISECONDS)?.let {
                it.addEvent(PACKET_QUEUE_EXIT_EVENT)
                bytesProcessed += it.packet.size
                packetsProcessed++
                if (firstPacketProcessedTime == 0L) {
                    firstPacketProcessedTime = System.currentTimeMillis()
                }
                lastPacketProcessedTime = System.currentTimeMillis()
                processPackets(listOf(it))
            }
        }
    }

    override fun processPackets(pkts: List<PacketInfo>) = inputTreeRoot.processPackets(pkts)

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("RTP receiver $id").apply {
            addStat( "queue size: ${incomingPacketQueue.size}")
            addStat( "Received $packetsReceived packets ($bytesReceived bytes) in " + "${lastPacketWrittenTime - firstPacketWrittenTime}ms " + "(${getMbps(bytesReceived, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            addStat("Processed $packetsProcessed " + "(${(packetsProcessed / (packetsReceived.toDouble())) * 100}%) ($bytesProcessed bytes) in " + "${lastPacketProcessedTime - firstPacketProcessedTime}ms " + "(${getMbps(bytesProcessed, Duration.ofMillis(lastPacketProcessedTime - firstPacketProcessedTime))} mbps)")
            val queueReadTotal = lastQueueReadTime - firstQueueReadTime
            addStat("Read from queue at a rate of " + "${numQueueReads / (Duration.ofMillis(queueReadTotal).seconds.toDouble())} times per second")
            addStat("The queue was empty $numTimesQueueEmpty out of $numQueueReads times")
            val statsVisitor = NodeStatsVisitor(this)
            inputTreeRoot.visit(statsVisitor)
        }
    }

    override fun enqueuePacket(p: PacketInfo) {
//        logger.cinfo { "Receiver ${hashCode()} enqueing data" }
        bytesReceived += p.packet.size
        p.addEvent(PACKET_QUEUE_ENTRY_EVENT)
        incomingPacketQueue.add(p)
        packetsReceived++
        if (firstPacketWrittenTime == 0L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    override fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer) {
        srtpDecryptWrapper.setTransformer(srtpTransformer)
    }

    override fun setSrtcpTransformer(srtcpTransformer: SinglePacketTransformer) {
        srtcpDecryptWrapper.setTransformer(srtcpTransformer)
    }

    override fun handleEvent(event: Event) {
        inputTreeRoot.visit(NodeEventVisitor(event))
    }

    override fun setAudioLevelListener(audioLevelListener: AudioLevelListener) {
        audioLevelReader.audioLevelListener = audioLevelListener
    }

    override fun getStreamStats(): Map<Long, IncomingStreamStatistics.Snapshot> {
        return statTracker.getCurrentStats().map { (ssrc, stats) ->
            Pair(ssrc, stats.getSnapshot())
        }.toMap()
    }

    override fun stop() {
        running = false
        rtcpRrGenerator.running = false
    }
}
