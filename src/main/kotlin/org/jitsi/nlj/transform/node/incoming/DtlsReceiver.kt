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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.dtls.DtlsStack
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cdebug

/**
 * [DtlsReceiverNode] bridges a chain of modules to a DTLS's transport
 * (used by the bouncycastle stack).  Receives incoming DTLS data, passes it
 * to the DTLS stack and forwards any DTLS app packets
 */
class DtlsReceiver(
        private val dtlsStack: DtlsStack
) : Node("DTLS Receiver") {
    override fun doProcessPackets(p: List<PacketInfo>) {
        logger.cdebug { "DTLS receiver processing incoming DTLS packets" }
        val appPackets = dtlsStack.processIncomingDtlsPackets(p)
        next(appPackets)
    }
}
