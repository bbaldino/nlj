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

import org.bouncycastle.crypto.tls.TlsContext
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.PacketIOActivity
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.TransceiverStreamStats
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.format.MediaFormat
import org.jitsi.util.DiagnosticContext
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService

// This is an API class, so its usages will largely be outside of this library
@Suppress("unused")
/**
 * Handles all packets (incoming and outgoing) for a particular stream.
 * (TODO: 'stream' defined as what, exactly, here?)
 * Handles the DTLS negotiation
 *
 * Incoming packets should be written via [handleIncomingPacket].  Outgoing
 * packets are put in [outgoingQueue] (and should be read by something)
 * TODO: maybe we want to have this 'push' the outgoing packets somewhere
 * else instead (then we could have all senders push to a single queue and
 * have the one thread just read from the queue and send, rather than that thread
 * having to read from a bunch of individual queues)
 */
class Transceiver(
    private val id: String,
    private val receiverExecutor: ExecutorService,
    private val senderExecutor: ExecutorService,
    /**
     * A [ScheduledExecutorService] which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    private val backgroundExecutor: ScheduledExecutorService
) : Stoppable, NodeStatsProducer {
    private val logger = getLogger(this.javaClass)
    private val rtpExtensions = mutableMapOf<Byte, RTPExtension>()
    private val payloadTypes = mutableMapOf<Byte, MediaFormat>()
    private val receiveSsrcs = ConcurrentHashMap.newKeySet<Long>()
    val packetIOActivity = PacketIOActivity()
    private val endpointConnectionStats = EndpointConnectionStats()
    /**
     * A central place to subscribe to be notified on the reception or transmission of RTCP packets for
     * this transceiver.  This is intended to be used by internal entities: mainly logic for things like generating
     * SRs and RRs and calculating RTT.  Since it is used for both send and receive, it is held here and passed to
     * the sender and receive so each can push or subscribe to updates.
     */
    private val rtcpEventNotifier = RtcpEventNotifier()

    private val transportCcEngine = TransportCCEngine(DiagnosticContext())

    private val rtpSender: RtpSender = RtpSenderImpl(id, transportCcEngine, rtcpEventNotifier, senderExecutor, backgroundExecutor)
    private val rtpReceiver: RtpReceiver =
        RtpReceiverImpl(
            id,
            { rtcpPacket ->
                rtpSender.sendRtcp(listOf(rtcpPacket))
            },
            transportCcEngine,
            rtcpEventNotifier,
            receiverExecutor,
            backgroundExecutor)
    val outgoingQueue = LinkedBlockingQueue<PacketInfo>()

    init {
        logger.cinfo { "Transceiver ${this.hashCode()} using receiver executor ${receiverExecutor.hashCode()} " +
                "and sender executor ${senderExecutor.hashCode()}" }

        rtcpEventNotifier.addRtcpEventListener(endpointConnectionStats)

        // Replace the sender's default packet handler with one that will add packets to outgoingQueue
        rtpSender.packetSender = object : Node("RTP packet sender") {
            override fun doProcessPackets(p: List<PacketInfo>) {
                outgoingQueue.addAll(p)
            }
        }
    }

    /**
     * Handle an incoming [PacketInfo] (that is, a packet received by the endpoint
     * this transceiver is associated with) to be processed by the receiver pipeline.
     */
    fun handleIncomingPacket(p: PacketInfo) {
        packetIOActivity.lastPacketReceivedTimestampMs = System.currentTimeMillis()
        rtpReceiver.enqueuePacket(p)
    }

    /**
     * Send packets to the endpoint this transceiver is associated with by
     * passing them out the sender's outgoing pipeline
     */
    fun sendRtp(rtpPackets: List<PacketInfo>) {
        packetIOActivity.lastPacketSentTimestampMs = System.currentTimeMillis()
        rtpSender.sendPackets(rtpPackets)
    }

    fun sendRtcp(rtcpPackets: List<RtcpPacket>) = rtpSender.sendRtcp(rtcpPackets)

    /**
     * Set a handler to be invoked when incoming RTP packets have finished
     * being processed.
     */
    fun setIncomingRtpHandler(rtpHandler: PacketHandler) {
        rtpReceiver.rtpPacketHandler = rtpHandler
    }

    /**
     * Set a handler to be invoked when incoming RTCP packets (which have not
     * bee terminated) have finished being processed.
     */
    fun setIncomingRtcpHandler(rtcpHandler: PacketHandler) {
        rtpReceiver.rtcpPacketHandler = rtcpHandler
    }

    fun addReceiveSsrc(ssrc: Long) {
        logger.cinfo { "Transceiver ${hashCode()} adding receive ssrc $ssrc" }
        receiveSsrcs.add(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcAddedEvent(ssrc))
        //TODO: fire events to rtp sender as well
    }

    fun removeReceiveSsrc(ssrc: Long) {
        logger.cinfo { "Transceiver ${hashCode()} removing receive ssrc $ssrc" }
        receiveSsrcs.remove(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcRemovedEvent(ssrc))
    }

    fun receivesSsrc(ssrc: Long): Boolean = receiveSsrcs.contains(ssrc)

    fun setRtpEncodings(rtpEncodings: Array<RTPEncodingDesc>) {
        val event = RtpEncodingsEvent(Arrays.asList(*rtpEncodings))
        rtpReceiver.handleEvent(event)
        rtpSender.handleEvent(event)
    }

    fun addDynamicRtpPayloadType(rtpPayloadType: Byte, format: MediaFormat) {
        payloadTypes[rtpPayloadType] = format
        logger.cinfo { "Payload type added: $rtpPayloadType -> $format" }
        val rtpPayloadTypeAddedEvent = RtpPayloadTypeAddedEvent(rtpPayloadType, format)
        rtpReceiver.handleEvent(rtpPayloadTypeAddedEvent)
        rtpSender.handleEvent(rtpPayloadTypeAddedEvent)
    }

    fun clearDynamicRtpPayloadTypes() {
        logger.cinfo { "All payload types being cleared" }
        val rtpPayloadTypeClearEvent = RtpPayloadTypeClearEvent()
        rtpReceiver.handleEvent(rtpPayloadTypeClearEvent)
        rtpSender.handleEvent(rtpPayloadTypeClearEvent)
        payloadTypes.clear()
    }

    fun addDynamicRtpPayloadTypeOverride(originalPt: Byte, overloadPt: Byte) {
        //TODO
        logger.cinfo { "Overriding payload type $originalPt to $overloadPt" }
    }

    fun addRtpExtension(extensionId: Byte, rtpExtension: RTPExtension) {
        logger.cinfo { "Adding RTP extension: $extensionId -> $rtpExtension" }
        rtpExtensions[extensionId] = rtpExtension
        val rtpExtensionAddedEvent = RtpExtensionAddedEvent(extensionId, rtpExtension)
        rtpReceiver.handleEvent(rtpExtensionAddedEvent)
        rtpSender.handleEvent(rtpExtensionAddedEvent)
    }

    fun clearRtpExtensions() {
        logger.cinfo { "Clearing all RTP extensions" }
        //TODO: ignoring this for now, since we'll have conflicts from each channel calling it
//        val rtpExtensionClearEvent = RtpExtensionClearEvent()
//        rtpReceiver.handleEvent(rtpExtensionClearEvent)
//        rtpSender.handleEvent(rtpExtensionClearEvent)
//        rtpExtensions.clear()
    }

    fun setAudioLevelListener(audioLevelListener: AudioLevelListener) {
        logger.cinfo { "BRIAN: transceiver setting csrc audio level listener on receiver" }
        rtpReceiver.setAudioLevelListener(audioLevelListener)
    }

    // TODO(brian): we may want to handle local and remote ssrc associations differently, as different parts of the
    // code care about one or the other, but currently there is no issue treating them the same.
    fun addSsrcAssociation(primarySsrc: Long, secondarySsrc: Long, type: String) {
        logger.cinfo { "Transeceiver $id adding ssrc association: $primarySsrc <-> $secondarySsrc ($type)"}
        val ssrcAssociationEvent = SsrcAssociationEvent(primarySsrc, secondarySsrc, type)
        rtpReceiver.handleEvent(ssrcAssociationEvent)
        rtpSender.handleEvent(ssrcAssociationEvent)
    }

    fun setSrtpInformation(chosenSrtpProtectionProfile: Int, tlsContext: TlsContext) {
        val srtpProfileInfo =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        val keyingMaterial = SrtpUtil.getKeyingMaterial(tlsContext, srtpProfileInfo)
        logger.cinfo { "Transceiver $id creating transformers with:\n" +
                "profile info:\n$srtpProfileInfo\n" +
                "keyingMaterial:\n${ByteBuffer.wrap(keyingMaterial).toHex()}\n" +
                "tls role: ${TlsRole.fromTlsContext(tlsContext)}" }
        val srtpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            TlsRole.fromTlsContext(tlsContext),
            false
        )
        val srtcpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            TlsRole.fromTlsContext(tlsContext),
            true
        )

        rtpReceiver.setSrtpTransformer(srtpTransformer)
        rtpReceiver.setSrtcpTransformer(srtcpTransformer)
        rtpSender.setSrtpTransformer(srtpTransformer)
        rtpSender.setSrtcpTransformer(srtcpTransformer)
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Transceiver $id").apply {
            addStat("RTP Receiver", rtpReceiver.getNodeStats())
            addStat("RTP Sender", rtpSender.getNodeStats())

        }
    }

    fun getStreamStats(): TransceiverStreamStats {
        return TransceiverStreamStats(rtpReceiver.getStreamStats(), rtpSender.getStreamStats())
    }

    override fun stop() {
        rtpReceiver.stop()
        rtpSender.stop()
    }
}
