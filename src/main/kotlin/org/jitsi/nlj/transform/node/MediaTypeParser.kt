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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.RtpPacket
import org.jitsi.service.neomedia.MediaType
import org.jitsi.service.neomedia.format.MediaFormat
import unsigned.toUByte
import java.util.concurrent.ConcurrentHashMap

/**
 * Parse RTP packets as either [VideoRtpPacket]s or [AudioRtpPacket]s
 */
class MediaTypeParser : Node("Media type parser") {
    private val payloadFormats: MutableMap<Byte, MediaFormat> = ConcurrentHashMap()

    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEachAs<RtpPacket> { pktInfo, pkt ->
            val mediaType = payloadFormats[pkt.header.payloadType.toUByte()]?.mediaType ?: run {
                logger.cdebug { "Unable to find format for payload type ${pkt.header.payloadType}" }
                return@forEachAs
            }
            pktInfo.packet = when (mediaType) {
                MediaType.AUDIO -> AudioRtpPacket(pkt.getBuffer())
                MediaType.VIDEO -> VideoRtpPacket(pkt.getBuffer())
                else -> throw Exception("Unrecognized media type: '$mediaType'")
            }
        }
        next(p)
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> payloadFormats[event.payloadType] = event.format
            is RtpPayloadTypeClearEvent -> payloadFormats.clear()
        }
        super.handleEvent(event)
    }

}
