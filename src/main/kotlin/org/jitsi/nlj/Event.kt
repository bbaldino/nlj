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

import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.format.MediaFormat

interface Event

class RtpPayloadTypeAddedEvent(val payloadType: Byte, val format: MediaFormat) : Event
class RtpPayloadTypeClearEvent : Event

class RtpExtensionAddedEvent(val extensionId: Byte, val rtpExtension: RTPExtension) : Event
class RtpExtensionClearEvent : Event

class ReceiveSsrcAddedEvent(val ssrc: Long) : Event
class ReceiveSsrcRemovedEvent(val ssrc: Long) : Event

class SsrcAssociationEvent(
    val primarySsrc: Long,
    val secondarySsrc: Long,
    val type: String
) : Event

class RtpEncodingsEvent(val rtpEncodings: List<RTPEncodingDesc>) : Event
